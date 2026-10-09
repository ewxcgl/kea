package app.kea.crypto

import java.security.MessageDigest
import java.util.Base64

/** Base64url without padding. Identical output on Android, iOS and Python. */
object B64 {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun enc(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun dec(text: String): ByteArray = decoder.decode(text)

    fun encOrNull(text: String): ByteArray? = try { dec(text) } catch (_: IllegalArgumentException) { null }

    fun decOrNull(text: String): ByteArray? = encOrNull(text)
}

/** Failures that mean "this data is not trustworthy", not "the app is broken". */
class KEAAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A payload that passed every check but arrived too late to be meaningful. */
class KEAReplayException(message: String) : Exception(message)

/** Structurally invalid — malformed header, wrong lengths. */
class KEAFrameException(message: String) : Exception(message)

/**
 * This device's long-lived Ed25519 identity.
 *
 * `deviceId` is derived from the public key, which gives three properties the
 * app needs: it is stable, it is short enough to paste into a chat message, and
 * anyone holding the roster can independently confirm that a payload claiming
 * to be from this device really is (PROTOCOL.md §2).
 */
class Identity private constructor(
    val seed: ByteArray,
    val publicKey: ByteArray,
    val deviceId: String,
) {
    fun sign(message: ByteArray): ByteArray = Primitives.sign(seed, message)

    /** Public-key half only, for handing to a verifier. */
    fun publicKeyOrNull(): ByteArray = publicKey.copyOf()

    companion object {
        const val DEVICE_ID_LENGTH = 11

        fun generate(): Identity {
            val seed = Primitives.randomBytes(Primitives.KEY_BYTES)
            return fromSeed(seed)
        }

        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == Primitives.KEY_BYTES) { "seed must be 32 bytes" }
            val pub = org.bouncycastle.crypto.params
                .Ed25519PrivateKeyParameters(seed, 0)
                .generatePublicKey()
                .encoded
            return Identity(seed.copyOf(), pub, deriveDeviceId(pub))
        }

        fun deriveDeviceId(publicKey: ByteArray): String =
            B64.enc(MessageDigest.getInstance("SHA-256").digest(publicKey))
                .take(DEVICE_ID_LENGTH)
    }
}