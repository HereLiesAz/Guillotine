package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `kind: "llm"` (azphalt `spec/llm.md`): only the endpoint tier over `openai-chat` becomes a brain; every
 * other shape is refused with a reason rather than half-driven.
 */
class AzpLlmTest {

    private val kilo = """
        {
          "tier": "endpoint",
          "inputs": [ { "id": "providerKey", "type": "promptString", "password": true, "optional": true,
                        "description": "Kilo account key" } ],
          "setup": { "sandbox": "github-actions", "script": "setup/setup.sh" },
          "endpoint": { "protocols": ["openai-chat", "github-actions-runner"],
                        "baseUrl": "https://api.kilo.ai/api/gateway/", "defaultModel": "kilo-auto/free",
                        "auth": "optional-bearer", "authInput": "providerKey" },
          "dataHandling": { "prompts": "may-train", "modelPinned": false, "operator": "Kilo Code" },
          "role": "text-generation"
        }
    """

    private fun manifest(llm: String?, kind: String = "llm") = AzpManifest(
        azphalt = "0.1", id = "com.example.llm", name = "Kilo", version = "1.0.0", kind = kind,
        license = "MIT", compat = ">=0.1", files = emptyMap(),
        llm = llm?.let { Json.parseToJsonElement(it) },
    )

    private fun edit(block: (MutableMap<String, Any?>) -> Unit): String {
        val o = Json.parseToJsonElement(kilo).jsonObject.toMutableMap<String, Any?>()
        block(o)
        return JsonObject(o.filterValues { it != null }.mapValues { (_, v) ->
            v as? kotlinx.serialization.json.JsonElement ?: Json.parseToJsonElement(v as String)
        }).toString()
    }

    private fun reason(llm: String?) = (AzpLlm.parse(manifest(llm)) as AzpLlm.Parsed.Unsupported).reason

    @Test fun endpointTierParses() {
        val e = (AzpLlm.parse(manifest(kilo)) as AzpLlm.Parsed.Ok).endpoint
        assertEquals("https://api.kilo.ai/api/gateway/chat/completions", e.chatCompletionsUrl)
        assertEquals("kilo-auto/free", e.defaultModel)
        assertEquals("providerKey", e.keyInput?.id)
        assertFalse(e.keyRequired)
        assertTrue(e.disclosure.contains("Kilo Code"))
        assertTrue(e.disclosure.contains("training"))
        assertTrue(e.disclosure.contains("may change"))
    }

    @Test fun sandboxWeightsIsRefused() {
        assertTrue(reason(edit { it["tier"] = "\"sandbox-weights\"" }).contains("sandbox"))
    }

    @Test fun runnerOnlyEndpointIsRefused() {
        val llm = kilo.replace("\"openai-chat\", ", "")
        assertTrue(reason(llm).contains("github-actions-runner"))
    }

    @Test fun plainHttpIsRefused() {
        assertTrue(reason(kilo.replace("https://api.kilo", "http://api.kilo")).contains("https"))
    }

    @Test fun dataHandlingIsRequired() {
        assertTrue(reason(edit { it["dataHandling"] = null }).contains("dataHandling"))
    }

    @Test fun bearerNeedsADeclaredInput() {
        assertTrue(reason(edit { it["inputs"] = "[]" }).contains("authInput"))
    }

    @Test fun weightsAreForbiddenOnEndpointTier() {
        assertTrue(reason(edit { it["weights"] = "{}" }).contains("weights"))
    }

    @Test fun keylessEndpointHasNoKeyInput() {
        val llm = kilo.replace("\"auth\": \"optional-bearer\", \"authInput\": \"providerKey\"", "\"auth\": \"none\"")
        assertNull((AzpLlm.parse(manifest(llm)) as AzpLlm.Parsed.Ok).endpoint.keyInput)
    }

    @Test fun surfaceIsAssistantBrainOnlyWhenUsable() {
        assertEquals(listOf(AzpInstallSurfaces.Surface.ASSISTANT_BRAIN), AzpInstallSurfaces.of(manifest(kilo)))
        assertEquals(
            listOf(AzpInstallSurfaces.Surface.NONE),
            AzpInstallSurfaces.of(manifest(edit { it["tier"] = "\"sandbox-weights\"" })),
        )
    }

    @Test fun storeOffersLlmPackagesDespiteNoAssetTypes() {
        assertTrue(AzpInstallSurfaces.hasKnownConsumer(emptyList(), "llm"))
        assertFalse(AzpInstallSurfaces.hasKnownConsumer(emptyList(), "mcp"))
    }
}
