package com.hereliesaz.guillotine.azphalt

/**
 * Evaluates a manifest's `compat` — the host azphalt-API version a package needs — against this host's.
 *
 * azphalt `spec/extension-manifest.md` § `compat` fixes a deliberately small grammar so every host
 * validates identically: an **optional** comparator (`>=` `>` `<=` `<` `=`, defaulting to `>=`) followed
 * by `MAJOR[.MINOR[.PATCH]]`, omitted parts being `0` (`0.1` ≡ `0.1.0`). Ranges, unions (`||`), hyphen
 * ranges, `^`, `~` and prerelease tags are explicitly **not** part of `0.1`.
 *
 * `spec/web-handoff.md` § Host obligations (5) requires a host to confirm a package is one it can run —
 * `kind`, `compat`, `mediaDomains`, `targetApps` — before installing. `targetApps` is enforced by
 * [AzpHandoffInstaller]; this is the `compat` half. (`mediaDomains` is not a manifest field at all — it
 * is a repository-summary field per `spec/repository-api.md`, so a host verifying raw bytes has nothing
 * to read and cannot check it. `kind` is surfaced rather than refused: [AzpInstallSurfaces] tells the
 * user plainly when a kind has no consumer here, which is more useful than refusing a package they may
 * have installed knowing it awaits a runtime.)
 */
object AzpCompat {

    /**
     * The azphalt host-API version Guillotine implements. Matches the manifest format it models
     * ([AzpManifest] tracks `0.1`), and is the left-hand side of every comparison here.
     */
    const val HOST_API_VERSION = "0.1"

    /** The comparators the `0.1` grammar allows. Order matters: `>=` and `<=` must be tried before `>`/`<`. */
    private val OPERATORS = listOf(">=", "<=", ">", "<", "=")

    /**
     * Whether [hostVersion] satisfies [compat].
     *
     * Returns **null** when [compat] is not expressible in the `0.1` grammar — a range, a union, a `^`
     * or `~`, a prerelease tag, or plain nonsense. Null is deliberately not `false`: a comparator this
     * host cannot parse says nothing about whether the package works, and refusing on unrecognised
     * *syntax* would reject packages that a later spec version legitimises. A malformed comparator is a
     * reason to stop evaluating, not evidence of incompatibility. A well-formed comparator that is
     * genuinely unsatisfied returns false, and callers do refuse on that.
     */
    fun satisfies(compat: String, hostVersion: String = HOST_API_VERSION): Boolean? {
        val trimmed = compat.trim()
        if (trimmed.isEmpty()) return null
        val op = OPERATORS.firstOrNull { trimmed.startsWith(it) } ?: ">="
        val versionPart = trimmed.removePrefix(if (trimmed.startsWith(op)) op else "").trim()
        val required = parseVersion(versionPart) ?: return null
        val host = parseVersion(hostVersion) ?: return null
        val cmp = compare(host, required)
        return when (op) {
            ">=" -> cmp >= 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            "<" -> cmp < 0
            "=" -> cmp == 0
            else -> null
        }
    }

    /**
     * `MAJOR[.MINOR[.PATCH]]` with omitted parts defaulting to 0. Anything else — a fourth part, a
     * prerelease or build suffix, a non-numeric or negative part, an empty string — is null.
     */
    private fun parseVersion(s: String): Triple<Int, Int, Int>? {
        if (s.isEmpty()) return null
        val parts = s.split('.')
        if (parts.size > 3) return null
        val nums = parts.map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull() ?: return null
        }
        return Triple(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
    }

    /** Semver precedence over the numeric triple. */
    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
}
