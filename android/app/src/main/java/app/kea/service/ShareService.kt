package app.kea.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.kea.BuildConfig
import app.kea.R
import app.kea.crypto.B64
import app.kea.crypto.Identity
import app.kea.geo.GeofenceEngine
import app.kea.net.Control
import app.kea.net.RelayClient
import app.kea.net.WatchGate
import app.kea.data.Member
import app.kea.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine.
 *
 * Holds the foreground notification, watches location, evaluates geofences
 * locally, and uploads — but only while [WatchGate] says somebody is actually
 * looking. Everything above it (UI, notifications) reads state rather than
 * driving it, so the app behaves identically whether or not the map is open.
 */
class ShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: Store
    private lateinit var relay: RelayClient
    private val gate = WatchGate()
    private val fences = GeofenceEngine()

    private var identity: Identity? = null
    private var locations: LocationManager? = null

    /** Serialises store writes so a fix and a roster cannot interleave. */
    private val writeLock = Mutex()

    private val _isWatchingMap = MutableStateFlow(false)
    val isWatchingMap: StateFlow<Boolean> = _isWatchingMap.asStateFlow()

    private val _lastFix = MutableStateFlow<Location?>(null)
    val lastFix: StateFlow<Location?> = _lastFix.asStateFlow()

    private val _relayConnection = MutableStateFlow(RelayClient.Connection.OFFLINE)
    val relayConnection: StateFlow<RelayClient.Connection> = _relayConnection.asStateFlow()

    private val gateState: StateFlow<WatchGate.State> get() = gate.state

    private val started = AtomicBoolean(false)
    private var locationActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(applicationContext)
        Notifications.createChannels(applicationContext)
        locations = getSystemService<LocationManager>()

        relay = RelayClient(
            scope = scope,
            onControl = ::handleControl,
            onFrame = ::handleFrame,
            onState = { _relayConnection.value = it },
        )

        PullResponseDispatcher.install(
            onGrant = { grantPendingRequest() },
            onDeny = { denyPendingRequest() },
        )

        scope.launch {
            // Load or mint the identity before anything needs to sign.
            identity = ensureIdentity()
            startForegroundIfPermitted()
            connectIfPossible()
            observeStore()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_WATCHING -> setMapWatching(intent.getBooleanExtra(EXTRA_WATCHING, false))
            ACTION_PULL -> intent.getStringExtra(EXTRA_TARGET)?.let {
                relay.requestLivePosition(it, intent.getLongExtra(EXTRA_TTL, WatchGate.PULL_DEFAULT_MS))
            }
        }
        startForegroundIfPermitted()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    // ---- identity --------------------------------------------------------

    private suspend fun ensureIdentity(): Identity {
        store.identitySeedB64()?.let { b64 ->
            runCatching { Identity.fromSeed(B64.dec(b64)) }.getOrNull()?.let { return it }
        }
        val fresh = Identity.generate()
        store.setIdentitySeed(B64.enc(fresh.seed))
        return fresh
    }

    // ---- lifecycle -------------------------------------------------------

    private fun startForegroundIfPermitted() {
        if (!hasLocationPermission()) return
        if (!started.compareAndSet(false, true)) return
        runCatching {
            startForeground(
                Notifications.ID_FOREGROUND,
                Notifications.foreground(
                    Notifications.ForegroundState(this, Notifications.ForegroundPhase.IDLE),
                    "",
                ),
            )
        }.onFailure { Log.w(TAG, "startForeground refused: ${it.message}") }
    }

    private suspend fun connectIfPossible() {
        val session = store.session.first()
        val group = session.group ?: return
        val ident = identity ?: return
        runCatching {
            relay.connect(
                relayUrl = currentRelayUrl(),
                identity = ident,
                groupId = B64.dec(group.groupId),
                groupKey = B64.dec(group.groupKey),
                displayName = store.settings.first().displayName,
                watching = _isWatchingMap.value,
            )
        }.onFailure { Log.w(TAG, "connect failed: ${it.message}") }
    }

    private suspend fun currentRelayUrl(): String {
        val configured = store.settings.first().relayUrl
        return configured.ifBlank { BuildConfig.RELAY_URL }
    }

    private fun observeStore() {
        // Keep the watch flag in sync with whoever is actually drawing the map.
        scope.launch {
            relay.reconnects.collect {
                relay.setWatching(_isWatchingMap.value)
                // Re-assert our presence after a reconnect, and flush anything
                // that happened while the socket was down.
                runCatching { store.updateSession { it } }
                flushPendingFences()
            }
        }
        scope.launch {
            // The tick that expires pull leases and ends the grace window.
            while (started.get()) {
                gate.tick(identity?.deviceId.orEmpty(), System.currentTimeMillis())
                delay(TICK_MS)
            }
        }
    }

    fun setMapWatching(on: Boolean) {
        if (_isWatchingMap.value == on) return
        _isWatchingMap.value = on
        relay.setWatching(on)
        if (!on) {
            // We are no longer looking, so stop demanding other people's
            // positions, but keep streaming briefly for anyone still watching us.
            gate.onLocalViewerStopped()
            requestLocationUpdates()
        } else {
            requestLocationUpdates()
        }
    }

    private fun stopEverything() {
        if (started.compareAndSet(true, false)) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        removeLocationUpdates()
        _relayConnection.value = RelayClient.Connection.OFFLINE
        PullResponseDispatcher.clear()
    }

    // ---- location --------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _lastFix.value = location
            scope.launch { onFix(location) }
        }

        @Deprecated("Required by the pre-30 interface")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            requestLocationUpdates()
        }

        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * Ask for location updates.
     *
     * The rate is deliberately not always-on. When nobody is watching we ask
     * for the cheap, infrequent updates that still let geofences work, and only
     * switch to the full rate while streaming.
     */
    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        val lm = locations ?: return
        if (!locationActive) {
            locationActive = true
            runCatching { lm.requestLocationUpdates(provider(), IDLE_INTERVAL_MS, 0f, locationListener) }
                .onFailure { Log.w(TAG, "requestLocationUpdates: ${it.message}") }
        }
        maybeUpgradeRate()
    }

    private fun removeLocationUpdates() {
        if (!locationActive) return
        locationActive = false
        runCatching { locations?.removeUpdates(locationListener) }
    }

    private var lastRate = 0L

    private fun maybeUpgradeRate() {
        val streaming = gateState.value.shouldStream
        val wanted = if (streaming) LIVE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (wanted == lastRate) return
        lastRate = wanted
        if (!locationActive) return
        runCatching {
            locations?.removeUpdates(locationListener)
            locations?.requestLocationUpdates(provider(), wanted, 0f, locationListener)
        }.onFailure { Log.w(TAG, "retune location: ${it.message}") }
    }

    private fun provider(): String = when {
        locations?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true -> LocationManager.GPS_PROVIDER
        locations?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true -> LocationManager.NETWORK_PROVIDER
        else -> LocationManager.GPS_PROVIDER
    }

    // ---- a fix arrives ---------------------------------------------------

    private suspend fun onFix(location: Location) {
        val session = store.session.first()
        val ident = identity ?: return

        maybeUpgradeRate()

        // Geofences are evaluated for *self* always, network or not. This is
        // what makes "you arrived home" work with the map closed.
        val (inside, transitions) = fences.evaluate(
            zones = session.zones,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
            inside = session.inside,
            now = System.currentTimeMillis(),
        )

        for (t in transitions) {
            if (fences.shouldNotify(t.zone, t.enter, isSelf = true)) {
                Notifications.showFence(
                    context = this,
                    id = t.zone.id.hashCode(),
                    title = getString(R.string.app_name),
                    body = if (t.enter) {
                        getString(R.string.notif_fence_self_enter, t.zone.name)
                    } else {
                        getString(R.string.notif_fence_self_exit, t.zone.name)
                    },
                )
            }
            // Queue for the group: if somebody is watching, this flushes now;
            // otherwise it waits until they are.
            runCatching {
                store.updateSession { s ->
                    s.copy(
                        inside = inside,
                        pendingFences = s.pendingFences + t.toPending(displayName(), ident.deviceId),
                    )
                }
            }
        }

        if (transitions.isEmpty() && inside != session.inside) {
            runCatching { store.updateSession { it.copy(inside = inside) } }
        }

        // Record our own fix so the map always has something to draw for us.
        runCatching {
            store.updateSession { s ->
                s.copy(members = s.members.upsertSelf(
                    deviceId = ident.deviceId,
                    name = displayName(),
                    publicKey = B64.enc(ident.publicKey),
                    location = location,
                    battery = batteryLevel(),
                    online = true,
                ))
            }
        }

        // ---- the watch-gate: the only reason we transmit at all ----------
        if (gateState.value.shouldStream) {
            relay.sendPayload(
                linkedMapOf<String, Any?>(
                    "type" to "LOC",
                    "lat" to location.latitude,
                    "lon" to location.longitude,
                    "acc" to if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    "alt" to if (location.hasAltitude()) location.altitude else null,
                    "spd" to if (location.hasSpeed()) location.speed.toDouble() else null,
                    "brg" to if (location.hasBearing()) location.bearing.toDouble() else null,
                    "bat" to batteryLevel(),
                    "src" to when (location.provider) {
                        LocationManager.GPS_PROVIDER -> "gps"
                        LocationManager.NETWORK_PROVIDER -> "network"
                        else -> "fused"
                    },
                ),
            )
            flushPendingFences()
            updateForegroundNotification()
        }
    }

    /**
     * Send queued geofence events.
     *
     * They were detected on the right phone at the right time; only the delivery
     * was deferred. The timestamp travels with them so a recipient sees when it
     * actually happened, not when it happened to be flushed.
     */
    private suspend fun flushPendingFences() {
        val session = store.session.first()
        val queued = session.pendingFences
        if (queued.isEmpty()) return
        for (event in queued) {
            val sent = relay.sendPayload(
                linkedMapOf<String, Any?>(
                    "type" to "FENC",
                    "fid" to event.zoneId,
                    "ev" to if (event.enter) "enter" else "exit",
                    "lat" to event.lat,
                    "lon" to event.lon,
                    "at" to event.at,
                ),
            )
            if (!sent) return   // stay queued; we are offline
        }
        runCatching { store.updateSession { it.copy(pendingFences = emptyList()) } }
    }

    private fun displayName(): String = nameCache

    @Volatile private var nameCache: String = ""

    private fun batteryLevel(): Float? = runCatching {
        val bm = getSystemService<BatteryManager>() ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) pct / 100f else null
    }.getOrNull()

    private fun updateForegroundNotification() {
        val phase = when (gateState.value.reason) {
            WatchGate.Reason.WATCHED -> Notifications.ForegroundPhase.LIVE
            WatchGate.Reason.COOLING_DOWN -> Notifications.ForegroundPhase.COOLING_DOWN
            else -> Notifications.ForegroundPhase.IDLE
        }
        val summary = watcherSummary()
        runCatching {
            getSystemService<android.app.NotificationManager>()
                ?.notify(
                    Notifications.ID_FOREGROUND,
                    Notifications.foreground(Notifications.ForegroundState(this, phase), summary),
                )
        }
    }

    /** Localized "Being watched by Ana" / "by 3 people". */
    private fun watcherSummary(): String {
        val watchers = gateState.value.watchers
        if (watchers.isEmpty()) return getString(R.string.sharing_idle)
        if (watchers.size == 1) {
            val dev = watchers.first()
            val name = watcherNames[dev] ?: dev
            return getString(R.string.sharing_watched_by, name)
        }
        return getString(R.string.sharing_watched_by_n, watchers.size)
    }

    @Volatile private var watcherNames: Map<String, String> = emptyMap()

    // ---- inbound ---------------------------------------------------------

    private fun handleControl(control: Control) {
        scope.launch { applyControl(control) }
    }

    private suspend fun applyControl(control: Control) {
        val ident = identity ?: return
        val now = System.currentTimeMillis()

        when (control) {
            is Control.Joined -> Unit

            is Control.RosterChanged -> {
                val self = control.roster.members.firstOrNull { it.dev == ident.deviceId }
                val names = control.roster.members.associate { it.dev to it.name }
                watcherNames = names

                writeLock.withLock {
                    store.updateSession { s ->
                        val known = s.members.associateBy { it.deviceId }
                        val merged = control.roster.members.map { entry ->
                            // Preserve the last known fix across a roster refresh;
                            // otherwise every roster update would blank the map.
                            val previous = known[entry.dev]
                            (previous ?: Member(entry.dev, entry.name, entry.pk, isSelf = entry.dev == ident.deviceId))
                                .copy(
                                    name = entry.name,
                                    publicKey = entry.pk,
                                    online = entry.on,
                                    watching = entry.watching,
                                    seenAt = entry.seen,
                                    isSelf = entry.dev == ident.deviceId,
                                )
                        }
                        s.copy(members = merged)
                    }
                }
                gate.onRoster(
                    store.session.first().members,
                    ident.deviceId, now,
                )
            }

            is Control.WatchChanged ->
                gate.onWatch(control.watch.dev, control.watch.on, ident.deviceId, now)

            is Control.PullRequested -> {
                if (control.request.from == ident.deviceId) return
                Notifications.showPullRequest(
                    context = this,
                    id = control.request.from.hashCode(),
                    requesterName = control.request.name,
                    ttlMs = control.request.ttl.toLong() * 1000,
                    onGrant = { grantPendingRequest() },
                    onDeny = { denyPendingRequest() },
                )
                PostedPullIds.record(control.request.from)
            }

            is Control.PullAnnounced -> Unit

            is Control.PullEnded ->
                gate.onRequestExpired(control.expired.dev, ident.deviceId, now)
        }
    }

    @Volatile private var grantedRequester: String? = null

    private fun grantPendingRequest() {
        val from = PostedPullIds.take() ?: return
        val ident = identity ?: return
        grantedRequester = from
        gate.onRequest(from, from, WatchGate.PULL_DEFAULT_MS, System.currentTimeMillis(), ident.deviceId)
    }

    private fun denyPendingRequest() {
        PostedPullIds.take()
    }

    private fun handleFrame(frame: ByteArray) {
        scope.launch {
            // One session read for the whole frame, not one per roster lookup.
            val session = store.session.first()
            val opened = relay.openFrame(frame) { dev ->
                session.members.firstOrNull { it.deviceId == dev }?.publicKey?.let { B64.decOrNull(it) }
            } ?: return@launch

            when (opened.payload["type"] as? String) {
                "LOC" -> applyLoc(opened.deviceId, opened.payload)
                "FENC" -> applyFence(opened.deviceId, opened.payload)
                "MEM" -> Unit
            }
        }
    }

    private suspend fun applyLoc(deviceId: String, payload: Map<String, Any?>) {
        val member = store.session.first().members.firstOrNull { it.deviceId == deviceId } ?: return
        // A relay should never echo our own frames, but if it does, ignore them.
        if (member.isSelf) return

        val updated = member.copy(
            lat = (payload["lat"] as? Number)?.toDouble(),
            lon = (payload["lon"] as? Number)?.toDouble(),
            accuracy = (payload["acc"] as? Number)?.toDouble(),
            altitude = (payload["alt"] as? Number)?.toDouble(),
            speed = (payload["spd"] as? Number)?.toDouble(),
            bearing = (payload["brg"] as? Number)?.toDouble(),
            battery = (payload["bat"] as? Number)?.toFloat(),
            source = payload["src"] as? String,
            fixAt = System.currentTimeMillis(),
            online = true,
        )
        store.updateSession { s -> s.copy(members = s.members.replace(updated)) }
    }

    private suspend fun applyFence(deviceId: String, payload: Map<String, Any?>) {
        val session = store.session.first()
        val zone = session.zones.firstOrNull { it.id == (payload["fid"] as? String) } ?: return
        val member = session.members.firstOrNull { it.deviceId == deviceId } ?: return
        val enter = payload["ev"] == "enter"

        if (!fences.shouldNotify(zone, enter, isSelf = member.isSelf)) return

        Notifications.showFence(
            context = this,
            id = (deviceId + zone.id).hashCode(),
            title = getString(R.string.app_name),
            body = if (enter) {
                getString(R.string.notif_fence_other_enter, member.name, zone.name)
            } else {
                getString(R.string.notif_fence_other_exit, member.name, zone.name)
            },
        )
    }

    // ---- helpers ---------------------------------------------------------

    companion object {
        private const val TAG = "ShareService"

        /** Fast, smooth, and only while somebody is watching. */
        const val LIVE_INTERVAL_MS = 4_000L

        /** Slow enough to preserve battery, but still detects zone crossings. */
        const val IDLE_INTERVAL_MS = 30_000L

        private const val TICK_MS = 5_000L

        const val ACTION_STOP = "app.kea.STOP"
        const val ACTION_WATCHING = "app.kea.WATCHING"
        const val ACTION_PULL = "app.kea.PULL"
        const val EXTRA_WATCHING = "watching"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TTL = "ttl"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ShareService::class.java).setAction(ACTION_WATCHING),
                )
            }
        }

        fun setWatching(context: Context, watching: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, ShareService::class.java)
                        .setAction(ACTION_WATCHING)
                        .putExtra(EXTRA_WATCHING, watching),
                )
            }
        }

        fun requestPull(context: Context, target: String, ttlMs: Long) {
            runCatching {
                context.startService(
                    Intent(context, ShareService::class.java)
                        .setAction(ACTION_PULL)
                        .putExtra(EXTRA_TARGET, target)
                        .putExtra(EXTRA_TTL, ttlMs),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ShareService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

/** Remembers which pull requests are on screen so a tap can be attributed. */
object PostedPullIds {
    private val ids = java.util.concurrent.ConcurrentLinkedQueue<String>()
    fun record(id: String) {
        ids.add(id)
    }

    fun take(): String? = ids.poll()
}