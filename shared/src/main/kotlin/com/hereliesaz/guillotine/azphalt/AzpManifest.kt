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
    /** `asset` | `code` | `mixed` | `app` | `mcp` | `pack`. */
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
    /**
     * Reverse-DNS host-app ids this package is scoped to (e.g. `["com.hereliesaz.guillotine"]`).
     * Empty/absent = **global** (offered to every conforming host). A store filters on it — see
     * [targetsApp].
     */
    val targetApps: List<String> = emptyList(),
    /** Static store-card preview (`image` still and/or `clip` motion). See [AzpPreview]. */
    val preview: AzpPreview? = null,
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
    /**
     * Companion-app header (required when `kind == "app"`): how a host installs/invokes an Android app
     * or PWA and the handoffs it offers. Free-form while pre-stable — the host only reads it, never
     * surfaces its own engine to it. See azphalt `spec/extension-manifest.md`.
     */
    val app: JsonElement? = null,
    /**
     * MCP-server header (required when `kind == "mcp"`): how a host reaches a local (`wasi`/native) or
     * `remote` (http/sse) server, its connect-time `inputs`, and the tools/resources/prompts it
     * `offers`. Free-form while pre-stable. See azphalt `spec/extension-manifest.md`.
     */
    val mcp: JsonElement? = null,
) {
    val isAsset: Boolean get() = kind == "asset" || kind == "mixed"
    val isCode: Boolean get() = kind == "code" || kind == "mixed"

    /** A `kind == "app"` companion app (Android app / PWA the host launches to hand a result back). */
    val isApp: Boolean get() = kind == "app"

    /** A `kind == "mcp"` server package (the manifest is a header describing how to reach the server). */
    val isMcp: Boolean get() = kind == "mcp"

    /**
     * Whether this package is offered to the host app [hostId]: true when it declares no [targetApps]
     * (global) or lists [hostId] among them. A store should hide packages scoped to other hosts.
     */
    fun targetsApp(hostId: String): Boolean = targetApps.isEmpty() || targetApps.contains(hostId)
}

/**
 * A package's static store-card preview (azphalt `spec/extension-manifest.md`). Each field is an
 * in-package path (into the `.azp`) or an `https:` URL. [image] is a still (LUT swatch, brush stroke,
 * shader still); [clip] an optional short motion preview for time-based packages.
 */
@Serializable
data class AzpPreview(
    val image: String? = null,
    val clip: String? = null,
)

/**
 * A contributed asset inside a `.azp` — a traditional asset (`brush`/`lut`/`shader`/`transition`/…)
 * or an **AI model** (`onnx` | `tflite` | `litert` | `sherpa-bundle`). Per spec/extension-manifest.md.
 *
 * A large asset (e.g. a multi-hundred-MB model) may be shipped **out of band**: [path] is `""` and
 * [remoteUrl] + [checksum] (+ [byteSize]) point at a file the host downloads with its own resumable
 * downloader (the "VSCode header pattern"). Exactly one of [path] (bundled) / [remoteUrl] (remote)
 * carries the bytes.
 */
@Serializable
data class AzpAsset(
    /** Asset format — e.g. `lut`, `shader`, `transition`, or a model type `onnx`/`tflite`/`sherpa-bundle`. */
    val type: String,
    /** Path into the package's `/assets` when bundled; `""` when the bytes are remote (see [remoteUrl]). */
    val path: String,
    /** Semantic role for routing (e.g. `subject-segmentation`, `speech-to-text`, `depth`). */
    val role: String? = null,
    /** URL to fetch when the asset is too large to bundle ([path] == `""`). */
    val remoteUrl: String? = null,
    /** SHA-256 of the remote file (`sha256-<hex>` or bare hex) — verified after download. */
    val checksum: String? = null,
    /** Size in bytes of the remote file, so a host can pre-allocate / show progress. */
    val byteSize: Long? = null,
    /** Optional host-rendered control schema (see spec/ui-schema.md). */
    val ui: String? = null,
    /** Normalized, host-neutral settings (e.g. a shader's declared inputs). Free-form. */
    val params: JsonElement? = null,
    /** Marketplace filter tags. */
    val tags: List<String> = emptyList(),
)
