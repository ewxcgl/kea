package app.kea.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives "share" / "no thanks" taps on the live-location request notification.
 *
 * A `BroadcastReceiver` rather than a service call, because the service may not
 * be running when someone dismisses the request, and starting a foreground
 * service purely to decline one would be absurd.
 *
 * The handler is registered by [ShareService] while it is alive; a tap after the
 * process dies finds nothing registered and is a silent no-op, which is the
 * correct outcome — we are not going to start sharing because of a stale tap.
 */
class PullResponseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GRANT -> PullResponseDispatcher.onGrant()
            ACTION_DENY -> PullResponseDispatcher.onDeny()
        }
    }

    companion object {
        const val ACTION_GRANT = "app.kea.PULL_grant"
        const val ACTION_DENY = "app.kea.PULL_deny"
    }
}

/**
 * The live callbacks behind [PullResponseReceiver].
 *
 * Kept separate so the receiver stays a thin, manifest-declared entry point
 * while the service owns the actual state change.
 */
object PullResponseDispatcher {
    @Volatile private var grant: (() -> Unit)? = null
    @Volatile private var deny: (() -> Unit)? = null

    fun install(onGrant: () -> Unit, onDeny: () -> Unit) {
        grant = onGrant
        deny = onDeny
    }

    fun clear() {
        grant = null
        deny = null
    }

    fun onGrant() {
        grant?.invoke()
    }

    fun onDeny() {
        deny?.invoke()
    }
}