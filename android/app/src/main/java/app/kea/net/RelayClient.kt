package app.kea.net

import android.util.Log
import app.kea.crypto.B64
import app.kea.crypto.Identity
import app.kea.crypto.ReceiverState
import app.kea.crypto.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import okio.ByteString
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * The relay socket.
 *
 * Everything crossing this boundary is either cleartext control (roster,
 * watching, pull requests) or an opaque sealed frame. This class never looks
 * inside a binary frame — it forwards bytes, and [openFrame] is the only place
 * that decrypts, and only after the sender has been authenticated against the
 * roster.
 */
class RelayClient(
    private val scope: CoroutineScope,
    private val onControl: (Control) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onState: (Connection) -> Unit = {},
) {
    enum class Connection { OFFLINE, CONNECTING, ONLINE }

    private val http = OkHttpClient.Builder()
        // Long enough to ride out a free-tier cold start, short enough that a
        // dead network is noticed rather than silently hanging.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSockets are long-lived
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(HEARTBEAT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var closedSignal: CompletableDeferred<Unit>? = null
    private val lifecycle = Mutex()
    private var job: Job? = null

    private val _connection = MutableStateFlow(Connection.OFFLINE)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Emitted after each successful (re)connect, so callers can replay state. */
    private val _reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reconnects: SharedFlow<Unit> = _reconnects.asSharedFlow()

    private val random = SecureRandom()

    // ---- crypto state, reset on every connection -------------------------

    private var identity: Identity? = null
    private var groupId: ByteArray? = null
    private var groupKey: ByteArray? = null
    private var sessionId = ByteArray(Wire.SESSION_ID_BYTES)
    private var seq = 0L
    private val receiverState = ReceiverState()

    private var wantWatching = false

    /** Display name, readable before the first socket opens. */
    @Volatile var displayName: String = ""
        private set

    val selfDeviceId: String get() = identity?.deviceId ?: ""

    // ---- lifecycle -------------------------------------------------------

    /**
     * Connect, or update the intent of an already-open connection.
     *
     * Idempotent: the service calls this on every location tick and the UI on
     * every lifecycle change.
     */
    suspend fun connect(
        relayUrl: String,
        identity: Identity,
        groupId: ByteArray,
        groupKey: ByteArray,
        displayName: String,
        watching: Boolean,
    ) = lifecycle.withLock {
        this.identity = identity
        this.groupId = groupId
        this.groupKey = groupKey
        this.displayName = displayName
        wantWatching = watching

        if (job?.isActive == true) {
            if (watching != wantWatchingOnSocket) sendWatch(watching)
            return@withLock
        }
        job = scope.launch(Dispatchers.IO) { runLoop(relayUrl) }
    }

    private var wantWatchingOnSocket = false

    private suspend fun runLoop(relayUrl: String) {
        var attempt = 0
        while (true) {
            _connection.value = Connection.CONNECTING
            onState(Connection.CONNECTING)

            val signal = CompletableDeferred<Unit>()
            closedSignal = signal
            sessionId = ByteArray(Wire.SESSION_ID_BYTES).also(random::nextBytes)
            seq = 0L
            receiverState.forget(selfDeviceId)

            openSocket(relayUrl)
            // Resolves on close or failure; then back off and try again.
            signal.await()

            val wait = backoffMs(attempt++)
            Log.i(TAG, "relay closed; reconnecting in ${wait}ms")
            delay(wait)
        }
    }

private fun openSocket(relayUrl: String) {
        val ident = identity ?: return
        // The group id goes in the path, not only in the `hello` frame.
        //
        // Cloudflare Durable Objects are selected *before* the WebSocket
        // upgrade, so an edge that routes by group cannot wait for a body
        // frame to learn where to send the socket. Both backends accept
        // `/ws/<groupId>`, so one URL setting works against either.
        val gid = groupId?.let { B64.enc(it) } ?: return
        val url = RelayEndpoint.wsUrl(relayUrl, gid)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KEA/1.0 (Android)")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                _connection.value = Connection.ONLINE
                onState(Connection.ONLINE)
                webSocket.send(
                    ControlJson.encodeToString(
                        Hello.serializer(),
                        Hello(
                            group = gid,
                            dev = ident.deviceId,
                            name = displayName,
                            pk = B64.enc(ident.publicKey),
                        ),
                    ),
                )
                wantWatchingOnSocket = wantWatching
                if (wantWatching) sendWatch(true)
                // Tell listeners to replay their watch state and flush queues.
                _reconnects.tryEmit(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseControl(text)?.let(onControl)
            }

override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Content boundary: not one byte is interpreted here.
                val frame = bytes.toByteArray()
                if (frame.size <= Wire.MAX_FRAME_BYTES) onFrame(frame)
            }


            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connection.value = Connection.OFFLINE
                onState(Connection.OFFLINE)
                closedSignal?.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                _connection.value = Connection.OFFLINE
                onState(Connection.OFFLINE)
                closedSignal?.complete(Unit)
            }
        })
    }

    // ---- outbound control ------------------------------------------------

    private fun sendWatch(on: Boolean) {
        val dev = selfDeviceId
        val ws = socket ?: return
        val json = runCatching {
            ControlJson.encodeToString(Watch.serializer(), Watch(dev = dev, on = on))
        }.getOrNull() ?: return
        ws.send(json)
        wantWatchingOnSocket = on
    }

    fun setWatching(on: Boolean) {
        wantWatching = on
        sendWatch(on)
    }

    /** "Get live location": ask one member to stream for a bounded window. */
    fun requestLivePosition(from: String, ttlMs: Long) {
        val clamped = min(ttlMs, WatchGate.PULL_MAX_MS).coerceAtLeast(1_000L)
        socket?.send(
            runCatching {
                ControlJson.encodeToString(
                    Pull.serializer(),
                    Pull(dev = from, ttl = (clamped / 1000).toInt()),
                )
            }.getOrNull() ?: return,
        )
    }

    fun leave() {
        val gid = groupId ?: return
        val json = runCatching {
            ControlJson.encodeToString(Leave.serializer(), Leave(group = B64.enc(gid), dev = selfDeviceId))
        }.getOrNull() ?: return
        socket?.send(json)
    }

    // ---- sealed payloads -------------------------------------------------

    /** Seal and send one payload. False when offline or on a seal failure. */
    fun sendPayload(payload: Map<String, Any?>): Boolean {
        val ident = identity ?: return false
        val gid = groupId ?: return false
        val key = groupKey ?: return false
        val ws = socket ?: return false
return try {
            val frame = Wire.seal(
                groupId = gid,
                groupKey = key,
                identity = ident,
                sessionId = sessionId,
                seq = seq++,
                payloadMap = payload,
            )
            ws.send(frame.toByteString())
            true
        } catch (t: Throwable) {
            // A failed seal must not take down the stream. Drop the sample.
            Log.w(TAG, "seal failed: ${t.message}")
            false
        }
    }

    /** A verified payload plus the authenticated identity that sent it. */
    class Opened(val deviceId: String, val payload: Map<String, Any?>)

    /**
     * Verify then decrypt an inbound frame.
     *
     * The sender's id comes from the authenticated header, never from the
     * payload body, so a peer cannot claim to be somebody else.
     *
     * @param publicKeyOf resolves a roster public key by device id. Returning
     *   null means we have not heard of that device, and the frame is dropped.
     */
    fun openFrame(frame: ByteArray, publicKeyOf: (String) -> ByteArray?): Opened? {
        val gid = groupId ?: return null
        val key = groupKey ?: return null
        return try {
            val header = Wire.parse(frame)
            val pub = publicKeyOf(header.deviceId) ?: return null
            Opened(header.deviceId, Wire.open(frame, gid, key, pub, receiverState))
        } catch (t: Throwable) {
            // Expected in normal operation: a hostile or broken relay, a stale
            // key after rotation, or a duplicate. Log at debug and drop.
            Log.d(TAG, "frame rejected: ${t::class.java.simpleName}: ${t.message}")
            null
        }
    }

    suspend fun stop() = lifecycle.withLock {
        job?.cancelAndJoin()
        job = null
        socket?.close(1000, "bye")
        socket = null
        closedSignal?.complete(Unit)
        _connection.value = Connection.OFFLINE
    }

    /**
     * Exponential backoff with full jitter.
     *
     * Jitter matters more than the exponent here: every client that lost the same
     * free-tier relay retries in lockstep otherwise, and hammers the instance
     * exactly while it is waking up.
     */
    private fun backoffMs(attempt: Int): Long {
        val ceiling = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtMost(8)).toLong())
        return BASE_BACKOFF_MS + (random.nextDouble() * ceiling).toLong()
    }

    companion object {
        private const val TAG = "RelayClient"
        const val HEARTBEAT_SEC = 25L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}