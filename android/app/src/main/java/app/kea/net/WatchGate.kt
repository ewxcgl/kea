package app.kea.net

import app.kea.data.Member
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The watch-gate — the reason this app is worth installing.
 *
 * A device transmits **nothing** unless a peer currently has the map open, or
 * somebody has explicitly asked for its live position. No heartbeats, no
 * "last seen", no idle pings. That is what makes continuous location sharing
 * survivable on a phone battery, and it is enforced here rather than trusted to
 * the relay, which has no idea what a payload means.
 *
 * All mutation happens on whatever thread the caller is on; the maps are
 * concurrent and the flows are atomic, so no extra locking is needed. Sequence
 * points (renew vs. expire) are funnelled through [reconcile] to keep the
 * decision consistent.
 */
class WatchGate {

    /** Streaming is live while any of these holds. */
    enum class Reason {
        /** Nobody is watching. Nothing is transmitted. */
        IDLE,

        /** At least one peer has the map open. */
        WATCHED,

        /** A peer asked for our position directly, via "Get live location". */
        REQUESTED,

        /** A watcher just went away; keep streaming briefly to avoid flapping. */
        COOLING_DOWN,
    }

    data class State(
        val reason: Reason = Reason.IDLE,
        /** Members with the map open, excluding self. */
        val watchers: Set<String> = emptySet(),
        /** Who asked for our position and until when (epoch ms). */
        val requests: Map<String, Pair<String, Long>> = emptyMap(),
        val shouldStream: Boolean = false,
        /** Epoch ms at which streaming will stop if nothing renews us. */
        val streamUntil: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** deviceId -> (requesterName, deadlineEpochMs). */
    private val requests = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Called on every roster and every watch broadcast.
     *
     * @param self this device's id, excluded from "someone is watching me"
     * @param graceMs how long to keep streaming after the last watcher leaves
     * @param now epoch ms
     */
    fun onRoster(members: List<Member>, self: String, now: Long, graceMs: Long = GRACE_MS) {
        val watchers = members.filter { it.deviceId != self && it.watching }.map { it.deviceId }.toSet()
        _state.update { it.copy(watchers = watchers) }
        reconcile(self, now, graceMs)
    }

    fun onWatch(deviceId: String, on: Boolean, self: String, now: Long, graceMs: Long = GRACE_MS) {
        _state.update { s ->
            val watchers = if (on) s.watchers + deviceId else s.watchers - deviceId
            s.copy(watchers = watchers)
        }
        reconcile(self, now, graceMs)
    }

    /** Somebody asked us to share for [ttlMs]. */
    fun onRequest(from: String, name: String, ttlMs: Long, now: Long, self: String, graceMs: Long = GRACE_MS) {
        requests[from] = name to (now + ttlMs)
        publishRequests()
        reconcile(self, now, graceMs)
    }

    /** The relay says the lease lapsed. Trust it and stop early. */
    fun onRequestExpired(from: String, self: String, now: Long, graceMs: Long = GRACE_MS) {
        requests.remove(from)
        publishRequests()
        reconcile(self, now, graceMs)
    }

    /**
     * Drop our own outstanding requests when the app goes quiet.
     *
     * If we stop watching we should not also be demanding other people's
     * positions; that is a surveillance posture, not a family-tracking one.
     */
    fun onLocalViewerStopped() {
        requests.clear()
        publishRequests()
        _state.update { it.copy(watchers = emptySet()) }
    }

    /** Call on a timer so a lapsed request ends even if the relay says nothing. */
    fun tick(self: String, now: Long, graceMs: Long = GRACE_MS) {
        var dropped = false
        val it = requests.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.second <= now) {
                it.remove()
                dropped = true
            }
        }
        if (dropped) publishRequests()
        reconcile(self, now, graceMs)
    }

    fun activeRequests(now: Long): Map<String, Pair<String, Long>> =
        requests.filterValues { it.second > now }

    // ---- decision --------------------------------------------------------

    private fun reconcile(self: String, now: Long, graceMs: Long) {
        val s = _state.value
        val liveRequests = requests.filterValues { it.second > now }
        val watching = s.watchers.isNotEmpty()
        val requested = liveRequests.isNotEmpty()

        val reason: Reason
        val until: Long
        if (requested) {
            reason = Reason.REQUESTED
            until = liveRequests.values.maxOf { it.second }
        } else if (watching) {
            reason = Reason.WATCHED
            // A watch is open-ended: keep streaming while it lasts.
            until = Long.MAX_VALUE
        } else if (s.shouldStream && s.streamUntil > now) {
            reason = Reason.COOLING_DOWN
            until = s.streamUntil
        } else {
            reason = Reason.IDLE
            until = 0L
        }

        _state.update {
            it.copy(
                reason = reason,
                requests = liveRequests,
                shouldStream = reason != Reason.IDLE,
                streamUntil = until,
            )
        }
    }

    private fun publishRequests() {
        _state.update { it.copy(requests = HashMap(requests)) }
    }

    companion object {
        /**
         * How long we keep streaming after the last watcher closes the map.
         *
         * Absorbs the ordinary churn of someone opening and closing the app, and
         * the brief drop a phone gets walking between buildings. Without it the
         * marker would visibly stutter, which reads as "broken" even though it
         * is working as designed.
         */
        const val GRACE_MS = 20_000L

        /** Default duration offered by "Get live location". */
        const val PULL_DEFAULT_MS = 15 * 60_000L

        const val PULL_MAX_MS = 24 * 60 * 60_000L
    }
}