package com.hereliesaz.guillotine.mcp

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end seal for the Cloudflare relay. The MCP bearer token is the shared secret; the
 * Cloudflare Worker only ever relays ciphertext and a public [roomId], so it can route the
 * device ⇄ tool pair without being able to read, forge, replay or misroute editor traffic.
 *
 * Both sides (this app and the Node `tools/mcp-relay` proxy) derive the same values from the
 * token, so the schemes here must match `proxy.js` exactly (frame version 2):
 *  - key   = SHA-256("guillotine-mcp-key:v2:" + dir + ":" + token) → 32 bytes (AES-256), one key
 *            per direction ([DIR_TO_DEVICE] = "t2d", [DIR_TO_TOOL] = "d2t"), so a frame sealed for
 *            one direction can never be reflected back as the other;
 *  - room  = hex(SHA-256("guillotine-mcp-room:" + token))   (public rendezvous id)
 *  - frame = `{"v":2,"rid","ctr","ts","iv","ct"}`: AES-256-GCM, random 12-byte IV, 128-bit tag
 *            **appended** to the ciphertext; IV and (ciphertext||tag) are base64 (standard, no wraps);
 *  - AAD   = UTF-8 "guillotine-mcp|v2|" + dir + "|" + rid + "|" + ctr + "|" + ts, so the routing id,
 *            direction, the sender's counter and its timestamp are all authenticated — the Worker
 *            can't swap a response onto another request's `rid` or re-stamp an old frame.
 *
 * Replay: the device accepts a tool→device frame once ([ReplayGuard]: `ts` within [REPLAY_WINDOW_MS]
 * of its clock and `rid` not seen before in that window). A device→tool response echoes the request's
 * `rid` and `ctr`; the proxy accepts it only for a still-pending request with that exact pair.
 */
object McpCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    const val VERSION = 2
    const val DIR_TO_DEVICE = "t2d"
    const val DIR_TO_TOOL = "d2t"

    /** How far a request's timestamp may be from the device clock (either way) and still be accepted. */
    const val REPLAY_WINDOW_MS = 10 * 60_000L

    private val random = SecureRandom()

    fun roomId(token: String): String = sha256("guillotine-mcp-room:$token").toHex()

    private fun key(token: String, dir: String): ByteArray {
        require(dir == DIR_TO_DEVICE || dir == DIR_TO_TOOL) { "bad direction: $dir" }
        return sha256("guillotine-mcp-key:v2:$dir:$token")
    }

    /** The additional authenticated data binding a frame to its routing id, direction, counter and time. */
    fun aad(dir: String, rid: String, ctr: Long, ts: Long): ByteArray =
        "guillotine-mcp|v$VERSION|$dir|$rid|$ctr|$ts".toByteArray(Charsets.UTF_8)

    data class Sealed(val ivB64: String, val ctB64: String)

    /** Encrypt [plaintext] for direction [dir]. Returns base64 IV and base64 (ciphertext||tag). */
    fun seal(token: String, dir: String, rid: String, ctr: Long, ts: Long, plaintext: String): Sealed {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(token, dir), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(dir, rid, ctr, ts))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // ciphertext || 16-byte tag
        return Sealed(iv.b64(), ct.b64())
    }

    /**
     * Decrypt a frame, or throw if the token is wrong, the data was tampered with, or any of
     * [dir]/[rid]/[ctr]/[ts] differ from what the sender sealed.
     */
    fun open(token: String, dir: String, rid: String, ctr: Long, ts: Long, ivB64: String, ctB64: String): String {
        val iv = ivB64.unb64()
        require(iv.size == IV_BYTES) { "bad iv" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(token, dir), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(dir, rid, ctr, ts))
        return cipher.doFinal(ctB64.unb64()).toString(Charsets.UTF_8)
    }

    /**
     * Rejects replayed tool→device frames: a frame is accepted only if its timestamp is within
     * [windowMs] of [clock] and its `rid` hasn't been accepted inside that window. Call [accept] only
     * AFTER the frame authenticated (so forged frames can't fill the cache). Thread-safe.
     */
    class ReplayGuard(
        private val windowMs: Long = REPLAY_WINDOW_MS,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val seen = LinkedHashMap<String, Long>() // rid -> ts, in arrival order

        @Synchronized
        fun accept(rid: String, ts: Long): Boolean {
            val now = clock()
            if (rid.isEmpty() || kotlin.math.abs(now - ts) > windowMs) return false
            // Anything older than the window can no longer pass the timestamp check; forget it.
            val it = seen.entries.iterator()
            while (it.hasNext()) { if (now - it.next().value > windowMs) it.remove() else break }
            if (seen.containsKey(rid)) return false
            seen[rid] = ts
            return true
        }
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    // Mask to a byte before formatting ("%02x" on a negative Byte sign-extends to 8 hex digits),
    // and pin the locale so the hex is identical everywhere (and Lint-clean).
    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }
    private fun ByteArray.b64(): String = java.util.Base64.getEncoder().encodeToString(this)
    private fun String.unb64(): ByteArray = java.util.Base64.getDecoder().decode(this)
}
