package com.hereliesaz.guillotine.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDelegationRouterTest {

    private fun tool(name: String, description: String) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject().put("type", "object"))

    @Test
    fun genericCandidateRetrievalFindsRelevantToolsWithoutPromptWorkflow() {
        val defs = JSONArray()
            .put(tool("get_timeline", "Read the current timeline"))
            .put(tool("apply_image_effect", "Upscale, depth, style, or brighten the current frame"))
            .put(tool("transcribe_precise", "Accurately transcribe a clip with offline Whisper ASR"))
            .put(tool("separate_stems", "Separate vocals from instrumental audio"))

        val candidates = TaskDelegationRouter.candidateDefinitions(
            "accurately transcribe what the speaker says",
            defs,
            limit = 2,
        )

        val names = (0 until candidates.length()).map { candidates.getJSONObject(it).getString("name") }
        assertTrue("transcribe_precise" in names)
    }

    @Test
    fun productionBatchesCoverLateNonLexicalToolsInsteadOfDroppingThem() {
        val defs = JSONArray()
        repeat(60) { i -> defs.put(tool("tool_$i", "Capability number $i")) }
        defs.put(tool("denoise_clip", "Remove hiss hum and background noise from speech"))

        val batches = TaskDelegationRouter.definitionBatches(defs, batchSize = 16)
        val names = batches.flatMap { batch ->
            (0 until batch.length()).map { batch.getJSONObject(it).getString("name") }
        }

        assertEquals(defs.length(), names.size)
        assertTrue("denoise_clip" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun parserDropsHallucinatedToolsAndKeepsModelRoles() {
        val defs = JSONArray()
            .put(tool("get_timeline", "Read timeline"))
            .put(tool("transcribe_precise", "Offline Whisper transcription"))

        val route = TaskDelegationRouter.parse(
            """{"tools":["transcribe_precise","made_up_tool"],"model_roles":["asr"],"reason":"speech task"}""",
            defs,
        )!!

        assertEquals(listOf("transcribe_precise"), route.tools)
        assertEquals(listOf("ASR"), route.modelRoles)
        assertEquals("speech task", route.reason)
    }
}
