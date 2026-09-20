package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AgentEvent
import com.hereliesaz.guillotine.ai.agent.OpenAiAgentBackend
import com.hereliesaz.guillotine.ai.agent.TaskDelegationRouter
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.ui.ActivityLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop counterpart to Android's delegating front controller.
 *
 * qwen3.5:0.8b has ONE role here: routing. It never edits or answers the user. The selected desktop
 * planner (or cloud brain) receives only the routed MCP subset. If the router is missing or uncertain,
 * the planner gets the full catalog and nothing is blocked.
 */
class DesktopDelegatingAgentBackend(
    private val settings: AiSettings,
    private val delegate: AgentBackend,
) : AgentBackend {

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) {
        try {
            val definitions = tools.definitions()
            if (definitions.length() <= MIN_CATALOG_FOR_ROUTING) {
                delegate.run(instruction, tools, onEvent)
                return
            }

            val routerReady = withContext(Dispatchers.IO) {
                DesktopOllama.ensureRunning() &&
                    DesktopOllama.listModels().any { it == DesktopOllama.ROUTER_MODEL }
            }
            if (!routerReady) {
                ActivityLog.info("AI · desktop router not installed — using full planner catalog.")
                delegate.run(instruction, tools, onEvent)
                return
            }

            ActivityLog.progress("AI · desktop router: choosing models/tools…")
            val router = OpenAiAgentBackend(
                apiKey = "ollama",
                endpoint = "http://127.0.0.1:11434/v1/chat/completions",
                model = DesktopOllama.ROUTER_MODEL,
                label = "Ollama router",
            )
            val status = modelStatus()
            val batches = TaskDelegationRouter.definitionBatches(definitions)
            val partialRoutes = buildList {
                for (batch in batches) {
                    val prompt = TaskDelegationRouter.prompt(
                        instruction = instruction,
                        definitions = batch,
                        modelStatus = status,
                        maxTools = ROUTER_TOOLS_PER_BATCH,
                    )
                    val raw = router.complete(prompt)
                    TaskDelegationRouter.parse(
                        raw = raw,
                        definitions = batch,
                        maxTools = ROUTER_TOOLS_PER_BATCH,
                    )?.let(::add)
                }
            }

            val routedTools = partialRoutes
                .flatMap { it.tools }
                .distinct()
                .take(MAX_ROUTED_SPECIALIST_TOOLS)
            if (routedTools.isEmpty()) {
                ActivityLog.info("AI · desktop router uncertain — using full planner catalog.")
                delegate.run(instruction, tools, onEvent)
                return
            }

            val routedRoles = partialRoutes.flatMap { it.modelRoles }.distinct().take(6)
            val allowed = LinkedHashSet<String>().apply {
                addAll(TaskDelegationRouter.UNIVERSAL_TOOLS)
                addAll(routedTools)
            }
            val roleText = routedRoles.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "general"
            ActivityLog.info(
                "AI · desktop router: $roleText → ${routedTools.joinToString(", ")}",
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
                "AI · desktop router failed (${e.message ?: e::class.java.simpleName}) — using full planner catalog.",
            )
            delegate.run(instruction, tools, onEvent)
        }
    }

    override suspend fun complete(prompt: String): String? = delegate.complete(prompt)

    override fun reset() = delegate.reset()

    private fun modelStatus(): String = buildString {
        fun row(role: String, property: String, configured: Boolean = false) {
            val available = configured || ModelResolver.resolve(property).isNotBlank()
            append(role).append(": ").append(if (available) "available" else "not configured").appendLine()
        }

        appendLine("ROUTER: ${DesktopOllama.ROUTER_MODEL} (dedicated)")
        row("ASSISTANT_LLM", "agentModelPath", settings.agentModelPath.isNotBlank())
        row("RECOGNITION", "idEmbedModelPath", settings.idEmbedModelPath.isNotBlank())
        row("FACE", "faceEmbedModelPath", settings.faceEmbedModelPath.isNotBlank())
        row("ASR", "asrModelPath", settings.asrModelPath.isNotBlank() || settings.speechModelPath.isNotBlank())
        row("TTS", "ttsModelPath", settings.ttsModelPath.isNotBlank())
        row("VLM", "vlmModelPath", settings.vlmModelPath.isNotBlank())
        row("AUDIO_EVENT", "audioEventModelPath", settings.audioEventModelPath.isNotBlank())
        row("DENOISE", "denoiseModelPath", settings.denoiseModelPath.isNotBlank())
        row("STEM", "stemModelPath", settings.stemModelPath.isNotBlank())
        row("DIARIZE_SEG", "diarizeSegModelPath", settings.diarizeSegModelPath.isNotBlank())
        row("DIARIZE_EMBED", "diarizeEmbedModelPath", settings.diarizeEmbedModelPath.isNotBlank())
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
