package com.hereliesaz.guillotine.mcp

import org.json.JSONObject

/**
 * Transport-agnostic JSON-RPC 2.0 dispatch for the MCP tools. Both the local HTTP server
 * ([McpServer]) and the encrypted Cloudflare relay ([McpRelayClient]) feed request bodies
 * here and get a response body back, so the two transports share identical behavior.
 */
object McpDispatcher {

    /** Parse a JSON-RPC request body and return the JSON-RPC response body. Never throws. */
    fun handle(tools: McpTools, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonRpcError(null, -32700, "Parse error").toString()
        val req = JsonRpcRequest(
            id = json.opt("id"),
            method = json.optString("method"),
            params = json.optJSONObject("params"),
        )
        return dispatch(tools, req).toString()
    }

    private fun dispatch(tools: McpTools, req: JsonRpcRequest): JSONObject = when (req.method) {
        "initialize" -> jsonRpcResult(req.id, JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
                put("resources", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", "guillotine-editor")
                put("version", "1.0.0")
            })
        })

        "tools/list" -> jsonRpcResult(req.id, JSONObject().apply {
            put("tools", tools.definitions())
        })

        "tools/call" -> {
            val name = req.params?.optString("name")
            if (name.isNullOrEmpty()) {
                jsonRpcError(req.id, -32602, "Missing tool name")
            } else {
                val args = req.params.optJSONObject("arguments") ?: JSONObject()
                try {
                    val res = tools.call(name, args)
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject().apply {
                            put("type", "text"); put("text", res.toString(2))
                        }))
                    })
                } catch (e: Exception) {
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject().apply {
                            put("type", "text"); put("text", "Error: ${e.message}")
                        }))
                        put("isError", true)
                    })
                }
            }
        }

        "resources/list" -> jsonRpcResult(req.id, JSONObject().apply {
            put("resources", tools.resourceDefinitions())
        })

        "resources/read" -> {
            val uri = req.params?.optString("uri")
            if (uri.isNullOrEmpty()) {
                jsonRpcError(req.id, -32602, "Missing uri")
            } else {
                try {
                    val content = tools.readResource(uri)
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("contents", org.json.JSONArray().put(JSONObject().apply {
                            put("uri", uri)
                            put("mimeType", "application/json")
                            put("text", content.toString(2))
                        }))
                    })
                } catch (e: Exception) {
                    jsonRpcError(req.id, -32602, "Resource error: ${e.message}")
                }
            }
        }

        else -> jsonRpcError(req.id, -32601, "Method not found: ${req.method}")
    }
}
