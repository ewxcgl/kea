package app.kea.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance against relay/keacrypto.py.
 *
 * If this fails, the Android client cannot talk to the relay or to iOS, so it is
 * the first thing to run — before any UI is trusted.
 */
class WireVectorTest {

    private val groupId = ByteArray(16) { it.toByte() }
    private val groupKey = ByteArray(32) { it.toByte() }
    private val sessionId = ByteArray(8) { it.toByte() }
    private val ts = 1_700_000_000_000L

    private val identity = Identity.fromSeed(ByteArray(32) { it.toByte() })

    /** Loc payload mirroring keacrypto.VECTOR_LOC. */
    private val loc = linkedMapOf<String, Any?>(
        "type" to "LOC",
        "lat" to 52.520008,
        "lon" to 13.404954,
        "acc" to 12.5,
        "alt" to 34.0,
        "spd" to 1.4,
        "brg" to 87.0,
        "bat" to 0.63,
        "src" to "gps",
    )

    private fun vectorFrameHex(): String =
        "01010a566b64617031526a5230770001020304050607000000000000018bcfe568004c" +
        "4f432017d630a98e1ca2b335501fc6d19985f5e12e9fd1eb9094bb693b2b2eea88b545" +
        "e78dfa58dc9757b9ae50521e11f3754406389a56f5ab7456066b88e052ec8fdff2d2ec" +
        "79e0e001eedd47f6a871177a2c8ee9c7498c047b77dfe579a9416af34c1f1ff5376976" +
        "8d9e79e8e7aae258cede8f4753136fbb30e043056e66187b592185916c5361f569b7cb" +
        "dc0bfc95f49c1fc1d84f809c151785eb11273cafcd3d38832413c143dbe4757525affc" +
        "b0d72413f3aa805c4f7672bab4947935002788046e63dbbaf39fcaba1b14ac3bc73767" +
        "b92343fce641cbf5d3ab07ac599deea6c44b430de402560e"

    // ------------------------------------------------------------ identities

    @Test
    fun deviceIdMatchesPython() {
        assertEquals("Vkdap1RjR0w", identity.deviceId)
        assertEquals(11, identity.deviceId.length)
    }

    @Test
    fun publicKeyMatchesPython() {
        assertEquals(
            "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg",
            B64.enc(identity.publicKey),
        )
    }

    // ----------------------------------------------------------------- frame

    @Test
    fun sealedFrameIsByteIdenticalToPython() {
        val frame = Wire.seal(
            groupId = groupId, groupKey = groupKey, identity = identity,
            sessionId = sessionId, seq = 0, payloadMap = loc, tsMs = ts,
        )
        assertEquals(vectorFrameHex(), hex(frame))
        assertEquals(269, frame.size)
    }

    @Test
    fun vectorFrameOpensToTheSamePayload() {
        val frame = Wire.seal(
            groupId = groupId, groupKey = groupKey, identity = identity,
            sessionId = sessionId, seq = 0, payloadMap = loc, tsMs = ts,
        )
        val out = Wire.open(
            frame = frame, groupId = groupId, groupKey = groupKey,
            signPub = identity.publicKey, state = ReceiverState(), nowMs = ts,
        )
        assertEquals("LOC", out["type"])
        assertEquals(52.520008, out["lat"])
        assertEquals(13.404954, out["lon"])
        assertEquals(0.63, out["bat"])
        assertEquals("gps", out["src"])
        assertEquals(0, out["seq"])
        assertEquals(ts, out["ts"])
    }

    // --------------------------------------------------------------- attacks

    @Test
    fun replayedFrameRejected() {
        val frame = seal(0)
        val state = ReceiverState()
        open(frame, state = state)
        assertFails { open(frame, state = state) }
    }

    @Test
    fun outOfOrderSeqRejected() {
        val state = ReceiverState()
        open(seal(9), state = state)
        assertFails { open(seal(8), state = state) }
    }

    @Test
    fun freshSessionResetsTheWindow() {
        val state = ReceiverState()
        open(seal(99, ByteArray(8) { 1 }), state = state)
        val out = open(seal(0, ByteArray(8) { 2 }), state = state)
        assertEquals(0, out["seq"])
    }

    @Test
    fun wrongGroupKeyRejected() {
        assertFails { open(seal(0), groupKey = ByteArray(32)) }
    }

    @Test
    fun wrongGroupIdRejected() {
        assertFails { open(seal(0), groupId = ByteArray(16)) }
    }

    @Test
    fun impostorKeyRejected() {
        // Someone else's deviceId with our own key material.
        val impostor = Identity.fromSeed(ByteArray(32) { 7 })
        assertFails { open(seal(0), signPub = impostor.publicKey) }
    }

    @Test
    fun forgedPositionByAnotherMemberRejected() {
        val victim = Identity.fromSeed(ByteArray(32) { 9 })
        val attacker = Identity.fromSeed(ByteArray(32) { 10 })

        val forged = seal(0, identity = attacker).copyOf()
        val frame = Wire.parse(forged)
        // Rewrite the cleartext header to impersonate the victim...
        System.arraycopy(
            victim.deviceId.toByteArray(), 0, forged, Wire.HEADER_OFFSET + 0, 11,
        )
        assertEquals(victim.deviceId, Wire.parse(forged).deviceId)
        // ...and it still fails, because the signature covers the AAD.
        assertFails {
            open(forged, signPub = victim.publicKey, groupId = groupId, groupKey = groupKey)
        }
        assertEquals(frame.deviceId, attacker.deviceId)
    }

    @Test
    fun ciphertextTamperRejected() {
        val frame = seal(0).copyOf()
        frame[80] = (frame[80].toInt() xor 1).toByte()
        assertFails { open(frame) }
    }

    @Test
    fun routingTamperRejected() {
        val frame = seal(0).copyOf()
        frame[13] = (frame[13].toInt() xor 0xFF).toByte()   // sessionId[0]
        assertFails { open(frame) }
    }

    @Test
    fun timestampTamperRejected() {
        val frame = seal(0).copyOf()
        // ts sits after deviceId(11) + sessionId(8) + seq(4).
        val at = Wire.HEADER_OFFSET + 11 + 8 + 4
        putLong(frame, at, ts - 120_000)
        assertFails { open(frame, nowMs = ts) }
    }

    @Test
    fun typeConfusionRejected() {
        // A signed LOC relabelled FENC must not slip past the AAD binding.
        val frame = seal(0).copyOf()
        val tagAt = Wire.HEADER_OFFSET + 11 + 8 + 4 + 8
        "FENC".toByteArray().copyInto(frame, tagAt)
        assertFails { open(frame) }
    }

    @Test
    fun nonceSwapRejected() {
        val frame = seal(0).copyOf()
        val nonceAt = Wire.HEADER_OFFSET + 11 + 8 + 4 + 8 + 4
        frame[nonceAt] = (frame[nonceAt].toInt() xor 1).toByte()
        assertFails { open(frame) }
    }

    @Test
    fun stalePayloadRejected() {
        val old = ts - (Wire.MAX_SKEW_MS + 60_000)
        assertFails { open(seal(0, ts = old), nowMs = ts) }
    }

    @Test
    fun lengthPrefixMismatchRejected() {
        val frame = seal(0).copyOf()
        frame[1] = ((frame[1].toInt() + 7).toByte())
        assertFails { open(frame) }
    }

    @Test
    fun bodyTimestampCannotSmuggle() {
        // seal() owns seq and ts; a caller-supplied body value must lose.
        val lying = LinkedHashMap(loc).apply { put("ts", ts + 3_600_000); put("seq", 999) }
        val frame = Wire.seal(
            groupId = groupId, groupKey = groupKey, identity = identity,
            sessionId = sessionId, seq = 3, payloadMap = lying, tsMs = ts,
        )
        val out = open(frame, nowMs = ts)
        assertEquals(ts, out["ts"])
        assertEquals(3, out["seq"])
    }

    @Test
    fun geofencePayloadRoundTrips() {
        val fenc = linkedMapOf<String, Any?>(
            "type" to "FENC",
            "fid" to "home",
            "ev" to "enter",
            "lat" to 52.5,
            "lon" to 13.4,
        )
        val frame = Wire.seal(
            groupId = groupId, groupKey = groupKey, identity = identity,
            sessionId = sessionId, seq = 1, payloadMap = fenc, tsMs = ts,
        )
        val out = open(frame, nowMs = ts)
        assertEquals("FENC", out["type"])
        assertEquals("home", out["fid"])
        assertEquals("enter", out["ev"])
        assertArrayEquals(
            Wire.typeTag("FENC"),
            Wire.typeTag(out["type"] as String),
        )
    }

    @Test
    fun oversizedPayloadRefused() {
        val fat = LinkedHashMap(loc).apply { put("pad", "x".repeat(20_000)) }
        assertFails {
            Wire.seal(groupId, groupKey, identity, sessionId, 0, fat, ts)
        }
    }

    // ---------------------------------------------------------------- invites

    @Test
    fun inviteRoundTrip() {
        val gid = ByteArray(16) { (it + 3).toByte() }
        val key = ByteArray(32) { (it + 5).toByte() }
        val link = InviteLink.build(gid, key, 1_800_000_000_000L)
        assertTrue(link.startsWith("kea://join#"))
        val parsed = InviteLink.parse(link)
        assertArrayEquals(gid, parsed.groupId)
        assertArrayEquals(key, parsed.groupKey)
        assertEquals(1_800_000_000_000L, parsed.expiresAtMs)
        assertEquals(57, InviteLink.BLOB_BYTES)
    }

    @Test
    fun inviteKeepsKeyInFragment() {
        val key = ByteArray(32) { it.toByte() }
        val link = InviteLink.build(ByteArray(16) { 3 }, key)

        // Everything before '#' is scheme and host, nothing more. Browsers and
        // chat apps never transmit a fragment, so the key stays off the wire.
        val fragmentAt = link.indexOf('#')
        assertTrue("expected kea://join#… but was $link", link.startsWith("kea://join#"))
        assertEquals("kea://join", link.substring(0, fragmentAt))

        // base64 is not substring-preserving, so compare the decoded bytes:
        // blob layout is version(1) | groupId(16) | groupKey(32) | expiresAt(8).
        val blob = B64.dec(link.substring(fragmentAt + 1))
        assertEquals(InviteLink.BLOB_BYTES, blob.size)
        assertArrayEquals(
            "group key must sit inside the fragment",
            key,
            blob.copyOfRange(1 + Wire.GROUP_ID_BYTES, 1 + Wire.GROUP_ID_BYTES + Wire.GROUP_KEY_BYTES),
        )
    }

    @Test
    fun inviteSurvivesBeingPasted() {
        val link = InviteLink.build(ByteArray(16) { 2 }, ByteArray(32) { 4 })
        assertArrayEquals(
            ByteArray(16) { 2 },
            InviteLink.parse("  Look at this: $link  ").groupId,
        )
    }

    @Test
    fun inviteRejectsGarbage() {
        val bad = listOf(
            "https://example.com",
            "kea://join",
            "kea://join#!!!!",
            "kea://join#AAAA",
            "kea://join#" + B64.enc(ByteArray(56)),
        )
        for (b in bad) {
            assertFails("accepted '$b'") { InviteLink.parse(b) }
        }
    }

    @Test
    fun inviteExpiryHonoured() {
        val link = InviteLink.build(ByteArray(16), ByteArray(32), 1_000L)
        assertTrue(InviteLink.parse(link).isExpired(nowMs = 2_000L))
        assertFalse(InviteLink.parse(InviteLink.build(ByteArray(16), ByteArray(32))).isExpired(2_000L))
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun base64UrlIsUnpadded() {
        val raw = ByteArray(33) { it.toByte() }
        val enc = B64.enc(raw)
        assertFalse(enc.contains('='))
        assertArrayEquals(raw, B64.dec(enc))
    }

    @Test
    fun base64RoundTripsEveryLength() {
        for (n in 0..40) {
            val raw = ByteArray(n) { (it * 7).toByte() }
            assertArrayEquals("len $n", raw, B64.dec(B64.enc(raw)))
        }
    }

    @Test
    fun canonicalJsonSortsKeysAndSkipsWhitespace() {
        assertEquals(
            """{"a":1,"b":2,"z":true}""",
            CanonicalJson.encode(linkedMapOf("z" to true, "b" to 2, "a" to 1)),
        )
    }

    @Test
    fun canonicalJsonRendersWholeDoublesWithPoint() {
        // Python writes 34.0; a naive implementation would write 34.
        assertEquals("""{"v":34.0}""", CanonicalJson.encode(mapOf("v" to 34.0)))
        assertEquals("""{"v":0.0}""", CanonicalJson.encode(mapOf("v" to 0.0)))
    }

    @Test
    fun canonicalJsonRejectsNonFinite() {
        assertFails { CanonicalJson.encode(mapOf("v" to Double.NaN)) }
        assertFails { CanonicalJson.encode(mapOf("v" to Double.POSITIVE_INFINITY)) }
    }

    @Test
    fun canonicalJsonRoundTrips() {
        val original = mapOf<String, Any?>(
            "type" to "LOC",
            "seq" to 3,
            "ts" to 1_700_000_000_000L,
            "lat" to 52.520008,
            "bat" to 0.63,
            "src" to "gps",
            "nullable" to null,
        )
        val text = CanonicalJson.encode(original)
        val back = CanonicalJson.decode(text)
        assertEquals("LOC", back["type"])
        assertEquals(3, back["seq"])
        assertEquals(1_700_000_000_000L, back["ts"])
        assertEquals(52.520008, back["lat"])
        assertEquals(0.63, back["bat"])
        assertEquals("gps", back["src"])
        assertTrue(back.containsKey("nullable"))
        assertEquals(null, back["nullable"])
    }

    @Test
    fun hkdfIsDeterministicAndLengthSensitive() {
        val a = Primitives.hkdf(groupKey, groupId, "x".toByteArray(), 32)
        val b = Primitives.hkdf(groupKey, groupId, "x".toByteArray(), 32)
        assertArrayEquals(a, b)
        val c = Primitives.hkdf(groupKey, groupId, "y".toByteArray(), 32)
        assertFalse(a.contentEquals(c))
        assertEquals(32, Primitives.hkdf(groupKey, groupId, "x".toByteArray(), 32).size)
        assertEquals(64, Primitives.hkdf(groupKey, groupId, "x".toByteArray(), 64).size)
    }

    @Test
    fun hkdfRejectsAbsurdOutputLength() {
        assertFails { Primitives.hkdf(groupKey, groupId, ByteArray(1), 255 * 32 + 1) }
    }

    // ----------------------------------------------------------------- utils

    private fun seal(
        seq: Long,
        session: ByteArray = sessionId,
        identity: Identity = this.identity,
        ts: Long = this.ts,
    ): ByteArray = Wire.seal(groupId, groupKey, identity, session, seq, loc, ts)

    private fun open(
        frame: ByteArray,
        groupId: ByteArray = this.groupId,
        groupKey: ByteArray = this.groupKey,
        signPub: ByteArray = identity.publicKey,
        state: ReceiverState = ReceiverState(),
        nowMs: Long = this.ts,
    ): Map<String, Any?> =
        Wire.open(frame, groupId, groupKey, signPub, state, nowMs)

    private fun assertFails(what: String = "", block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
        throw AssertionError(if (what.isEmpty()) "expected a failure" else "expected failure: $what")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun putLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = (v ushr (56 - 8 * i)).toByte()
    }
}