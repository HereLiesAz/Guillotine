package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.Serializable

/**
 * The detached signature stored as `signature.json` inside a signed `.azp` — an Ed25519 signature
 * over the canonical `manifest.json` bytes (which transitively cover the payload through the
 * `manifest.files` digests). Mirrors `@azphalt/azp`'s `AzpSignature`.
 *
 * A signature alone is *tamper-evidence*, not identity: [AzpPackage.signatureStatus] confirms it is
 * internally consistent, and [AzpPackage.verifyTrust] decides whether the signer is trusted against
 * a host trust store (directly, or via a registry [countersignature] chain).
 */
@Serializable
data class AzpSignature(
    /** Signature algorithm — currently always `"ed25519"`. */
    val alg: String,
    /** Base64 SPKI (X.509) public key the [signature] verifies against. */
    val publicKey: String,
    /** Optional signer-chosen key identifier. */
    val keyId: String? = null,
    /** Base64 Ed25519 signature over the canonical `manifest.json` bytes. */
    val signature: String,
    /** Optional counter-signature from a registry/authority vouching for [publicKey]. */
    val countersignature: AzpCountersignature? = null,
)

/**
 * A counter-signature: an Ed25519 signature over the SPKI DER bytes of the key **below** it in the
 * chain (the author at the base, or the previous counter-signer). A host that trusts a key anywhere
 * in the chain transitively trusts the author. Counter-signatures nest, forming a web-of-trust chain
 * of bounded depth. Mirrors `@azphalt/azp`'s `AzpCountersignature`.
 */
@Serializable
data class AzpCountersignature(
    /** Base64 SPKI public key of the registry/authority making this vouch. */
    val publicKey: String,
    /** Base64 Ed25519 signature over the vouched-for key's SPKI DER bytes. */
    val signature: String,
    /** Optional registry-chosen key identifier. */
    val keyId: String? = null,
    /** A further counter-signature vouching for [publicKey] — the next hop up the chain. */
    val countersignature: AzpCountersignature? = null,
)
