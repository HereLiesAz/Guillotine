package com.hereliesaz.guillotine.azphalt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A catalog entry as shown in the store, projected from a registry [AzphaltRepository.RepoPackage]
 * into just what the card renders. Discovery is remote (the azphalt storefront); the bytes are only
 * fetched + verified on install.
 */
data class AzphaltPlugin(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val kind: String,
    val category: String,
    val tags: List<String>,
    /** "Free", or a formatted price like "$6.00" for a paid package. */
    val priceLabel: String,
    val rating: Double?,
    val downloads: Long,
    /** A remote (`https:`) preview still, when the package ships one. */
    val previewImageUrl: String?,
)

/**
 * Backing state for the Azphalt Store screen. It browses a remote [AzphaltRepository] (the hosted
 * storefront) rather than a bundled catalog — the packages live at the registry, not in the app. The
 * fetch blocks, so [loadCatalog] must be called off the main thread; results and any error land on
 * the exposed flows for the UI to observe.
 */
class AzphaltStoreState(
    private val repository: AzphaltRepository = AzphaltRepository(),
) {
    private val _plugins = MutableStateFlow<List<AzphaltPlugin>>(emptyList())
    val plugins: StateFlow<List<AzphaltPlugin>> = _plugins.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** A user-facing message when the catalog couldn't be loaded (offline, registry down, …). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val categories = listOf("All", "vegas-inspired", "layer-effects", "layer-effects-scenery", "kinetic-typography", "kinetic-typography-smart", "companion-apps", "mcp-servers")

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    /**
     * Fetch the catalog from the storefront and publish it. [hostAppId] is this host's reverse-DNS id;
     * the registry scopes results by `targetApps` and the client re-applies that scoping so packages
     * meant for other hosts never show. Blocks on network — call from an IO dispatcher.
     */
    fun loadCatalog(hostAppId: String = "", query: String = "") {
        _loading.value = true
        _error.value = null
        try {
            val packages = repository.fetchCatalog(query = query, hostAppId = hostAppId)
            _plugins.value = packages.map { it.toPlugin() }
        } catch (e: AzphaltRepository.RepoException) {
            _error.value = e.message ?: "Could not reach the Azphalt store."
            _plugins.value = emptyList()
        } catch (e: Exception) {
            _error.value = "Could not load the catalog: ${e.message}"
            _plugins.value = emptyList()
        } finally {
            _loading.value = false
        }
    }

    private fun AzphaltRepository.RepoPackage.toPlugin(): AzphaltPlugin = AzphaltPlugin(
        id = id,
        name = name,
        description = description.ifBlank { "An azphalt plugin." },
        author = author,
        version = version,
        kind = kind,
        category = categoryFor(this),
        tags = tags,
        priceLabel = priceLabel(price),
        rating = rating,
        downloads = downloads,
        previewImageUrl = preview?.image?.takeIf { it.startsWith("http") },
    )

    private fun categoryFor(pkg: AzphaltRepository.RepoPackage): String = when {
        pkg.kind == "app" -> "companion-apps"
        pkg.kind == "mcp" -> "mcp-servers"
        pkg.id.contains("vegas") -> "vegas-inspired"
        pkg.id.contains("scenery") -> "layer-effects-scenery"
        pkg.id.contains("smart") -> "kinetic-typography-smart"
        pkg.id.contains("typography") || pkg.id.contains("type") ||
            pkg.tags.contains("text") || pkg.mediaDomains.contains("text") -> "kinetic-typography"
        else -> "layer-effects"
    }

    /**
     * Download [pkg] from the registry, verify its integrity **and provenance** on-device, and write the
     * verified `.azp` into [extensionsDirPath] — the directory the editor reads installed extensions
     * from. Discovery is remote; this is where the bytes finally land locally, and only after
     * [AzpPackage.verifyTrust] accepts them (an HTML error page, truncated body, or tampered signature
     * fails here instead of installing). Mirrors [AzpModelInstall.install]'s trust gate, so the store's
     * "shader/LUT/code" install path gets the same protection the model-install path already had —
     * with one deliberate difference: [InstallResult.Untrusted] only fires for a package that IS signed
     * but by a key [trustedKeys] doesn't recognize, never for a merely-unsigned package. Before this
     * trust gate existed, every install was integrity-only (equivalent to today's "unsigned" case), so
     * gating unsigned installs behind an extra confirmation would be new friction with no matching new
     * protection — nothing in the ecosystem is signed yet, so it would fire on literally every install.
     * [pins] still enforces trust-on-first-use — a later update of the same package id signed by a
     * *different* key returns [InstallResult.PublisherChanged] instead of silently overwriting, unless
     * [allowPublisherChange] is the user's explicit confirmation. Blocks — call off-thread.
     */
    fun install(
        pkg: AzphaltPlugin,
        extensionsDirPath: String,
        trustedKeys: Set<String> = emptySet(),
        pins: AzpPublisherPins? = null,
        allowUntrusted: Boolean = false,
        allowPublisherChange: Boolean = false,
    ): InstallResult {
        return try {
            val bytes = repository.download(pkg.id, pkg.version)
            val trust = AzpPackage.verifyTrust(bytes, trustedKeys)
            if (!trust.ok) {
                return InstallResult.Failure("“${pkg.name}” failed verification and was not installed: ${trust.reason}")
            }
            // Publisher continuity (trust-on-first-use): if this id was installed before, the signer must
            // match the pinned key, or the caller must have already confirmed the change. Checked before
            // the trust prompt so a hijacked update surfaces as a publisher change, not a generic warning.
            val pinnedKey = pins?.keyFor(pkg.id)
            if (pinnedKey != null && trust.signerPublicKey != pinnedKey && !allowPublisherChange) {
                return InstallResult.PublisherChanged(pkg.id, pinnedKey, trust.signerPublicKey)
            }
            // Only a *signed* package with an unrecognized signer needs a confirmation — an unsigned
            // package has no provenance claim to be suspicious of; it's exactly as trustworthy as every
            // install was before this trust gate existed.
            if (trust.signed && !trust.trusted && !allowUntrusted) {
                return InstallResult.Untrusted(trust.reason)
            }
            val dir = File(extensionsDirPath).apply { mkdirs() }
            // A package id is reverse-DNS, but sanitize anyway so it can never escape the dir, and cap
            // its length so a pathological id can't blow the filesystem's 255-char name limit.
            val sanitized = pkg.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeName = sanitized.take(120) + ".azp"
            File(dir, safeName).writeBytes(bytes)
            // Pin the publisher on first install, or when the caller approved a rotation. Only signed
            // packages pin — an unsigned package leaves no key to enforce against.
            if (trust.signerPublicKey != null && pins != null && (pinnedKey == null || allowPublisherChange)) {
                pins.pin(pkg.id, trust.signerPublicKey)
            }
            // trust.signed only means "carries a signature.json", not "it was cryptographically verified"
            // — verifyTrust's Ed25519-unavailable path returns signed=true with the signature genuinely
            // unverified. signatureStatus().valid is the field that actually means "verified", so re-derive
            // it here rather than reporting trust.signed as if it were that (a bare re-unzip; these packages
            // are small and this runs once per install, not a hot path).
            val signatureValid = AzpPackage.signatureStatus(bytes).valid
            InstallResult.Success(pkg.id, signed = trust.signed, signatureValid = signatureValid)
        } catch (e: AzphaltRepository.RepoException) {
            InstallResult.Failure(e.message ?: "Download failed.")
        } catch (e: Exception) {
            InstallResult.Failure("Install failed: ${e.message}")
        }
    }

    sealed class InstallResult {
        /** The `.azp` verified and was written. [signed]/[signatureValid] surface provenance for the UI. */
        data class Success(val id: String, val signed: Boolean, val signatureValid: Boolean) : InstallResult()
        data class Failure(val message: String) : InstallResult()
        /** Integrity-sound, but not from a trusted signer (or unsigned) and not yet approved by the user. */
        data class Untrusted(val reason: String) : InstallResult()
        /** [packageId] was previously installed from a different publisher key than this update carries. */
        data class PublisherChanged(val packageId: String, val pinnedKey: String, val newSignerKey: String?) : InstallResult()
    }

    private fun priceLabel(price: AzphaltRepository.RepoPrice?): String {
        if (price == null || price.amountCents <= 0) return "Free"
        val whole = price.amountCents / 100
        val cents = price.amountCents % 100
        val amount = "$whole.${cents.toString().padStart(2, '0')}"
        return if (price.currency == "USD") "$$amount" else "$amount ${price.currency}"
    }
}
