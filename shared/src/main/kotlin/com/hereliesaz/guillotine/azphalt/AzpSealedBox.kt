package com.hereliesaz.guillotine.azphalt

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * libsodium `crypto_box_seal` — how GitHub requires Actions secrets to be encrypted before upload
 * (azphalt `spec/llm.md` § Setup, `setup.secrets`). Port of the reference host's `sealed-box.ts`:
 * an ephemeral X25519 key pair, nonce = BLAKE2b-192(ephemeralPk ‖ recipientPk), output
 * ephemeralPk ‖ crypto_box(message) where crypto_box is XSalsa20-Poly1305 keyed by
 * HSalsa20(X25519(ephemeralSk, recipientPk)).
 */
object AzpSealedBox {

    private val rng = SecureRandom()

    fun seal(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32) { "recipient key must be 32 bytes" }
        val ephSk = ByteArray(32).also(rng::nextBytes)
        val ephPk = ByteArray(32).also { X25519.scalarMultBase(ephSk, 0, it, 0) }
        val nonce = nonce(ephPk, recipientPublicKey)
        return ephPk + box(message, nonce, recipientPublicKey, ephSk)
    }

    /** `crypto_box_seal_open`, for tests and completeness. Returns null when authentication fails. */
    fun open(sealed: ByteArray, recipientPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray? {
        if (sealed.size < 32 + 16) return null
        val ephPk = sealed.copyOfRange(0, 32)
        val k = beforeNm(ephPk, recipientSecretKey)
        return secretBoxOpen(sealed.copyOfRange(32, sealed.size), nonce(ephPk, recipientPublicKey), k)
    }

    internal fun nonce(ephPk: ByteArray, recipientPk: ByteArray): ByteArray {
        val d = Blake2bDigest(192)
        d.update(ephPk, 0, ephPk.size)
        d.update(recipientPk, 0, recipientPk.size)
        return ByteArray(24).also { d.doFinal(it, 0) }
    }

    /** `crypto_box_beforenm`: HSalsa20 over the X25519 shared secret with a zero nonce. */
    internal fun beforeNm(publicKey: ByteArray, secretKey: ByteArray): ByteArray {
        val shared = ByteArray(32)
        X25519.scalarMult(secretKey, 0, publicKey, 0, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    private fun box(m: ByteArray, nonce: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray =
        secretBox(m, nonce, beforeNm(pk, sk))

    /** XSalsa20-Poly1305 secretbox, libsodium's "easy" layout: tag ‖ ciphertext. */
    private fun secretBox(m: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val stream = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val block0 = ByteArray(32 + m.size)
        System.arraycopy(m, 0, block0, 32, m.size)
        val out = ByteArray(block0.size)
        stream.processBytes(block0, 0, block0.size, out, 0)
        val polyKey = out.copyOfRange(0, 32)
        val c = out.copyOfRange(32, out.size)
        return mac(polyKey, c) + c
    }

    private fun secretBoxOpen(boxed: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (boxed.size < 16) return null
        val tag = boxed.copyOfRange(0, 16)
        val c = boxed.copyOfRange(16, boxed.size)
        val stream = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(key), nonce)) }
        val block0 = ByteArray(32 + c.size)
        System.arraycopy(c, 0, block0, 32, c.size)
        val out = ByteArray(block0.size)
        stream.processBytes(block0, 0, block0.size, out, 0)
        if (!java.security.MessageDigest.isEqual(mac(out.copyOfRange(0, 32), c), tag)) return null
        return out.copyOfRange(32, out.size)
    }

    private fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val p = Poly1305().apply { init(KeyParameter(key)) }
        p.update(data, 0, data.size)
        return ByteArray(16).also { p.doFinal(it, 0) }
    }

    /** HSalsa20 core (NaCl `crypto_core_hsalsa20`): 20 rounds, no final addition. */
    internal fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        fun le(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        val sigma = "expand 32-byte k".toByteArray()
        val x = IntArray(16)
        x[0] = le(sigma, 0); x[5] = le(sigma, 4); x[10] = le(sigma, 8); x[15] = le(sigma, 12)
        for (i in 0 until 4) {
            x[1 + i] = le(key, 4 * i)
            x[11 + i] = le(key, 16 + 4 * i)
            x[6 + i] = le(input, 4 * i)
        }
        fun r(a: Int, b: Int, c: Int, s: Int) { x[a] = x[a] xor Integer.rotateLeft(x[b] + x[c], s) }
        repeat(10) {
            r(4, 0, 12, 7); r(8, 4, 0, 9); r(12, 8, 4, 13); r(0, 12, 8, 18)
            r(9, 5, 1, 7); r(13, 9, 5, 9); r(1, 13, 9, 13); r(5, 1, 13, 18)
            r(14, 10, 6, 7); r(2, 14, 10, 9); r(6, 2, 14, 13); r(10, 6, 2, 18)
            r(3, 15, 11, 7); r(7, 3, 15, 9); r(11, 7, 3, 13); r(15, 11, 7, 18)
            r(1, 0, 3, 7); r(2, 1, 0, 9); r(3, 2, 1, 13); r(0, 3, 2, 18)
            r(6, 5, 4, 7); r(7, 6, 5, 9); r(4, 7, 6, 13); r(5, 4, 7, 18)
            r(11, 10, 9, 7); r(8, 11, 10, 9); r(9, 8, 11, 13); r(10, 9, 8, 18)
            r(12, 15, 14, 7); r(13, 12, 15, 9); r(14, 13, 12, 13); r(15, 14, 13, 18)
        }
        val out = ByteArray(32)
        intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]).forEachIndexed { i, v ->
            for (j in 0 until 4) out[4 * i + j] = (v ushr (8 * j)).toByte()
        }
        return out
    }
}
