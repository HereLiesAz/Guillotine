package com.hereliesaz.guillotine.mcp

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest

/**
 * Embedded MCP server on port [port]. External AI tools/assistants connect via HTTP
 * POST to /mcp with JSON-RPC 2.0 bodies. GET /health is a simple liveness check.
 *
 * **Bind address.** This server carries a full-control bearer token (see [McpAuth]) over
 * **plaintext** HTTP — no TLS. A prior security review flagged binding it to all interfaces as
 * critical: anyone on the same network segment (open wifi, a compromised peer, an untrusted LAN)
 * can passively sniff the bearer token off a legitimate connection and replay it for full editor
 * control. The ideal fix is TLS with a self-signed cert generated on first run (the port stays
 * reachable over the network, but sniffing the token no longer works) — that needs a certificate
 * builder (e.g. Bouncy Castle's `bcpkix`, not just the `bcprov` provider this module already
 * depends on for [com.hereliesaz.guillotine.azphalt.AzpCrypto]) which is a dependency-graph change
 * outside this fix's scope, so instead: **[bindAllInterfaces] defaults to `false`, binding only to
 * loopback (127.0.0.1)**. Loopback traffic never leaves the device, so passive network sniffing of
 * the token is moot by construction — at the cost of external tools needing `adb reverse` / a
 * local proxy / the encrypted Cloudflare relay ([McpRelayClient]) to reach the server, rather than
 * connecting directly over LAN. Pass `bindAllInterfaces = true` at construction to opt back into
 * the old all-interfaces behavior for a LAN use case where that trade-off is acceptable; because
 * NanoHTTPD binds its host at construction time (the field is `private final` upstream), this
 * can only be set at the call site, not toggled live once the server is already running. Because
 * it grants read/write control of the editor, `/mcp` also requires a bearer token
 * (`Authorization: Bearer <token>`, see [McpAuth]) regardless of bind mode; `/health` stays open
 * so liveness checks don't need the secret.
 */
class McpServer(port: Int = 6274, bindAllInterfaces: Boolean = false) :
    NanoHTTPD(if (bindAllInterfaces) null else "127.0.0.1", port) {

    private var tools: McpToolsSurface? = null
    private var tokenProvider: (() -> String)? = null

    fun startServer(tools: McpToolsSurface, tokenProvider: () -> String) {
        this.tools = tools
        this.tokenProvider = tokenProvider
        start(SOCKET_READ_TIMEOUT, false)
    }

    fun stopServer() = stop()

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
        val resp = McpDispatcher.handle(t, readBody(session))
        // A JSON-RPC notification produces no response body — acknowledge with 202 and send nothing.
        return if (resp.isEmpty()) {
            newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
        } else {
            ok(resp)
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
