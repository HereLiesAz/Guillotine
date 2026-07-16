package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Parsed JSON-RPC 2.0 request. */
data class JsonRpcRequest(
    val id: Any?,
    val method: String,
    val params: JSONObject?,
)

// A null id must serialize as JSON `"id": null` (required by JSON-RPC 2.0, e.g. for parse-error
// responses). org.json DROPS a key whose value is a Kotlin null, so map it to JSONObject.NULL.
fun jsonRpcResult(id: Any?, result: JSONObject): JSONObject = JSONObject().apply {
    put("jsonrpc", "2.0")
    put("id", id ?: JSONObject.NULL)
    put("result", result)
}

fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
    put("jsonrpc", "2.0")
    put("id", id ?: JSONObject.NULL)
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

/** Shorthand for a JSON schema integer property. */
fun intProp(desc: String = ""): JSONObject = JSONObject().apply {
    put("type", "integer")
    if (desc.isNotEmpty()) put("description", desc)
}

/** Shorthand for a JSON schema number (float) property. */
fun numberProp(desc: String = ""): JSONObject = JSONObject().apply {
    put("type", "number")
    if (desc.isNotEmpty()) put("description", desc)
}

/** Shorthand for a JSON schema boolean property. */
fun boolProp(desc: String = ""): JSONObject = JSONObject().apply {
    put("type", "boolean")
    if (desc.isNotEmpty()) put("description", desc)
}
