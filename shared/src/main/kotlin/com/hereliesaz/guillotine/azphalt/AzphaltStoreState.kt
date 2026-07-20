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
     * Download [pkg] from the registry, verify its integrity on-device, and write the verified `.azp`
     * into [extensionsDirPath] — the directory the editor reads installed extensions from. Discovery is
     * remote; this is where the bytes finally land locally, and only after [AzpPackage] accepts them
     * (an HTML error page or truncated body fails here instead of installing). Blocks — call off-thread.
     */
    fun install(pkg: AzphaltPlugin, extensionsDirPath: String): InstallResult {
        return try {
            val bytes = repository.download(pkg.id, pkg.version)
            AzpPackage.load(bytes) // integrity gate: throws AzpException on any verification failure
            val signed = AzpPackage.signatureStatus(bytes)
            // A present-but-invalid signature means tampering/corruption — refuse it. (Unsigned is
            // allowed: integrity without provenance, surfaced to the user, per spec/package-format.md.)
            if (signed.signed && !signed.valid) {
                return InstallResult.Failure("“${pkg.name}” has an invalid signature and was not installed: ${signed.error ?: "verification failed"}")
            }
            val dir = File(extensionsDirPath).apply { mkdirs() }
            // A package id is reverse-DNS, but sanitize anyway so it can never escape the dir, and cap
            // its length so a pathological id can't blow the filesystem's 255-char name limit.
            val sanitized = pkg.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeName = sanitized.take(120) + ".azp"
            File(dir, safeName).writeBytes(bytes)
            InstallResult.Success(pkg.id, signed = signed.signed, signatureValid = signed.valid)
        } catch (e: AzpPackage.AzpException) {
            InstallResult.Failure("“${pkg.name}” failed verification and was not installed: ${e.message}")
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
    }

    private fun priceLabel(price: AzphaltRepository.RepoPrice?): String {
        if (price == null || price.amountCents <= 0) return "Free"
        val whole = price.amountCents / 100
        val cents = price.amountCents % 100
        val amount = "$whole.${cents.toString().padStart(2, '0')}"
        return if (price.currency == "USD") "$$amount" else "$amount ${price.currency}"
    }
}
