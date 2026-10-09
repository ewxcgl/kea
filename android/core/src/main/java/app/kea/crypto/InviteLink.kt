package app.kea.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Invite links — PROTOCOL.md §3.
 *
 * The layout is deliberately fixed-width and unversioned in its payload, because
 * the group key has to survive being pasted into whatever chat app the group
 * happens to use.
 *
 * The key lives in the URL **fragment**. Browsers, chat apps and the relay never
 * transmit a fragment to a server, so the key stays off the wire by construction
 * rather than by policy.
 */
object InviteLink {

    private const val SCHEME = "kea://join#"
    const val VERSION: Byte = 1
    const val BLOB_BYTES = 1 + Wire.GROUP_ID_BYTES + Wire.GROUP_KEY_BYTES + 8

    class Invite(
        val groupId: ByteArray,
        val groupKey: ByteArray,
        val expiresAtMs: Long,
    ) {
        /** A group with no expiry is the common case; 0 means "never". */
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs > 0 && nowMs > expiresAtMs
    }

    fun build(groupId: ByteArray, groupKey: ByteArray, expiresAtMs: Long = 0L): String {
        require(groupId.size == Wire.GROUP_ID_BYTES) { "group id must be ${Wire.GROUP_ID_BYTES}B" }
        require(groupKey.size == Wire.GROUP_KEY_BYTES) { "group key must be ${Wire.GROUP_KEY_BYTES}B" }
        val buf = ByteBuffer.allocate(BLOB_BYTES)
        buf.put(VERSION)
        buf.put(groupId)
        buf.put(groupKey)
        buf.putLong(expiresAtMs)
        return SCHEME + B64.enc(buf.array())
    }

    /** Tolerant of wrapping whitespace and a pasted prefix, strict about the rest. */
    fun parse(input: String): Invite {
        val trimmed = input.trim()
        val at = trimmed.indexOf("#kea://join#")
        val normalized = when {
            at >= 0 -> trimmed.substring(at + 1)
            trimmed.startsWith(SCHEME) -> trimmed
            trimmed.contains(SCHEME) -> trimmed.substring(trimmed.indexOf(SCHEME))
            else -> throw IllegalArgumentException("not a KEA invite link")
        }
        val blob = B64.dec(normalized.removePrefix(SCHEME))
        if (blob.size != BLOB_BYTES) {
            throw IllegalArgumentException("invite blob is ${blob.size}B, expected ${BLOB_BYTES}B")
        }
        val buf = ByteBuffer.wrap(blob)
        val version = buf.get()
        if (version != VERSION) throw IllegalArgumentException("unsupported invite version $version")
        val groupId = ByteArray(Wire.GROUP_ID_BYTES).also(buf::get)
        val groupKey = ByteArray(Wire.GROUP_KEY_BYTES).also(buf::get)
        return Invite(groupId, groupKey, buf.long)
    }

    fun newGroup(): Pair<ByteArray, ByteArray> =
        Primitives.randomBytes(Wire.GROUP_ID_BYTES) to Primitives.randomBytes(Wire.GROUP_KEY_BYTES)
}