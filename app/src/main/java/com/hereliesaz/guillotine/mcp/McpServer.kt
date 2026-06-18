package com.hereliesaz.guillotine.mcp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Embedded MCP server on port [port]. External AI tools/assistants connect via HTTP
 * POST to /mcp with JSON-RPC 2.0 bodies. GET /health is a simple liveness check.
 *
 * Bound to all interfaces so AI/ML tools can reach it over the network to operate the app —
 * that remote control is the point of the server. Because it grants read/write control of the
 * editor, `/mcp` requires a bearer token (`Authorization: Bearer <token>`, see [McpAuth]);
 * `/health` stays open so liveness checks don't need the secret.
 */
class McpServer(port: Int = 6274) : NanoHTTPD(port) {

    private var tools: McpTools? = null
    private var tokenProvider: (() -> String)? = null

    fun startServer(tools: McpTools, tokenProvider: () -> String) {
        this.tools = tools
        this.tokenProvider = tokenProvider
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri == "/health" && session.method == Method.GET ->
            ok("""{"status":"ok"}""")

        session.uri == "/mcp" && session.method == Method.POST ->
            if (authorized(session)) handleMcp(session) else unauthorized()

        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    /** Constant-time check of the `Authorization: Bearer <token>` header against the live token. */
    private fun authorized(session: IHTTPSession): Boolean {
        // Fail closed: if no token is configured, reject rather than expose the editor.
        val expected = tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return false
        val header = session.headers["authorization"].orEmpty()
        val provided = if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim() else ""
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
    }

    private fun unauthorized(): Response = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED,
        "application/json",
        jsonRpcError(null, -32001, "Unauthorized: missing or invalid bearer token").toString(),
    )

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
