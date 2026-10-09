package app.kea.service

import app.kea.data.Member
import android.location.Location

/** Helpers for keeping the member list current without losing last-known fixes. */

/** Insert or replace by deviceId, preserving everything not being updated. */
fun List<Member>.replace(member: Member): List<Member> {
    val index = indexOfFirst { it.deviceId == member.deviceId }
    return if (index < 0) this + member else toMutableList().also { it[index] = member }
}

/**
 * Write our own fix back into the roster.
 *
 * Roster refreshes replace member metadata wholesale, so without this our own
 * marker would vanish every time somebody else's roster arrived — the map would
 * flicker with our own icon disappearing.
 */
fun List<Member>.upsertSelf(
    deviceId: String,
    name: String,
    publicKey: String,
    location: Location,
    battery: Float?,
    online: Boolean,
): List<Member> {
    val existing = firstOrNull { it.deviceId == deviceId }
    val updated = Member(
        deviceId = deviceId,
        name = name.ifBlank { existing?.name.orEmpty() },
        publicKey = publicKey,
        online = online,
        watching = existing?.watching ?: false,
        seenAt = existing?.seenAt ?: System.currentTimeMillis(),
        isSelf = true,
        lat = location.latitude,
        lon = location.longitude,
        accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        altitude = if (location.hasAltitude()) location.altitude else null,
        speed = if (location.hasSpeed()) location.speed.toDouble() else null,
        bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
        battery = battery ?: existing?.battery,
        source = location.provider,
        fixAt = System.currentTimeMillis(),
        rejected = existing?.rejected ?: 0,
    )
    return replace(updated)
}