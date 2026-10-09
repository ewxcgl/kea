package app.kea.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cleartext control frames — PROTOCOL.md §6.
 *
 * These are the *only* things the relay can read, and that is intentional. The
 * `watching` flag must be visible to peers or the watch-gate cannot work, and
 * roster membership is not sensitive in the way a coordinate is. Everything that
 * carries geography travels as a sealed binary frame instead.
 *
 * `on`, `by` and `from` collide with Kotlin keywords, hence the @SerialName.
 */
@Serializable
data class Hello(
    val t: String = "hello",
    val v: Int = PROTOCOL_VERSION,
    val group: String,
    val dev: String,
    val name: String,
    val pk: String,
)

@Serializable
data class HelloOk(
    val t: String = "hello_ok",
    val heartbeat: Int,
    @SerialName("max_frame") val maxFrame: Int,
)

@Serializable
data class Watch(
    val t: String = "watch",
    val dev: String,
    val on: Boolean,
)

@Serializable
data class RosterEntry(
    val dev: String,
    val name: String,
    val pk: String,
    val on: Boolean,
    val watching: Boolean,
    val seen: Long,
)

@Serializable
data class Roster(
    val t: String = "roster",
    val members: List<RosterEntry>,
)

/** "Acquire live location": ask one member to stream for a bounded window. */
@Serializable
data class Pull(
    val t: String = "pull",
    val dev: String,
    val ttl: Int,
)

@Serializable
data class PullRequest(
    val t: String = "pullreq",
    val from: String,
    val name: String,
    val ttl: Int,
)

@Serializable
data class Pulled(
    val t: String = "pulled",
    val dev: String,
    @SerialName("by") val by: String,
    val ttl: Int,
)

@Serializable
data class PullExpired(
    val t: String = "pullexpired",
    val dev: String,
)

@Serializable
data class Leave(
    val t: String = "leave",
    val group: String,
    val dev: String,
)

@Serializable
data class Ping(val t: String = "ping", val ts: Long)

@Serializable
data class Pong(val t: String = "pong", val ts: Long)

const val PROTOCOL_VERSION = 1

/**
 * Lenient by design: a relay or a future client version may add fields, and an
 * unknown one must never break the watch-gate. `t` is always required, since it
 * is the only thing we dispatch on.
 */
val ControlJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
    explicitNulls = false
}

/** Every inbound control message, so the socket layer can dispatch in one place. */
sealed interface Control {
    data class Joined(val ok: HelloOk) : Control
    data class WatchChanged(val watch: Watch) : Control
    data class RosterChanged(val roster: Roster) : Control
    data class PullRequested(val request: PullRequest) : Control
    data class PullAnnounced(val pulled: Pulled) : Control
    data class PullEnded(val expired: PullExpired) : Control
}

/**
 * Decode an inbound control frame by its `t` discriminator.
 *
 * Anything unrecognised returns null rather than throwing: a newer relay adding
 * a field, or a future message type, must never be able to crash the socket
 * loop or knock the watch-gate offline.
 */
fun parseControl(text: String): Control? = try {
    val obj = ControlJson.parseToJsonElement(text).jsonObject
    when (obj["t"]?.jsonPrimitive?.content) {
        "hello_ok" -> Control.Joined(ControlJson.decodeFromJsonElement(HelloOk.serializer(), obj))
        "roster" -> Control.RosterChanged(ControlJson.decodeFromJsonElement(Roster.serializer(), obj))
        "watch" -> Control.WatchChanged(ControlJson.decodeFromJsonElement(Watch.serializer(), obj))
        "pullreq" -> Control.PullRequested(ControlJson.decodeFromJsonElement(PullRequest.serializer(), obj))
        "pulled" -> Control.PullAnnounced(ControlJson.decodeFromJsonElement(Pulled.serializer(), obj))
        "pullexpired" -> Control.PullEnded(ControlJson.decodeFromJsonElement(PullExpired.serializer(), obj))
        else -> null
    }
} catch (_: Exception) {
    null
}