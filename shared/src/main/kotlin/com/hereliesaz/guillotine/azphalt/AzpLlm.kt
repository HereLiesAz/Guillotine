package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Host side of azphalt `kind: "llm"` (azphalt `spec/llm.md`, *Proposed*): an **off-device language
 * model** installed from the store and offered as an *additional* assistant brain. It never replaces a
 * built-in provider or the on-device brain; the user picks it explicitly.
 *
 * Only the **`endpoint` tier over `openai-chat`** is consumed: the host POSTs to
 * `{baseUrl}/chat/completions` through the existing OpenAI-compatible backend. That path is text-only,
 * so the invariant holds — media never leaves the device; the brain sees the prompt, tool definitions
 * and tool results, exactly like a BYO-key provider.
 *
 * Not consumed (the package installs, but [parse] reports it [Parsed.Unsupported]):
 * - `sandbox-weights` — needs a GitHub Actions sandbox Guillotine doesn't provision.
 * - an `endpoint` package declaring only `github-actions-runner` — same reason.
 *
 * `setup` is never executed. The spec forbids running it on-device, and the `openai-chat` protocol
 * doesn't need it: the host talks to `baseUrl` directly.
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

    /** A usable `endpoint`-tier model: everything the agent factory and Settings need. */
    data class Endpoint(
        val packageId: String,
        val name: String,
        val version: String,
        /** `https://` base; requests go to [chatCompletionsUrl]. */
        val baseUrl: String,
        val defaultModel: String,
        /** `none` | `optional-bearer` | `required-bearer`. */
        val auth: String,
        /** The input carrying the bearer key; null when [auth] is `none`. */
        val keyInput: Input?,
        val dataHandling: DataHandling,
        val role: String?,
    ) {
        val chatCompletionsUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"
        val keyRequired: Boolean get() = auth == AUTH_REQUIRED

        /** One line for Settings / the install notice. */
        val disclosure: String
            get() = buildString {
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
        when (val tier = llm.str("tier")) {
            "endpoint" -> Unit
            "sandbox-weights" -> return Parsed.Unsupported(
                "it runs its model in a GitHub Actions sandbox, which Guillotine doesn't provision",
            )
            else -> return Parsed.Unsupported("unknown tier “${tier.orEmpty()}”")
        }
        if (llm["weights"] != null) return Parsed.Unsupported("an endpoint-tier package may not declare weights")

        val endpoint = llm["endpoint"] as? JsonObject ?: return Parsed.Unsupported("the endpoint block is missing")
        val protocols = (endpoint["protocols"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if ("openai-chat" !in protocols) {
            return Parsed.Unsupported("it only speaks github-actions-runner, which needs a sandbox Guillotine doesn't provision")
        }
        val baseUrl = endpoint.str("baseUrl")
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
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

        val dh = llm["dataHandling"] as? JsonObject
            ?: return Parsed.Unsupported("an endpoint-tier package must declare dataHandling")
        val prompts = dh.str("prompts")?.takeIf { it in PROMPT_MODES }
            ?: return Parsed.Unsupported("dataHandling.prompts is missing or unknown")

        return Parsed.Ok(
            Endpoint(
                packageId = manifest.id,
                name = manifest.name,
                version = manifest.version,
                baseUrl = baseUrl,
                defaultModel = defaultModel,
                auth = auth,
                keyInput = keyInput,
                dataHandling = DataHandling(
                    prompts = prompts,
                    modelPinned = dh.bool("modelPinned") ?: false,
                    operator = dh.str("operator"),
                    terms = dh.str("terms"),
                ),
                role = llm.str("role"),
            ),
        )
    }

    /**
     * Usable endpoint-tier models installed in [extensionsDir] for [hostAppId], name-sorted. Blocking
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

    /** The installed endpoint with [packageId], or null when it's gone or unusable. */
    fun find(extensionsDir: File, hostAppId: String, packageId: String): Endpoint? =
        if (packageId.isBlank()) null else installed(extensionsDir, hostAppId).firstOrNull { it.packageId == packageId }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
