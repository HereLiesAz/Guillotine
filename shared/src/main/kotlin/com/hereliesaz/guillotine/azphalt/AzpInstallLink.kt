package com.hereliesaz.guillotine.azphalt

import java.net.URI

/**
 * A `azphalt://install?id=<packageId>&version=<version>` deep link — a web storefront naming a package
 * for whatever conforming host is installed to go and fetch.
 *
 * azphalt's `spec/store-app.md` specifies only the Android app-to-app BROWSE handoff and states outright
 * that the web case is unspecified, which is why azphalt.store's **Install · Free** button dead-ends at
 * "install it from any Azphalt-conforming host": a web page cannot push a `.azp` into an Android app.
 * This is the other half of `AzpExternalOpen`'s file route (`:app`) — instead of handing over bytes, the page
 * hands over a *name*, and the host resolves it against the registry ([AzphaltRegistry]).
 *
 * The scheme is deliberately host-agnostic: any conforming host claims `azphalt://`, and Android shows a
 * chooser when several are installed. No package name is baked in anywhere.
 *
 * A link is untrusted input — it names a package, it does not vouch for one. Everything downloaded on the
 * strength of one still goes through [AzpHandoffInstaller]'s integrity, signature and publisher-continuity
 * checks, exactly as the spec's "a lying store app gains nothing" requires of the app-to-app route.
 *
 * Pure JVM ([java.net.URI], never `android.net.Uri`) so `:shared` stays platform-agnostic and this parses
 * under a plain JVM unit test.
 */
data class AzpInstallLink(
    /** Reverse-DNS package id, already validated against [ID_PATTERN]. */
    val id: String,
    /** Requested version, validated against [VERSION_PATTERN]; null means "resolve the latest". */
    val version: String?,
) {
    companion object {
        const val SCHEME = "azphalt"
        const val HOST = "install"

        /**
         * [id] and [version] are interpolated into a registry URL *path*, so they are validated rather
         * than merely escaped: anything outside these character sets — or containing `..` — is refused
         * outright instead of being encoded and sent. A deep link arrives from a web page, and a web page
         * must not be able to steer a path segment.
         */
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val VERSION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9.+-]{0,63}$")

        /**
         * Parses [raw], returning null for anything that isn't a well-formed, well-scoped install link:
         * wrong scheme or host, absent/blank/invalid `id`, present-but-invalid `version`, or a value that
         * survives percent-decoding as a traversal attempt.
         *
         * A present-but-invalid `version` is a hard null rather than a silent fall back to "latest" —
         * quietly installing a *different* version than the link asked for is worse than doing nothing.
         */
        fun parse(raw: String?): AzpInstallLink? {
            if (raw.isNullOrBlank()) return null
            val uri = try {
                URI(raw.trim())
            } catch (e: Exception) {
                return null
            }
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            // Opaque forms (`azphalt:install?…`) have no authority at all; the contract is hierarchical.
            val host = uri.host ?: uri.authority ?: return null
            if (!host.equals(HOST, ignoreCase = true)) return null

            val params = parseQuery(uri.rawQuery) ?: return null
            val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
            if (!isSafe(id, ID_PATTERN)) return null

            val version = params["version"]?.takeIf { it.isNotBlank() }
            if (version != null && !isSafe(version, VERSION_PATTERN)) return null

            return AzpInstallLink(id, version)
        }

        private fun isSafe(value: String, pattern: Regex): Boolean =
            !value.contains("..") && pattern.matches(value)

        /**
         * Splits a raw query into percent-decoded values. Deliberately *not* [java.net.URLDecoder], which
         * applies `application/x-www-form-urlencoded` rules and turns a literal `+` into a space — `+` is
         * legal in a semver build identifier (`1.0.0+build.7`), so form-decoding would corrupt exactly the
         * versions it's most likely to see. Percent-escapes are decoded; everything else is left alone.
         *
         * Null if any escape is malformed — the whole link fails rather than one pair quietly vanishing,
         * since a dropped `version=` would read as "install the latest" instead of "this link is broken".
         * (`java.net.URI` rejects most of these first; this is the belt to its braces.)
         */
        private fun parseQuery(rawQuery: String?): Map<String, String>? {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val pairs = ArrayList<Pair<String, String>>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) continue // a valueless flag carries nothing this contract uses
                val key = percentDecode(pair.substring(0, eq)) ?: return null
                val value = percentDecode(pair.substring(eq + 1)) ?: return null
                pairs += key to value
            }
            // First occurrence wins: a duplicated `id=` must not let a later copy override the one a
            // reader (or a logging layer) would consider authoritative.
            return pairs.reversed().toMap()
        }

        /**
         * Decodes `%XX` escapes as UTF-8, or null if any escape is malformed. Raw query text is ASCII by
         * definition; a stray non-ASCII byte means the input was never a well-formed URI, so refuse it
         * rather than guess an encoding.
         */
        private fun percentDecode(s: String): String? {
            if (!s.contains('%')) return if (s.all { it.code <= 127 }) s else null
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= s.length) return null
                        val hex = s.substring(i + 1, i + 3)
                        // Explicit hex-digit check: toIntOrNull(16) also accepts a sign, so "%+1" would
                        // otherwise decode to 0x01 instead of being rejected as malformed.
                        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        out.write(hex.toInt(16))
                        i += 3
                    }
                    c.code <= 127 -> { out.write(c.code); i++ }
                    else -> return null
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
