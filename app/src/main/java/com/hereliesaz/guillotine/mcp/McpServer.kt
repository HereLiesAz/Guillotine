package com.hereliesaz.guillotine.mcp

import fi.iki.elonen.NanoHTTPD
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
        val t = tools ?: return ok(jsonRpcError(null, -32603, "Server not ready").toString())
        return ok(McpDispatcher.handle(t, readBody(session)))
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun ok(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json)
}
