package app.kea.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kea.MainActivity
import app.kea.R

/**
 * Notification plumbing.
 *
 * Every user-visible string here comes from resources, which is what makes the
 * German translation apply to zone alerts as well as to the interface. There is
 * no concatenated English anywhere in this file on purpose — a sentence built
 * from fragments cannot be translated, and word order differs between the two
 * languages we ship.
 */
object Notifications {

    const val CHANNEL_FENCE = "fence"
    const val CHANNEL_SHARING = "sharing"
    const val CHANNEL_INVITE = "invite"

    /** The persistent foreground notification. Must use a stable id. */
    const val ID_FOREGROUND = 1

    private fun channelIdFor(channel: String) = "$channel-$NOTIFICATION_ID_BASE"

    private const val NOTIFICATION_ID_BASE = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val fence = NotificationChannel(
            channelIdFor(CHANNEL_FENCE),
            context.getString(R.string.notif_channel_geofence),
            // Default importance: a zone alert should be seen, but not make a
            // sound at 3am for someone else arriving home.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_geofence_desc) }

        val sharing = NotificationChannel(
            channelIdFor(CHANNEL_SHARING),
            context.getString(R.string.notif_channel_sharing),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = context.getString(R.string.notif_channel_sharing_desc) }

        val invite = NotificationChannel(
            channelIdFor(CHANNEL_INVITE),
            context.getString(R.string.notif_channel_invite),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notif_channel_invite_desc) }

        manager.createNotificationChannels(listOf(fence, sharing, invite))
    }

    // ---- foreground ------------------------------------------------------

    /**
     * @param watcherSummary already-resolved, localized text such as
     *   "Being watched by Ana" or "Being watched by 3 people".
     */
    fun foreground(state: ForegroundState, watcherSummary: String): Notification {
        val ctx = state.context
        val (title, body) = when (state.phase) {
            ForegroundPhase.LIVE ->
                ctx.getString(R.string.notif_service_live_title) to watcherSummary
            ForegroundPhase.COOLING_DOWN ->
                ctx.getString(R.string.notif_service_grace_title) to
                    ctx.getString(R.string.notif_service_grace_body)
            ForegroundPhase.IDLE ->
                ctx.getString(R.string.notif_service_idle_title) to
                    ctx.getString(R.string.notif_service_idle_body)
        }

        return NotificationCompat.Builder(ctx, channelIdFor(CHANNEL_SHARING))
            .setSmallIcon(R.drawable.ic_stat_kea)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    enum class ForegroundPhase { IDLE, COOLING_DOWN, LIVE }

    class ForegroundState(val context: Context, val phase: ForegroundPhase)

    // ---- geofence --------------------------------------------------------

    fun showFence(context: Context, id: Int, title: String, body: String) {
        val n = NotificationCompat.Builder(context, channelIdFor(CHANNEL_FENCE))
            .setSmallIcon(R.drawable.ic_stat_kea)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        post(context, NOTIFICATION_ID_BASE + id, n)
    }

    /**
     * Someone asked for our live position.
     *
     * Deliberately interactive: silently starting to share because a message
     * arrived would be exactly the behaviour this app promises not to have.
     */
    fun showPullRequest(
        context: Context,
        id: Int,
        requesterName: String,
        ttlMs: Long,
        onGrant: () -> Unit,
        onDeny: () -> Unit,
    ) {
        val minutes = (ttlMs / 60_000).coerceAtLeast(1)
        val n = NotificationCompat.Builder(context, channelIdFor(CHANNEL_INVITE))
            .setSmallIcon(R.drawable.ic_stat_kea)
            .setContentTitle(context.getString(R.string.notif_pull_title, requesterName))
            .setContentText(context.getString(R.string.notif_pull_body, durationText(context, minutes)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                0,
                context.getString(R.string.notif_pull_action_grant),
                actionIntent(context, "grant", id, onGrant),
            )
            .addAction(
                0,
                context.getString(R.string.notif_pull_action_deny),
                actionIntent(context, "deny", id, onDeny),
            )
            .build()
        post(context, NOTIFICATION_ID_BASE + id, n)
    }

    // ---- helpers ---------------------------------------------------------

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(
        context: Context, action: String, id: Int, handler: () -> Unit,
    ): PendingIntent {
        // Grant/deny must survive the service being alive but the UI gone, so
        // the handler is invoked through a broadcast that the service registers.
        return PendingIntent.getBroadcast(
            context,
            id * 31 + action.hashCode(),
            Intent(context, PullResponseReceiver::class.java).setAction(
                "app.kea.PULL_$action",
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).also { pending ->
            PendingIntents.register(pending.hashCode(), handler)
        }
    }

    /** "15 min", "2 h", "1 min" — localized through the quantity-less plurals. */
    private fun durationText(context: Context, minutes: Long): String = when {
        minutes < 60 -> context.getString(R.string.duration_minutes, minutes.toInt())
        minutes % 60 == 0L -> context.getString(R.string.duration_hours, (minutes / 60).toInt())
        else -> context.getString(
            R.string.duration_hours_minutes,
            (minutes / 60).toInt(), (minutes % 60).toInt(),
        )
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Nothing to do: without permission this is a silent no-op, and
            // asking from a background service is not allowed anyway.
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

/**
 * Bridges a notification action back to the running service.
 *
 * A PendingIntent cannot carry a lambda, and starting the service just to say
 * "no thanks" would be absurd. Handlers are registered by the service on start
 * and dropped when it dies, so a stale tap is a no-op rather than a crash.
 */
object PendingIntents {
    private val handlers = HashMap<Int, () -> Unit>()

    fun register(key: Int, handler: () -> Unit) {
        synchronized(handlers) { handlers[key] = handler }
    }

    fun unregisterAll() {
        synchronized(handlers) { handlers.clear() }
    }

    fun fire(key: Int) {
        val h = synchronized(handlers) { handlers.remove(key) } ?: return
        h()
    }
}