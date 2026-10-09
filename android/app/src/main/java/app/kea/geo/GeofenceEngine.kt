package app.kea.geo

import app.kea.data.PendingFence
import app.kea.data.Zone

/**
 * Turns a stream of fixes into geofence transitions.
 *
 * Evaluated on the device that owns the location, against that device's own
 * zones. That placement is the whole trick for making alerts work under the
 * watch-gate: my arrival at Home is detected on my phone whether or not anyone
 * is looking, so the alert does not depend on somebody happening to have the
 * map open.
 *
 * Hysteresis matters more than it looks. Without it, GPS noise around a boundary
 * produces a burst of enter/exit pairs and the user's phone buzzes repeatedly for
 * one arrival. A zone has to be *clearly* outside before we call it an exit.
 */
class GeofenceEngine {

    data class Transition(
        val zone: Zone,
        val enter: Boolean,
        val lat: Double,
        val lon: Double,
        val at: Long,
    ) {
        fun toPending(name: String, deviceId: String) =
            PendingFence(zoneId = zone.id, deviceId = deviceId, memberName = name,
                enter = enter, lat = lat, lon = lon, at = at)
    }

    /**
     * @param inside current state per zone id
     * @param accuracyM horizontal accuracy of this fix, in metres
     * @param slackM extra margin required before declaring an *exit*. Entering
     *   is deliberately eager and leaving is deliberately reluctant.
     */
    fun evaluate(
        zones: List<Zone>,
        lat: Double,
        lon: Double,
        accuracyM: Double,
        inside: Map<String, Boolean>,
        now: Long,
        slackM: Double = EXIT_SLACK_M,
    ): Pair<Map<String, Boolean>, List<Transition>> {
        val newInside = HashMap(inside)
        val transitions = mutableListOf<Transition>()

        for (zone in zones) {
            val wasInside = inside[zone.id] == true

            val hit = if (zone.isPolygon) {
                Geo.inPolygon(lat, lon, zone.ringPairs())
            } else {
                // A wide accuracy radius should count as arrival, otherwise
                // indoor GPS would never register.
                Geo.distanceMeters(lat, lon, zone.lat, zone.lon) <=
                    maxOf(zone.radiusM.toDouble(), accuracyM)
            }

            // To leave, we must be clearly outside: radius plus the fix's own
            // error plus a margin. Otherwise a fix that merely wobbles across
            // the line would read as an exit.
            val clearlyOutside = if (zone.isPolygon) {
                !hit
            } else {
                Geo.distanceMeters(lat, lon, zone.lat, zone.lon) >
                    zone.radiusM + accuracyM + slackM
            }

            val nowInside = if (wasInside) !clearlyOutside else hit

            if (nowInside != wasInside) {
                newInside[zone.id] = nowInside
                transitions += Transition(zone, enter = nowInside, lat = lat, lon = lon, at = now)
            } else if (nowInside) {
                newInside[zone.id] = true
            }
        }

        return newInside to transitions
    }

    /**
     * Which notifications a transition should raise.
     *
     * Self and others are independent switches: somebody may want "when I get
     * home" without "when my partner gets home", and vice versa. Self is
     * evaluated locally and never needs the network.
     */
    fun shouldNotify(zone: Zone, enter: Boolean, isSelf: Boolean): Boolean = when {
        isSelf -> if (enter) zone.notifySelf else zone.notifySelf
        enter -> zone.notifyEnter && zone.notifyOthers
        else -> zone.notifyExit && zone.notifyOthers
    }

    companion object {
        /** Extra metres of certainty demanded before declaring an exit. */
        const val EXIT_SLACK_M = 25.0
    }
}