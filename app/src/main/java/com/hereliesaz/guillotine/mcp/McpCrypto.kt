package com.hereliesaz.guillotine.mcp

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end seal for the Cloudflare relay. The MCP bearer token is the shared secret; the
 * Cloudflare Worker only ever relays ciphertext and a public [roomId], so it can route the
 * device ⇄ tool pair without being able to read or forge editor traffic.
 *
 * Both sides (this app and the Node `tools/mcp-relay` proxy) derive the same values from the
 * token, so the schemes here must match the proxy exactly:
 *  - key   = SHA-256("guillotine-mcp-key:"  + token)   → 32 bytes (AES-256)
 *  - room  = hex(SHA-256("guillotine-mcp-room:" + token))   (public rendezvous id)
 *  - frame = AES-256-GCM, random 12-byte IV, 128-bit tag **appended** to the ciphertext;
 *            IV and (ciphertext||tag) are sent base64 (standard, no line wraps).
 */
object McpCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun roomId(token: String): String = sha256("guillotine-mcp-room:$token").toHex()

    private fun key(token: String): ByteArray = sha256("guillotine-mcp-key:$token")

    data class Sealed(val ivB64: String, val ctB64: String)

    /** Encrypt [plaintext] under the token. Returns base64 IV and base64 (ciphertext||tag). */
    fun seal(token: String, plaintext: String): Sealed {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(token), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // ciphertext || 16-byte tag
        return Sealed(iv.b64(), ct.b64())
    }

    /** Decrypt a frame, or throw if the token is wrong / the data was tampered with. */
    fun open(token: String, ivB64: String, ctB64: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(token), "AES"), GCMParameterSpec(GCM_TAG_BITS, ivB64.unb64()))
        return cipher.doFinal(ctB64.unb64()).toString(Charsets.UTF_8)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    // Mask to a byte before formatting — "%02x" on a negative Byte sign-extends to 8 hex digits.
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
