package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Parsed JSON-RPC 2.0 request. */
data class JsonRpcRequest(
    val id: Any?,
    val method: String,
    val params: JSONObject?,
)

fun jsonRpcResult(id: Any?, result: JSONObject): JSONObject = JSONObject().apply {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
    put("jsonrpc", "2.0")
    put("id", id)
    put("error", JSONObject().apply { put("code", code); put("message", message) })
}

fun toolDefinition(name: String, description: String, inputSchema: JSONObject): JSONObject =
    JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }

/** Shorthand for a JSON schema string property. */
fun stringProp(desc: String = ""): JSONObject = JSONObject().apply {
    put("type", "string")
    if (desc.isNotEmpty()) put("description", desc)
}
