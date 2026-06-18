package com.hereliesaz.guillotine.mcp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Embedded MCP server on port [port]. External AI tools/assistants connect via HTTP
 * POST to /mcp with JSON-RPC 2.0 bodies. GET /health is a simple liveness check.
 *
 * Bound to loopback (127.0.0.1) only: the server exposes unauthenticated read/write control
 * of the editor, so it must not be reachable from other devices on the network. Tools on a
 * dev machine reach it via `adb forward tcp:6274 tcp:6274`.
 */
class McpServer(port: Int = 6274) : NanoHTTPD("127.0.0.1", port) {

    private var tools: McpTools? = null

    fun startServer(tools: McpTools) {
        this.tools = tools
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri == "/health" && session.method == Method.GET ->
            ok("""{"status":"ok"}""")

        session.uri == "/mcp" && session.method == Method.POST ->
            handleMcp(session)

        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun handleMcp(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return ok(jsonRpcError(null, -32700, "Parse error").toString())

        val req = JsonRpcRequest(
            id = json.opt("id"),
            method = json.optString("method"),
            params = json.optJSONObject("params"),
        )
        val result = dispatch(req)
        return ok(result.toString())
    }

    private fun dispatch(req: JsonRpcRequest): JSONObject {
        val t = tools ?: return jsonRpcError(req.id, -32603, "Server not ready")
        return when (req.method) {
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
                put("tools", t.definitions())
            })

            "tools/call" -> {
                val name = req.params?.optString("name")
                    ?: return jsonRpcError(req.id, -32602, "Missing tool name")
                val args = req.params.optJSONObject("arguments") ?: JSONObject()
                try {
                    val res = t.call(name, args)
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

            "resources/list" -> jsonRpcResult(req.id, JSONObject().apply {
                put("resources", t.resourceDefinitions())
            })

            "resources/read" -> {
                val uri = req.params?.optString("uri")
                    ?: return jsonRpcError(req.id, -32602, "Missing uri")
                try {
                    val content = t.readResource(uri)
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

            else -> jsonRpcError(req.id, -32601, "Method not found: ${req.method}")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun ok(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json)
}
