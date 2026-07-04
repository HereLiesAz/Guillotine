package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject

interface McpToolsSurface {
    fun definitions(): JSONArray
    fun call(name: String, args: JSONObject): JSONObject
    fun resourceDefinitions(): JSONArray
    fun readResource(uri: String): JSONObject
}
