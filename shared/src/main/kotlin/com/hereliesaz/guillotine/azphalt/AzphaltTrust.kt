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
     * `signingKeys` (`spec/repository-api.md` § Trust bootstrap). The store now lists this key there
     * as `packages-v1` (azphalt#246), but Guillotine doesn't fetch it yet, so it stays pinned here.
     *
     * Rotated 2026-10: the previous store key's private half was lost and the catalog was re-signed
     * with this one. See [RETIRED_FLAGSHIP_SIGNING_KEY] and [isFlagshipRotation].
     */
    const val FLAGSHIP_SIGNING_KEY = "MCowBQYDK2VwAyEAWNptGhJCdyjabJ/pEnw+nh41woxC01z6mS8XnL8Cv+M="

    /**
     * The key the flagship catalog was signed with before the 2026-10 rotation (its private half was
     * lost, so nothing chains the new key to it). Still trusted, so packages installed earlier keep
     * verifying, and recognised by [isFlagshipRotation] so their updates aren't refused.
     */
    const val RETIRED_FLAGSHIP_SIGNING_KEY = "MCowBQYDK2VwAyEAzmko3VFIYjx0fhXcGUQVmTpBQc33OlfRdJZ03MirPjU="

    /** Every key the flagship registry has signed with: the `trustedKeys` for store installs. */
    val FLAGSHIP_SIGNING_KEYS: Set<String> = setOf(FLAGSHIP_SIGNING_KEY, RETIRED_FLAGSHIP_SIGNING_KEY)

    /**
     * True when an update moves a package pinned to the retired flagship key onto the current one: the
     * store's own rotation, not a third party taking the id. Installers accept it without a
     * publisher-change prompt and re-pin. Any other key change still needs the user's approval.
     */
    fun isFlagshipRotation(pinnedKey: String?, signerKey: String?): Boolean =
        pinnedKey == RETIRED_FLAGSHIP_SIGNING_KEY && signerKey == FLAGSHIP_SIGNING_KEY

    /** The flagship storefront's web URL — the fallback surface when no Azphalt Store app is installed. */
    const val STORE_WEB_URL = "https://azphalt.store"
}
