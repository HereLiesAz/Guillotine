package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.platform.ModelResolver
import com.hereliesaz.guillotine.ui.ActivityLog
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Front controller for every assistant brain (local or cloud).
 *
 * The tiny bundled router sees a compact, live view of the MCP catalog and delegates the request to a
 * narrow set of capabilities. The real planner then gets only those tool definitions. This replaces
 * the impossible approach of hand-writing a workflow for every way a user might phrase an edit.
 *
 * If routing fails, nothing is bricked: the delegate receives the full tool surface exactly as before.
 */
class DelegatingAgentBackend(
    private val context: Context,
    private val settings: AiSettings,
    private val delegate: AgentBackend,
) : AgentBackend {

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) {
        // Preserve the deliberately instant dead-air fast path; making it wait for a router would undo
        // the fix that stopped Phi-4 from loading for "cut the boring parts".
        if (PromptCoach.isBoringCutRequest(instruction)) {
            delegate.run(instruction, tools, onEvent)
            return
        }

        try {
            val definitions = tools.definitions()
            if (definitions.length() <= MIN_CATALOG_FOR_ROUTING) {
                delegate.run(instruction, tools, onEvent)
                return
            }

            ActivityLog.progress("AI · router: choosing models/tools…")
            val status = modelStatus()
            val batches = TaskDelegationRouter.definitionBatches(definitions)
            val prompts = batches.map { batch ->
                TaskDelegationRouter.prompt(
                    instruction = instruction,
                    definitions = batch,
                    modelStatus = status,
                    maxTools = ROUTER_TOOLS_PER_BATCH,
                )
            }
            val raws = TaskRouterLocalModel.routeMany(context, prompts)
            val partialRoutes = batches.indices.mapNotNull { index ->
                TaskDelegationRouter.parse(
                    raw = raws.getOrNull(index),
                    definitions = batches[index],
                    maxTools = ROUTER_TOOLS_PER_BATCH,
                )
            }

            if (partialRoutes.isEmpty()) {
                ActivityLog.info("AI · router unavailable/uncertain — using full planner catalog.")
                delegate.run(instruction, tools, onEvent)
                return
            }

            val routedTools = partialRoutes
                .flatMap { it.tools }
                .distinct()
                .take(MAX_ROUTED_SPECIALIST_TOOLS)
            if (routedTools.isEmpty()) {
                ActivityLog.info("AI · router found no confident capability — using full planner catalog.")
                delegate.run(instruction, tools, onEvent)
                return
            }
            val routedRoles = partialRoutes.flatMap { it.modelRoles }.distinct().take(6)
            val reasons = partialRoutes.map { it.reason }.filter { it.isNotBlank() }.distinct().take(2)

            val allowed = LinkedHashSet<String>().apply {
                addAll(TaskDelegationRouter.UNIVERSAL_TOOLS)
                addAll(routedTools)
            }
            val roleText = routedRoles.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "general"
            val why = reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { " · $it" }.orEmpty()
            ActivityLog.info(
                "AI · router: $roleText → ${routedTools.joinToString(", ")}$why",
            )

            delegate.run(
                instruction,
                RoutedToolsSurface(tools, allowed),
                onEvent,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ActivityLog.info(
                "AI · router failed (${e.message ?: e::class.java.simpleName}) — using full planner catalog.",
            )
            delegate.run(instruction, tools, onEvent)
        }
    }

    override suspend fun complete(prompt: String): String? = delegate.complete(prompt)

    override fun reset() = delegate.reset()

    private fun modelStatus(): String = buildString {
        fun slot(role: String, property: String, fallback: String? = null) {
            val path = ModelResolver.resolve(context, settings, property)
            append(role).append(": ")
            when {
                path.isNotBlank() -> append("available")
                fallback != null -> append(fallback)
                else -> append("not configured")
            }
            appendLine()
        }

        appendLine("ROUTER: bundled 135M routing model available")
        slot("ASSISTANT_LLM", "agentModelPath")
        slot("RECOGNITION", "idEmbedModelPath", "bundled fallback available")
        slot("FACE", "faceEmbedModelPath", "general recognition fallback available")
        slot("ASR", "asrModelPath")
        slot("TTS", "ttsModelPath")
        slot("VLM", "vlmModelPath")
        slot("AUDIO_EVENT", "audioEventModelPath")
        slot("DENOISE", "denoiseModelPath")
        slot("STEM", "stemModelPath")
        slot("DIARIZE_SEG", "diarizeSegModelPath")
        slot("DIARIZE_EMBED", "diarizeEmbedModelPath")
        slot("SUPERRES", "effect_superres")
        slot("DEPTH", "effect_depth")
        slot("LOWLIGHT", "effect_lowlight")
        slot("STYLE", "effect_style")
        append("LOCAL_VISION: bundled on-device vision available").appendLine()

        val cloud = settings.provider !in setOf(AiProviderType.LOCAL, AiProviderType.MLKIT) &&
            settings.keyFor(settings.provider).isNotBlank()
        append("CLOUD_PLANNER: ").append(if (cloud) "configured" else "not configured")
    }.trim()

    private class RoutedToolsSurface(
        private val upstream: McpToolsSurface,
        private val allowed: Set<String>,
    ) : McpToolsSurface {
        override fun definitions(): JSONArray {
            val all = upstream.definitions()
            return JSONArray().apply {
                for (i in 0 until all.length()) {
                    val d = all.optJSONObject(i) ?: continue
                    if (d.optString("name") in allowed) put(d)
                }
            }
        }

        // Calls are forwarded instead of hard-blocked. The planner only sees routed definitions, but
        // a provider that remembers a valid prior tool name can still recover instead of dead-ending.
        override fun call(name: String, args: JSONObject): JSONObject = upstream.call(name, args)
        override fun resourceDefinitions(): JSONArray = upstream.resourceDefinitions()
        override fun readResource(uri: String): JSONObject = upstream.readResource(uri)
        override fun cancel(requestId: Any?) = upstream.cancel(requestId)
    }

    private companion object {
        const val MIN_CATALOG_FOR_ROUTING = 12
        const val ROUTER_TOOLS_PER_BATCH = 2
        const val MAX_ROUTED_SPECIALIST_TOOLS = 12
    }
}
