package com.hereliesaz.guillotine.azphalt

/**
 * The host contract for running a `code`-kind azphalt extension (azphalt jobs #2–#5). A code extension
 * ships an entry module (`entry`, `runtime` = `js` | `wasm`) that the host runs in a **sandbox**, granting
 * only the [AzpManifest.capabilities] the user approved. Guillotine exchanges JSON with it — never media,
 * the engine, the filesystem, sensors, or the network (azphalt's "never-list").
 *
 * This file is the **capability boundary and runtime seam**, which are real and enforced. The actual
 * sandbox — a WASM engine hosting QuickJS-in-WASM for `js`, or a WASM module against the host ABI — is
 * not bundled in this build, so the shipped [runtime] is [Unavailable]: it refuses to execute rather than
 * pretend. Capability gating ([missingGrants]) is live now, so when a real engine is dropped in behind
 * this interface it is already least-privilege by construction.
 */
interface AzpCodeRuntime {

    /** What the user granted this package: the subset of requested capabilities they approved. */
    data class Grant(val capabilities: Set<String>)

    sealed class Outcome {
        /** The extension ran and returned this JSON result. */
        data class Ok(val resultJson: String) : Outcome()
        /** The extension needed [capability], which wasn't granted. */
        data class Denied(val capability: String) : Outcome()
        /** No sandbox is available to run code extensions in this build. */
        data class Unavailable(val reason: String) : Outcome()
        /** The sandbox failed (trap, timeout, bad module, malformed I/O). */
        data class Error(val message: String) : Outcome()
    }

    /**
     * Run [pkg]'s entry module with [inputJson], limited to [grant]. Implementations MUST refuse a
     * capability the extension uses that isn't in [grant] (see [missingGrants]).
     */
    fun run(pkg: AzpPackage.Loaded, inputJson: String, grant: Grant): Outcome

    companion object {
        /** The capabilities [manifest] requests that aren't in [granted] — must be empty before running. */
        fun missingGrants(manifest: AzpManifest, granted: Set<String>): List<String> =
            manifest.capabilities.filterNot { it in granted }

        /** True when every capability [manifest] requests has been [granted]. */
        fun fullyGranted(manifest: AzpManifest, granted: Set<String>): Boolean =
            missingGrants(manifest, granted).isEmpty()
    }
}

/**
 * The shipped [AzpCodeRuntime] until a WASM sandbox is bundled: it verifies the code kind and grants,
 * then honestly reports that no engine is available rather than faking a result. This keeps `code`
 * packages installable and their capabilities user-approvable while the sandbox (jobs #2–#5) is built.
 */
object UnavailableAzpCodeRuntime : AzpCodeRuntime {
    override fun run(pkg: AzpPackage.Loaded, inputJson: String, grant: AzpCodeRuntime.Grant): AzpCodeRuntime.Outcome {
        if (!pkg.manifest.isCode) {
            return AzpCodeRuntime.Outcome.Error("not a code extension (kind=${pkg.manifest.kind})")
        }
        AzpCodeRuntime.missingGrants(pkg.manifest, grant.capabilities).firstOrNull()?.let {
            return AzpCodeRuntime.Outcome.Denied(it)
        }
        return AzpCodeRuntime.Outcome.Unavailable(
            "code extensions require a WASM sandbox, which is not bundled in this build",
        )
    }
}
