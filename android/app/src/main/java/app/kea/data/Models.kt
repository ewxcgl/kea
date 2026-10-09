package app.kea.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-device state.
 *
 * Persisted as one JSON blob in DataStore rather than a relational database.
 * That is a deliberate trade: the working set is one group, a dozen zones and a
 * handful of last-known positions, so a query engine would be machinery with
 * nothing to do. It also keeps the APK smaller and the build simpler, and it
 * makes "leave the group" a single key delete rather than a cascade.
 *
 * Nothing here is ever backed up or transmitted except through the sealed
 * payloads, and the group key lives in the private store.
 */

@Serializable
data class Zone(
    val id: String,
    val name: String,
    /** "circle" or "polygon". */
    val shape: String = SHAPE_CIRCLE,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Int = 100,
    /** Flat [lat, lon] pairs, only meaningful when shape == polygon. */
    val ring: List<List<Double>> = emptyList(),
    val notifyEnter: Boolean = true,
    val notifyExit: Boolean = true,
    /** Independent of the others: "tell me when *I* cross my own line". */
    val notifySelf: Boolean = true,
    val notifyOthers: Boolean = true,
    /** Index into the member colour palette, so both clients agree. */
    val colorIndex: Int = 0,
) {
    val isPolygon: Boolean get() = shape == SHAPE_POLYGON

    fun ringPairs(): List<Pair<Double, Double>> =
        ring.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }

    companion object {
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_POLYGON = "polygon"
    }
}

@Serializable
data class Member(
    val deviceId: String,
    val name: String,
    /** base64url Ed25519 public key, as advertised in the roster. */
    val publicKey: String,
    val online: Boolean = false,
    val watching: Boolean = false,
    /** Relay's last-contact timestamp, ms. */
    val seenAt: Long = 0L,
    val isSelf: Boolean = false,
    // Last sealed LOC. Kept so the map has something to draw after a restart.
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val battery: Float? = null,
    val source: String? = null,
    val fixAt: Long = 0L,
    /** Count of frames rejected by verification — surfaced in the member sheet. */
    val rejected: Int = 0,
)

@Serializable
data class Group(
    val groupId: String,
    /** base64url. Never leaves the device outside a sealed payload. */
    val groupKey: String,
    val name: String,
    val createdAt: Long,
    val inviteLink: String,
    /** Bumped on key rotation so queued events from the old key are dropped. */
    val keyGeneration: Int = 1,
)

/** A geofence transition that happened while nobody was watching. */
@Serializable
data class PendingFence(
    val zoneId: String,
    val deviceId: String,
    val memberName: String,
    val enter: Boolean,
    val lat: Double,
    val lon: Double,
    val at: Long,
)

@Serializable
data class Session(
    val group: Group? = null,
    val zones: List<Zone> = emptyList(),
    val members: List<Member> = emptyList(),
    val pendingFences: List<PendingFence> = emptyList(),
    /** zoneId -> whether we are currently inside it. Drives enter/exit edges. */
    val inside: Map<String, Boolean> = emptyMap(),
) {
    val hasGroup: Boolean get() = group != null
}

/** Scalar preferences that do not belong in the JSON blob. */
@Serializable
data class Settings(
    val displayName: String = "",
    val relayUrl: String = "",
    /** "" = follow system, otherwise "en" or "de". */
    val languageTag: String = "",
    /** Best-effort OS language, captured once for the "follow system" case. */
    val lastSeenLanguage: String = "",
    /** Whether the watch-gate has been explained and accepted. */
    val onboardingDone: Boolean = false,
)

/**
 * Palette shared with iOS so a member is the same colour on both devices.
 *
 * Ordered by hue spread rather than brightness so adjacent entries stay
 * distinguishable for the most common colour-vision deficiencies.
 */
object Palette {
    @SerialName("unused")
    private const val UNUSED = ""

    val COLOURS = listOf(
        "#5EEAD4", // teal
        "#FCA5A5", // salmon
        "#A5B4FC", // periwinkle
        "#FCD34D", // amber
        "#86EFAC", // mint
        "#F9A8D4", // pink
        "#93C5FD", // sky
        "#FDBA74", // apricot
    )

    fun colour(index: Int): String = COLOURS[((index % COLOURS.size) + COLOURS.size) % COLOURS.size]
}

/**
 * A member's colour, derived from their device id.
 *
 * Derived rather than assigned so both clients pick the same colour for the
 * same person without exchanging anything, and so the assignment survives a
 * reinstall. [Int.MIN_VALUE] has no positive counterpart, so it is folded to 0.
 */
fun Member.colour(): String =
    Palette.colour(if (deviceId.hashCode() == Int.MIN_VALUE) 0 else kotlin.math.abs(deviceId.hashCode()))

/** Parse a `#RRGGBB` palette entry to an Android colour int. */
fun String.toColourInt(): Int =
    android.graphics.Color.parseColor(if (startsWith("#")) this else "#$this")