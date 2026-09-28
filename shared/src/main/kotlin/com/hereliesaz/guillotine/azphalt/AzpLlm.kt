package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Host side of azphalt `kind: "llm"` (azphalt `spec/llm.md`): an **off-device language model** installed
 * from the store, offered *in addition to* the built-in providers and the on-device brain.
 *
 * Two ways to reach one, per the package's `endpoint.protocols`:
 * - **`openai-chat`** — the host POSTs to `{baseUrl}/chat/completions` through the OpenAI-compatible
 *   agent backend, and is itself the rolling-delimiter translator ([AzpLlmDelimiters]). This can drive
 *   the editor ([AzpLlmBrain]).
 * - **`github-actions-runner`** — the package is installed into the user's private GitHub Actions
 *   sandbox and each task is a workflow run ([AzpLlmSandbox]). Minutes per call and no tool calling, so
 *   it serves background text jobs, never the interactive brain. `sandbox-weights` packages only speak
 *   this protocol.
 *
 * Text only either way: media never leaves the device. `setup` never runs on the device; it runs in the
 * sandbox (spec § Setup).
 */
object AzpLlm {

    /** A connect-time value the host prompts for (a provider key). Never stored in the package. */
    data class Input(
        val id: String,
        val description: String,
        val password: Boolean,
        val optional: Boolean,
    )

    /** Prompt-handling disclosure the spec requires a host to surface before use. */
    data class DataHandling(
        /** `not-retained` | `logged` | `may-train` | `unknown`. */
        val prompts: String,
        /** Whether [Endpoint.defaultModel] always names the same weights (a router is `false`). */
        val modelPinned: Boolean,
        val operator: String?,
        val terms: String?,
    )

    /** An Actions secret the sandbox gets from an input (`setup.secrets[]`). */
    data class Secret(val name: String, val input: String)

    /** A usable `llm` package: everything the agent factory, the sandbox and Settings need. */
    data class Endpoint(
        val packageId: String,
        val name: String,
        val version: String,
        /** `endpoint` | `sandbox-weights`. */
        val tier: String,
        /** Declares `openai-chat`: the host can call [baseUrl] directly (and drive the editor with it). */
        val supportsChat: Boolean,
        /** Declares `github-actions-runner`: the host can run it in the private sandbox. */
        val supportsRunner: Boolean,
        /** `https://` base when [supportsChat]; requests go to [chatCompletionsUrl]. Blank otherwise. */
        val baseUrl: String,
        val defaultModel: String,
        /** `none` | `optional-bearer` | `required-bearer`. */
        val auth: String,
        /** The input carrying the bearer key; null when [auth] is `none`. */
        val keyInput: Input?,
        val dataHandling: DataHandling,
        val role: String?,
        /** Every declared input (keys the host prompts for); [keyInput] is among them when bearer auth. */
        val inputs: List<Input> = emptyList(),
        val secrets: List<Secret> = emptyList(),
        /** Permissions the one-time setup token needs (`setup.requires.githubToken`). */
        val setupTokenPermissions: List<String> = emptyList(),
        /** Every URL setup downloads, weights included. */
        val fetches: List<String> = emptyList(),
        /** Total bytes of weights the sandbox downloads and caches (0 for the endpoint tier). */
        val weightsBytes: Long = 0,
        /** The weights' own licence, one line, when declared (`weights.modelLicense`). */
        val modelLicense: String? = null,
    ) {
        /** Prompts stay in the user's own sandbox (no third-party model operator). */
        val runsInSandbox: Boolean get() = tier == TIER_SANDBOX
        val chatCompletionsUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"
        val keyRequired: Boolean get() = auth == AUTH_REQUIRED

        /** One line for Settings / the install notice. */
        val disclosure: String
            get() = buildString {
                if (runsInSandbox) {
                    append("Runs in your own private GitHub sandbox; prompts reach no model operator.")
                    if (weightsBytes > 0) append(" Downloads ${weightsBytes / 1_000_000} MB of weights there.")
                    modelLicense?.let { append(" Model licence: $it.") }
                    return@buildString
                }
                append(dataHandling.operator?.let { "Run by $it. " } ?: "Run by a third party. ")
                append(
                    when (dataHandling.prompts) {
                        "not-retained" -> "Prompts are not retained."
                        "logged" -> "Prompts are logged."
                        "may-train" -> "Prompts may be used for training."
                        else -> "What happens to prompts is not stated."
                    },
                )
                if (!dataHandling.modelPinned) append(" The model behind it may change without notice.")
            }
    }

    sealed interface Parsed {
        data class Ok(val endpoint: Endpoint) : Parsed

        /** A valid-looking `llm` package this host can't drive, or a malformed one; [reason] says which. */
        data class Unsupported(val reason: String) : Parsed
    }

    const val TIER_SANDBOX = "sandbox-weights"
    const val AUTH_NONE = "none"
    const val AUTH_OPTIONAL = "optional-bearer"
    const val AUTH_REQUIRED = "required-bearer"
    private val AUTH_MODES = setOf(AUTH_NONE, AUTH_OPTIONAL, AUTH_REQUIRED)
    private val PROMPT_MODES = setOf("not-retained", "logged", "may-train", "unknown")

    /** Parse and validate [manifest]'s `llm` block against the endpoint-tier rules of `spec/llm.md`. */
    fun parse(manifest: AzpManifest): Parsed {
        if (!manifest.isLlm) return Parsed.Unsupported("not an llm package")
        val llm = manifest.llm as? JsonObject ?: return Parsed.Unsupported("the llm block is missing")
        if (manifest.entry != null || manifest.runtime != null || manifest.capabilities.isNotEmpty() ||
            manifest.assets.isNotEmpty() || manifest.app != null || manifest.mcp != null
        ) {
            return Parsed.Unsupported("an llm package may not also carry code, assets, app or mcp blocks")
        }
        val tier = llm.str("tier")
        if (tier != "endpoint" && tier != TIER_SANDBOX) return Parsed.Unsupported("unknown tier “${tier.orEmpty()}”")
        val weights = llm["weights"] as? JsonObject
        if (tier == "endpoint" && llm["weights"] != null) {
            return Parsed.Unsupported("an endpoint-tier package may not declare weights")
        }
        if (tier == TIER_SANDBOX && weights == null) return Parsed.Unsupported("a sandbox-weights package must declare weights")

        val setup = llm["setup"] as? JsonObject ?: return Parsed.Unsupported("the setup block is missing")
        val sandbox = setup.str("sandbox")
        if (sandbox != "github-actions") return Parsed.Unsupported("unknown sandbox “${sandbox.orEmpty()}”")

        val endpoint = llm["endpoint"] as? JsonObject ?: return Parsed.Unsupported("the endpoint block is missing")
        val protocols = (endpoint["protocols"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val chat = "openai-chat" in protocols
        val runner = "github-actions-runner" in protocols
        if (!chat && !runner) return Parsed.Unsupported("it declares no protocol this host speaks")
        if (tier == TIER_SANDBOX && chat) return Parsed.Unsupported("sandbox-weights permits only github-actions-runner")
        val baseUrl = endpoint.str("baseUrl").orEmpty()
        if (chat && !baseUrl.startsWith("https://")) {
            return Parsed.Unsupported("openai-chat needs an https:// baseUrl")
        }
        val defaultModel = endpoint.str("defaultModel")
            ?: return Parsed.Unsupported("the endpoint names no defaultModel")
        val auth = endpoint.str("auth") ?: AUTH_NONE
        if (auth !in AUTH_MODES) return Parsed.Unsupported("unknown auth mode “$auth”")

        val inputs = (llm["inputs"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            Input(
                id = id,
                description = o.str("description").orEmpty(),
                password = o.bool("password") ?: false,
                optional = o.bool("optional") ?: false,
            )
        }
        val keyInput = if (auth == AUTH_NONE) {
            null
        } else {
            val ref = endpoint.str("authInput")
                ?: return Parsed.Unsupported("a bearer auth mode needs an authInput")
            inputs.firstOrNull { it.id == ref }
                ?: return Parsed.Unsupported("authInput “$ref” is not a declared input")
        }
        val secrets = (setup["secrets"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Secret(o.str("name") ?: return@mapNotNull null, o.str("input") ?: return@mapNotNull null)
        }
        if (secrets.any { s -> inputs.none { it.id == s.input } }) {
            return Parsed.Unsupported("a setup secret names an undeclared input")
        }

        val dh = llm["dataHandling"] as? JsonObject
        if (dh == null && tier == "endpoint") return Parsed.Unsupported("an endpoint-tier package must declare dataHandling")
        val prompts = dh?.str("prompts")?.takeIf { it in PROMPT_MODES }
            ?: if (dh == null) "not-retained" else return Parsed.Unsupported("dataHandling.prompts is missing or unknown")

        val weightFiles = (weights?.get("files") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val license = (weights?.get("modelLicense") as? JsonObject)?.let { l ->
            listOfNotNull(l.str("spdx"), (l["commercialUse"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "commercial use allowed" else "no commercial use" })
                .joinToString(", ").ifBlank { null }
        }
        val fetches = (setup["fetches"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("url") } +
            weightFiles.mapNotNull { it.str("remoteUrl") }

        return Parsed.Ok(
            Endpoint(
                packageId = manifest.id,
                name = manifest.name,
                version = manifest.version,
                tier = tier,
                supportsChat = chat,
                supportsRunner = runner,
                baseUrl = baseUrl,
                defaultModel = defaultModel,
                auth = auth,
                keyInput = keyInput,
                dataHandling = DataHandling(
                    prompts = prompts,
                    modelPinned = dh?.bool("modelPinned") ?: (tier == TIER_SANDBOX),
                    operator = dh?.str("operator"),
                    terms = dh?.str("terms"),
                ),
                role = llm.str("role"),
                inputs = inputs,
                secrets = secrets,
                setupTokenPermissions = ((setup["requires"] as? JsonObject)?.get("githubToken") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                fetches = fetches,
                weightsBytes = weightFiles.sumOf { (it["byteSize"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L },
                modelLicense = license,
            ),
        )
    }

    /**
     * Usable `llm` packages installed in [extensionsDir] for [hostAppId], name-sorted. Blocking
     * I/O — call off the main thread. Packages were integrity-checked at install; unreadable or
     * unsupported ones are skipped.
     */
    fun installed(extensionsDir: File, hostAppId: String): List<Endpoint> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".azp") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val manifest = AzpPackage.read(f.readBytes()).manifest
                if (!manifest.isLlm || !manifest.targetsApp(hostAppId)) return@runCatching null
                (parse(manifest) as? Parsed.Ok)?.endpoint
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    /** The installed `.azp` file of [packageId], for the sandbox install (which commits its payload). */
    fun packageFile(extensionsDir: File, hostAppId: String, packageId: String): File? =
        extensionsDir.listFiles { _, name -> name.endsWith(".azp") }?.firstOrNull { f ->
            runCatching {
                val m = AzpPackage.read(f.readBytes()).manifest
                m.isLlm && m.id == packageId && m.targetsApp(hostAppId)
            }.getOrDefault(false)
        }

    /** The installed endpoint with [packageId], or null when it's gone or unusable. */
    fun find(extensionsDir: File, hostAppId: String, packageId: String): Endpoint? =
        if (packageId.isBlank()) null else installed(extensionsDir, hostAppId).firstOrNull { it.packageId == packageId }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
