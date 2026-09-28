package com.hereliesaz.guillotine.azphalt

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rolling delimiters (azphalt `spec/llm.md` § Rolling delimiters) — a port of the reference
 * `@azphalt/llm-host` `delimiters.ts`, byte-compatible with the reference sandbox runner (`run.py`).
 *
 * Untrusted material of turn `n` is wrapped `⟦tag_n⟧ … ⟦/tag_n⟧`, where
 * `tag_n = base32(HMAC-SHA256(sessionKey, "azphalt-llm-turn:" || n))[0:26]`. Material can't forge a tag it
 * can't compute, so it can't pose as instructions. A trusted translator (this object for `openai-chat`,
 * the runner for `github-actions-runner`) turns tagged segments into separate `user` messages, so tags
 * never reach the model; output that carries any session tag is rejected.
 *
 * What this buys is authority separation, not immunity from persuasion (spec § Scope).
 */
object AzpLlmDelimiters {

    data class Message(val role: String, val content: String)

    class DelimiterException(message: String) : Exception(message)

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Chat-template control tokens and role markers of the common open-weight families (as run.py). */
    private val NATIVE_MARKERS =
        Regex("""<\|[A-Za-z0-9_]{1,40}\|>|\[/?INST\]|<</?SYS>>|</?s>|<start_of_turn>|<end_of_turn>""")

    private val rng = SecureRandom()

    /** A fresh session key: 32 random bytes, unpadded base64url. One per conversation. */
    fun newSessionKey(): String {
        val raw = ByteArray(32).also(rng::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /** `tag_n` for [sessionKey] (unpadded base64url decoding to at least 16 bytes). */
    fun turnTag(sessionKey: String, n: Int): String {
        require(n >= 0) { "turn must be a non-negative integer" }
        val raw = Base64.getUrlDecoder().decode(sessionKey)
        require(raw.size >= 16) { "sessionKey must decode to at least 16 bytes" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(raw, "HmacSHA256")) }
        return base32(mac.doFinal("azphalt-llm-turn:$n".toByteArray())).take(26)
    }

    /** Every tag of the session up to and including [turn], oldest first. */
    fun sessionTags(sessionKey: String, turn: Int): List<String> = (0..turn).map { turnTag(sessionKey, it) }

    /** Remove every session tag and native control marker from untrusted text. */
    fun scrub(text: String, tags: List<String>): String {
        var out = text
        for (tag in tags) out = out.replace("⟦$tag⟧", "").replace("⟦/$tag⟧", "")
        return NATIVE_MARKERS.replace(out, "")
    }

    /** Wrap untrusted [material] for the current turn (the last of [tags]), scrubbing it first. */
    fun wrap(material: String, tags: List<String>): String {
        val tag = tags.lastOrNull() ?: throw DelimiterException("wrap needs the session's tags")
        return "⟦$tag⟧${scrub(material, tags)}⟦/$tag⟧"
    }

    /** True when [text] carries any tag of the session: model output that does is rejected. */
    fun containsSessionTag(text: String, tags: List<String>): Boolean = tags.any { text.contains(it) }

    /**
     * The translator: tagged segments of the current turn become their own `user` messages, scrubbed;
     * text outside tags keeps its role. Throws on an unterminated segment or a tag that survives.
     */
    fun translate(messages: List<Message>, tags: List<String>): List<Message> {
        val out = ArrayList<Message>()
        fun add(role: String, content: String) {
            if (content.isNotBlank()) out += Message(role, content)
        }
        for (m in messages) {
            if (tags.isEmpty()) {
                add(m.role, m.content)
                continue
            }
            val tag = tags.last()
            val opening = "⟦$tag⟧"
            val closing = "⟦/$tag⟧"
            var pos = 0
            while (true) {
                val start = m.content.indexOf(opening, pos)
                if (start < 0) break
                val end = m.content.indexOf(closing, start + opening.length)
                if (end < 0) throw DelimiterException("unterminated tagged segment")
                add(m.role, m.content.substring(pos, start))
                add("user", scrub(m.content.substring(start + opening.length, end), tags))
                pos = end + closing.length
            }
            add(m.role, m.content.substring(pos))
        }
        if (out.any { containsSessionTag(it.content, tags) }) throw DelimiterException("a session tag survived translation")
        return out
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(value shl (5 - bits)) and 31])
        return sb.toString()
    }

    /**
     * One conversation's delimiter state, for a host that is itself the translator (`openai-chat`).
     * [nextTurn] advances the turn; [tags] are every tag so far, for scrubbing and the output check.
     */
    class Session(val sessionKey: String = newSessionKey()) {
        var turn: Int = -1
            private set

        fun nextTurn(): List<String> {
            turn++
            return tags()
        }

        fun tags(): List<String> = if (turn < 0) emptyList() else sessionTags(sessionKey, turn)
    }
}
