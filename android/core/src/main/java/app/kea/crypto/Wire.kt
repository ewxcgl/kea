package app.kea.crypto

import java.security.MessageDigest

/**
 * Payload sealing and opening. This is the Android half of PROTOCOL.md §4–§5
 * and must agree byte for byte with relay/keacrypto.py and the Swift client.
 *
 * Wire layout (little details that matter):
 *
 *     byte  0      flags, bit0 = sealed
 *     byte  1..2   u16 body length
 *     body:
 *         deviceId     11 ascii
 *         sessionId     8
 *         seq           4  u32
 *         tsMs          8  i64
 *         typeTag       4  ascii, space padded
 *         nonce        12
 *         sealed       var  ciphertext || Poly1305 tag
 *         signature    64
 */
object Wire {

    const val GROUP_ID_BYTES = 16
    const val GROUP_KEY_BYTES = 32
    const val SESSION_ID_BYTES = 8
    const val MAX_FRAME_BYTES = 16_384
    const val MAX_SKEW_MS = 600_000L

    private const val PAYLOAD_INFO = "kea-payload-v1"
    private const val NONCE_INFO = "kea-nonce-v1"

    private const val OFF_FLAGS = 0
    private const val OFF_LEN = 1
    private const val HEADER_BYTES = 3

    /** Where the routing block starts. Tests tamper here. */
    const val HEADER_OFFSET = HEADER_BYTES

    /** 11 deviceId + 8 sessionId + 4 seq + 8 ts + 4 typeTag */
    const val ROUTING_OFFSET = HEADER_BYTES
    const val ROUTING_LENGTH = 11 + 8 + 4 + 8 + 4

    /** First byte of the nonce inside the body. */
    const val NONCE_OFFSET = HEADER_BYTES + ROUTING_LENGTH

    private const val MIN_BODY = ROUTING_LENGTH + 12 + 16 + 64

    private val PAYLOAD_INFO_BYTES = PAYLOAD_INFO.toByteArray(Charsets.US_ASCII)
    private val NONCE_INFO_BYTES = NONCE_INFO.toByteArray(Charsets.US_ASCII)

    // ---------------------------------------------------------------- seal

    /**
     * @param seq strictly increasing within a session; the caller owns it.
     * @param payloadMap must contain "type"; "seq" and "ts" are set here.
     */
    fun seal(
        groupId: ByteArray,
        groupKey: ByteArray,
        identity: Identity,
        sessionId: ByteArray,
        seq: Long,
        payloadMap: Map<String, Any?>,
        tsMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        requireDimensions(groupId, groupKey, sessionId)

        val type = payloadMap["type"] as? String
            ?: throw IllegalArgumentException("payload must carry a \"type\"")
        val tag = typeTag(type)
        val deviceId = identity.deviceId
        val seqInt = seq.toInt()

        // The authenticated header wins over anything in the body.
        val body = LinkedHashMap(payloadMap).apply {
            put("seq", seqInt)
            put("ts", tsMs)
        }
        val plaintext = CanonicalJson.encode(body).toByteArray(Charsets.UTF_8)

        val aad = buildAad(groupId, deviceId, sessionId, seqInt, tsMs, tag)
        val nonce = buildNonce(groupKey, groupId, deviceId, sessionId, seqInt)
        val sealed = Primitives.seal(buildKey(groupKey, groupId), nonce, plaintext, aad)
        val signature = identity.sign(signable(aad, sealed))

        // Enforce the cap *before* assembling. The length prefix is 16-bit, so
        // an oversize payload would otherwise wrap it and desync every parser
        // downstream — including the relay.
        val bodyBytes = ROUTING_LENGTH + 12 + sealed.size + 64
        if (bodyBytes > MAX_FRAME_BYTES) {
            throw IllegalArgumentException(
                "frame $bodyBytes B exceeds the $MAX_FRAME_BYTES B cap"
            )
        }

        val out = ByteArray(HEADER_BYTES + bodyBytes)
        var p = 0
        out[p++] = 0x01
        out[p++] = (bodyBytes ushr 8).toByte()
        out[p++] = (bodyBytes and 0xFF).toByte()
        deviceId.toByteArray(Charsets.US_ASCII).copyInto(out, p); p += 11
        sessionId.copyInto(out, p); p += 8
        putInt(out, p, seqInt); p += 4
        putLong(out, p, tsMs); p += 8
        tag.copyInto(out, p); p += 4
        nonce.copyInto(out, p); p += 12
        sealed.copyInto(out, p); p += sealed.size
        signature.copyInto(out, p); p += 64

        check(p == out.size) { "frame assembly desync: $p of ${out.size}" }
        return out
    }

    // ---------------------------------------------------------------- open

    /** Structural parse. No cryptography, no trust. */
    class Frame(
        val deviceId: String,
        val sessionId: ByteArray,
        val seq: Int,
        val tsMs: Long,
        val tag: ByteArray,
        val nonce: ByteArray,
        val sealed: ByteArray,
        val signature: ByteArray,
    )

    fun parse(frame: ByteArray): Frame {
        if (frame.size < HEADER_BYTES) throw KEAFrameException("frame shorter than header")
        if (frame[OFF_FLAGS].toInt() and 0x01 == 0) throw KEAFrameException("not a sealed payload")
        val declared = ((frame[OFF_LEN].toInt() and 0xFF) shl 8) or (frame[OFF_LEN + 1].toInt() and 0xFF)
        val body = frame.copyOfRange(HEADER_BYTES, frame.size)
        if (body.size != declared) {
            throw KEAFrameException("length prefix $declared but body ${body.size}")
        }
        if (body.size < MIN_BODY) throw KEAFrameException("body ${body.size} below minimum $MIN_BODY")

        var p = 0
        val deviceId = String(body, p, 11, Charsets.US_ASCII); p += 11
        val sessionId = body.copyOfRange(p, p + 8); p += 8
        val seq = getInt(body, p); p += 4
        val tsMs = getLong(body, p); p += 8
        val tag = body.copyOfRange(p, p + 4); p += 4
        val nonce = body.copyOfRange(p, p + 12); p += 12
        val signature = body.copyOfRange(body.size - 64, body.size)
        val sealed = body.copyOfRange(p, body.size - 64)

        return Frame(deviceId, sessionId, seq, tsMs, tag, nonce, sealed, signature)
    }

    /**
     * Verify then decrypt.
     *
     * @param signPub the sender's Ed25519 key **from the roster**. A frame is
     *   never trusted on its own say-so; that is what stops one member from
     *   forging another's position.
     */
    fun open(
        frame: ByteArray,
        groupId: ByteArray,
        groupKey: ByteArray,
        signPub: ByteArray,
        state: ReceiverState,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Any?> {
        requireDimensions(groupId, groupKey, ByteArray(8))
        val f = parse(frame)

        if (Identity.deriveDeviceId(signPub) != f.deviceId) {
            throw KEAAuthException("device id does not match the supplied signing key")
        }

        if (kotlin.math.abs(nowMs - f.tsMs) > MAX_SKEW_MS) {
            throw KEAReplayException("payload ${nowMs - f.tsMs}ms outside the freshness window")
        }

        state.check(f.deviceId, f.sessionId, f.seq)

        val aad = buildAad(groupId, f.deviceId, f.sessionId, f.seq, f.tsMs, f.tag)
        if (!Primitives.verify(signPub, signable(aad, f.sealed), f.signature)) {
            throw KEAAuthException("signature did not verify")
        }

        // The nonce is derived, not chosen. Reject a swapped one before spending
        // a decryption on it.
        val expected = buildNonce(groupKey, groupId, f.deviceId, f.sessionId, f.seq)
        if (!MessageDigest.isEqual(f.nonce, expected)) {
            throw KEAAuthException("nonce does not match (deviceId, sessionId, seq)")
        }

        val plaintext = Primitives.open(buildKey(groupKey, groupId), f.nonce, f.sealed, aad)
        val decoded = CanonicalJson.decode(String(plaintext, Charsets.UTF_8))

        val type = decoded["type"] as? String
        if (type == null || !MessageDigest.isEqual(typeTag(type), f.tag)) {
            throw KEAAuthException("type tag does not match the sealed payload type")
        }
        return decoded
    }

    // -------------------------------------------------------------- helpers

    private fun requireDimensions(groupId: ByteArray, groupKey: ByteArray, sessionId: ByteArray) {
        require(groupId.size == GROUP_ID_BYTES) { "group id must be $GROUP_ID_BYTES bytes" }
        require(groupKey.size == GROUP_KEY_BYTES) { "group key must be $GROUP_KEY_BYTES bytes" }
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
    }

    fun typeTag(type: String): ByteArray {
        val ascii = type.toByteArray(Charsets.US_ASCII).copyOf(4)
        for (i in ascii.indices) if (ascii[i] == 0.toByte()) ascii[i] = ' '.code.toByte()
        return ascii
    }

    internal fun buildAad(
        groupId: ByteArray, deviceId: String, sessionId: ByteArray,
        seq: Int, tsMs: Long, tag: ByteArray,
    ): ByteArray {
        val out = ByteArray(PAYLOAD_INFO_BYTES.size + 1 + groupId.size + 11 +
            sessionId.size + 4 + 8 + tag.size)
        var p = 0
        PAYLOAD_INFO_BYTES.copyInto(out, p); p += PAYLOAD_INFO_BYTES.size
        out[p++] = 0
        groupId.copyInto(out, p); p += groupId.size
        deviceId.toByteArray(Charsets.US_ASCII).copyInto(out, p); p += 11
        sessionId.copyInto(out, p); p += sessionId.size
        putInt(out, p, seq); p += 4
        putLong(out, p, tsMs); p += 8
        tag.copyInto(out, p)
        return out
    }

    internal fun buildNonce(
        groupKey: ByteArray, groupId: ByteArray, deviceId: String,
        sessionId: ByteArray, seq: Int,
    ): ByteArray {
        val info = ByteArray(NONCE_INFO_BYTES.size + 11 + sessionId.size + 4)
        var p = 0
        NONCE_INFO_BYTES.copyInto(info, p); p += NONCE_INFO_BYTES.size
        deviceId.toByteArray(Charsets.US_ASCII).copyInto(info, p); p += 11
        sessionId.copyInto(info, p); p += sessionId.size
        putInt(info, p, seq)
        return Primitives.hkdf(groupKey, groupId, info, 12)
    }

    internal fun buildKey(groupKey: ByteArray, groupId: ByteArray): ByteArray =
        Primitives.hkdf(groupKey, groupId, PAYLOAD_INFO_BYTES, 32)

    internal fun signable(aad: ByteArray, sealed: ByteArray): ByteArray {
        val out = ByteArray(1 + aad.size + sealed.size)
        out[0] = 0
        aad.copyInto(out, 1)
        sealed.copyInto(out, 1 + aad.size)
        return out
    }

    private fun putInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    private fun getInt(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)

    private fun putLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun getLong(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }
}

/**
 * Per-group replay bookkeeping, one instance per group per device.
 *
 * Not thread-safe by design: callers serialise payload handling onto a single
 * coroutine, which is exactly what PROTOCOL.md §12 asks for and removes a whole
 * class of locking bugs.
 */
class ReceiverState {
    private val seen = HashMap<String, Int>()

    fun key(deviceId: String, sessionId: ByteArray): String =
        deviceId + ":" + sessionId.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun check(deviceId: String, sessionId: ByteArray, seq: Int) {
        val k = key(deviceId, sessionId)
        val last = seen[k] ?: -1
        if (seq <= last) throw KEAReplayException("replayed seq $seq <= $last")
        seen[k] = seq
    }

    /** Drops history for a session that a peer has clearly restarted. */
    @Synchronized
    fun forget(deviceId: String) {
        seen.keys.removeAll { it.startsWith("$deviceId:") }
    }
}