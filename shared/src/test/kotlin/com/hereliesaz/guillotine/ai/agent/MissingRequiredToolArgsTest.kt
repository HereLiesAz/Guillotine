package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpToolsSurface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MissingRequiredToolArgsTest {

    private val tools = object : McpToolsSurface {
        private fun def(name: String) = JSONObject().put("name", name).put(
            "inputSchema",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("clip_id", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("clip_id")),
        )
        override fun definitions(): JSONArray = JSONArray().put(def("select_clip")).put(def("split_clip"))
        override fun call(name: String, args: JSONObject): JSONObject = JSONObject()
        override fun resourceDefinitions(): JSONArray = JSONArray()
        override fun readResource(uri: String): JSONObject = JSONObject()
    }

    @Test fun blankClearsSelectionForSelectClip() {
        assertEquals(emptyList<String>(), missingRequiredToolArgs(tools, "select_clip", JSONObject().put("clip_id", "")))
    }

    @Test fun blankStillMissingElsewhere() {
        assertEquals(listOf("clip_id"), missingRequiredToolArgs(tools, "split_clip", JSONObject().put("clip_id", " ")))
    }

    @Test fun absentOrNullIsMissingEvenForSelectClip() {
        assertEquals(listOf("clip_id"), missingRequiredToolArgs(tools, "select_clip", JSONObject()))
        assertEquals(listOf("clip_id"), missingRequiredToolArgs(tools, "select_clip", JSONObject().put("clip_id", JSONObject.NULL)))
    }
}
