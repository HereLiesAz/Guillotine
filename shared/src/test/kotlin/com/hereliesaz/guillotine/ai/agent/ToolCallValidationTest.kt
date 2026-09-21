package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpToolsSurface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallValidationTest {

    private class FakeTools : McpToolsSurface {
        var called = false

        override fun definitions(): JSONArray = JSONArray().put(
            JSONObject()
                .put("name", "analyze_clip_with_reference")
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("clip_id", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("clip_id")),
                ),
        )

        override fun call(name: String, args: JSONObject): JSONObject {
            called = true
            return JSONObject().put("ok", true)
        }

        override fun resourceDefinitions(): JSONArray = JSONArray()

        override fun readResource(uri: String): JSONObject = JSONObject()
    }

    @Test
    fun missingRequiredClipIdFailsBeforeDispatch() {
        val tools = FakeTools()

        val outcome = callTool(tools, "analyze_clip_with_reference", JSONObject())

        assertTrue(outcome.isError)
        assertTrue(outcome.content().contains("Missing required argument: clip_id"))
        assertFalse(tools.called)
    }

    @Test
    fun validRequiredClipIdDispatchesNormally() {
        val tools = FakeTools()

        val outcome = callTool(
            tools,
            "analyze_clip_with_reference",
            JSONObject().put("clip_id", "clip-123"),
        )

        assertFalse(outcome.isError)
        assertTrue(tools.called)
    }
}
