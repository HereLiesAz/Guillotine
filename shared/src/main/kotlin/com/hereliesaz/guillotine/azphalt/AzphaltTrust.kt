package com.hereliesaz.guillotine.azphalt

/**
 * Trust anchors for the flagship azphalt registry (azphalt.store), kept independent of *how* a
 * package's bytes actually arrive — a direct download, or handed over by the Azphalt Store app's
 * delegated-acquisition intent (azphalt `spec/store-app.md`). Either way, the bytes get verified
 * against the same signer.
 */
object AzphaltTrust {
    /**
     * The flagship registry's package-signing key (base64 SPKI, Ed25519) — every package in its
     * catalog is signed with this one key (`apps/storefront/scripts/build-catalog.ts` applies a
     * single build-time signing key across the whole build; verified independently against several
     * *live* packages via `curl` + unzip, not just the repo's source). Without this pinned, every
     * single install from the flagship store hits [AzpPackage.verifyTrust]'s "not from a trusted
     * publisher" gate — since the whole catalog only recently became uniformly signed, that
     * regressed from "fires on nothing" to "fires on everything" with no client-side change.
     *
     * The spec's proper trust-bootstrap channel for this is `/.well-known/azphalt-repository.json`'s
     * `signingKeys` (`spec/repository-api.md` § Trust bootstrap) — but that field is not currently
     * populated on this registry (it's wired to a *different* key, used for paid-download
     * entitlement tokens, not content signing), so there's no in-band way to discover this key yet.
     * Pinned here out-of-band until that's fixed upstream; safe to remove once discovery works and
     * a caller fetches trusted keys from the well-known document instead of a hardcoded constant.
     */
    const val FLAGSHIP_SIGNING_KEY = "MCowBQYDK2VwAyEAzmko3VFIYjx0fhXcGUQVmTpBQc33OlfRdJZ03MirPjU="

    /** The flagship storefront's web URL — the fallback surface when no Azphalt Store app is installed. */
    const val STORE_WEB_URL = "https://azphalt.store"
}
