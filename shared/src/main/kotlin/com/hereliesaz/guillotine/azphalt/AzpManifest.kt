package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The azphalt extension manifest (`manifest.json`) — the UTF-8 JSON at the root of every `.azp`.
 *
 * Mirrors the normative spec (azphalt `spec/extension-manifest.md`, format version `0.1`). Required
 * fields have no defaults, so a manifest missing one fails to parse (which the loader treats as an
 * invalid package). Free-form, still-evolving sub-trees (`contributes`, per-asset `params`) are kept
 * as raw [JsonElement] rather than over-modeled while the spec is pre-stable.
 *
 * This is the *host* side of the standard — see [AzpPackage] for load + verify. It only ever reads an
 * extension's declarations; it never surfaces Guillotine's own engine to extension code.
 */
@Serializable
data class AzpManifest(
    /** Format version, e.g. `"0.1"`. Marks the file as an azphalt manifest. */
    val azphalt: String,
    /** Reverse-DNS, globally unique — e.g. `com.hereliesaz.halftone`. */
    val id: String,
    val name: String,
    /** Semver of the package itself. */
    val version: String,
    /** `asset` | `code` | `mixed`. */
    val kind: String,
    /** SPDX license id. */
    val license: String,
    /** Min host API version, e.g. `">=0.1"`. */
    val compat: String,
    /** Payload path → `sha256-<hex>` digest (integrity; see spec/package-format.md). Required. */
    val files: Map<String, String>,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    /** Contributed assets (asset/mixed kinds). */
    val assets: List<AzpAsset> = emptyList(),
    /** Code entry module (code/mixed kinds), e.g. `code/main.js`. */
    val entry: String? = null,
    /** `js` (QuickJS-in-WASM) | `wasm` (against the host ABI). */
    val runtime: String? = null,
    /** Least-privilege capability list the code needs; the host grants only what's declared here. */
    val capabilities: List<String> = emptyList(),
    /** Extension points the code registers (filters/tools/commands). Free-form while pre-stable. */
    val contributes: JsonElement? = null,
) {
    val isAsset: Boolean get() = kind == "asset" || kind == "mixed"
    val isCode: Boolean get() = kind == "code" || kind == "mixed"
}

/** A contributed asset (brush / lut / pattern / stamp / shader) inside a `.azp`. */
@Serializable
data class AzpAsset(
    /** `brush` | `lut` | `pattern` | `stamp` | `shader`. */
    val type: String,
    /** Path into the package's `/assets`. */
    val path: String,
    /** Optional host-rendered control schema (see spec/ui-schema.md). */
    val ui: String? = null,
    /** Normalized, host-neutral settings (e.g. a shader's declared inputs). Free-form. */
    val params: JsonElement? = null,
)
