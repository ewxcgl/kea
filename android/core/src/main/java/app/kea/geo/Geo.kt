package app.kea.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesy for geofences, implemented identically on both platforms.
 *
 * Deliberately hand-written rather than pulled from a mapping library: the
 * result is a wire-visible behaviour ("did this member just cross the line?"),
 * so it has to match the Swift implementation byte for byte in its edge cases,
 * and it has to work with nothing but a latitude and a longitude.
 *
 * WGS84 is assumed throughout, which is what every phone GPS produces.
 */
object Geo {

    /** Mean Earth radius, metres. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * Great-circle distance in metres.
     *
     * Haversine rather than the law of cosines: it stays numerically stable for
     * the very small distances that matter here, where two people standing in
     * the same room produce a near-zero result that a law-of-cosines
     * implementation would round to garbage.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Even-odd ray casting.
     *
     * Winding order is irrelevant, which matches how a person drags a polygon on
     * a map. A point exactly on an edge is implementation-defined; we count it
     * as inside, so nudging a boundary does not make a fence flicker.
     */
    fun inPolygon(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (yi, xi) = ring[i]
            val (yj, xj) = ring[j]
            if (onSegment(lat, lon, yi, xi, yj, xj)) return true
            val straddles = (yi > lat) != (yj > lat)
            if (straddles) {
                val xCross = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun onSegment(
        py: Double, px: Double,
        y1: Double, x1: Double,
        y2: Double, x2: Double,
    ): Boolean {
        val cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
        if (abs(cross) > EPSILON) return false
        val withinX = px >= min(x1, x2) - EPSILON && px <= max(x1, x2) + EPSILON
        val withinY = py >= min(y1, y2) - EPSILON && py <= max(y1, y2) + EPSILON
        return withinX && withinY
    }

    /** Initial bearing from one point to another, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Accuracy circles overlap test.
     *
     * A fence should fire when the user *probably* entered it, not only when
     * the reported point is inside. Two circles intersect when the centre
     * distance is less than the sum of their radii.
     */
    fun circlesOverlap(
        lat1: Double, lon1: Double, r1: Double,
        lat2: Double, lon2: Double, r2: Double,
    ): Boolean = distanceMeters(lat1, lon1, lat2, lon2) < (r1 + r2)

    private const val EPSILON = 1e-9
}