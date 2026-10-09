package app.kea.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * The three primitives PROTOCOL.md §4 needs, and nothing else.
 *
 * BouncyCastle is used rather than the platform JCA because:
 *  - ChaCha20-Poly1305 only reaches Conscrypt at API 28 and we support 26.
 *  - Ed25519 only reaches Conscrypt at API 33 and its raw-key API is awkward.
 *  - Raw byte semantics matter here: the wire format is defined by the spec, not
 *    by whichever provider happens to be installed.
 *
 * R8 discards the parts of BC that this file does not reference.
 */
internal object Primitives {

    private val random = SecureRandom()

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val SIGNATURE_BYTES = 64
    const val MAC_BITS = 128

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** HKDF-SHA256 (RFC 5869) extract-then-expand. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF output too long: $length" }
        val out = ByteArray(length)
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        var written = 0
        while (written < length) {
            // generateBytes returns the number of bytes produced at that offset.
            val n = gen.generateBytes(out, written, length)
            if (n <= 0) break
            written += n
        }
        require(written == length) { "HKDF produced $written of $length bytes" }
        return out
    }

    /**
     * ChaCha20-Poly1305 (RFC 8439). Output is `ciphertext || tag`, matching
     * Python's cryptography and iOS CryptoKit once the nonce is prefixed.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        return run(cipher, plaintext)
    }

    /** @throws KEAAuthException if the tag does not authenticate. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        return try {
            run(cipher, sealed)
        } catch (e: IllegalStateException) {
            // BC signals a bad tag as a failed doFinal / DataLengthException.
            throw KEAAuthException("AEAD authentication failed", e)
        } catch (e: org.bouncycastle.crypto.DataLengthException) {
            throw KEAAuthException("AEAD frame truncated", e)
        }
    }

    private fun run(cipher: ChaCha20Poly1305, input: ByteArray): ByteArray {
        val out = ByteArray(cipher.getOutputSize(input.size))
        var off = 0
        off += cipher.processBytes(input, 0, input.size, out, off)
        off += cipher.doFinal(out, off)
        return if (off == out.size) out else out.copyOf(off)
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, params(seed))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Constant-time inside BC. Never throws on a bad signature. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: RuntimeException) {
            // Malformed key material is a verification failure, not a crash.
            false
        }
    }

    private fun params(seed: ByteArray) =
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
}