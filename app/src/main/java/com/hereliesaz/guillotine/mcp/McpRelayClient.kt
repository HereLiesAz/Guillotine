package com.hereliesaz.guillotine.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional outbound bridge to the encrypted Cloudflare relay (see `tools/mcp-relay`). Instead of
 * waiting for inbound LAN connections, the app dials *out* over WSS to the Worker and joins a
 * room derived from the MCP token. The tool-side proxy joins the same room; the Worker relays
 * opaque [McpCrypto]-sealed frames between them, so the channel is encrypted in transit **and**
 * end-to-end (the Worker only sees ciphertext).
 *
 * Frame shape (JSON, both directions): `{"rid":"<correlation>","iv":"<b64>","ct":"<b64>"}` where
 * the plaintext is a JSON-RPC request (tool→device) or response (device→tool). The device echoes
 * the request's `rid` so the proxy can match responses.
 */
class McpRelayClient(
    private val tools: McpTools,
    private val tokenProvider: () -> String,
    private val config: RelayConfig,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var attempt = 0

    fun start() {
        if (running || !config.isUsable) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        runCatching { ws?.close(1000, "client stopping") }
        ws = null
        scope.cancel()
    }

    private fun connect() {
        if (!running) return
        val room = McpCrypto.roomId(tokenProvider())
        val sep = if (config.workerUrl.contains("?")) "&" else "?"
        val url = "${config.workerUrl}${sep}room=$room&role=device"
        val request = Request.Builder().url(url).apply {
            if (config.accessKey.isNotBlank()) addHeader("X-Relay-Key", config.accessKey)
        }.build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(webSocket, text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun handleFrame(webSocket: WebSocket, text: String) {
        val token = tokenProvider()
        scope.launch {
            try {
                val frame = JSONObject(text)
                val rid = frame.optString("rid")
                val iv = frame.optString("iv")
                val ct = frame.optString("ct")
                if (iv.isEmpty() || ct.isEmpty()) return@launch
                val requestBody = McpCrypto.open(token, iv, ct)
                val responseBody = McpDispatcher.handle(tools, requestBody)
                val sealed = McpCrypto.seal(token, responseBody)
                webSocket.send(
                    JSONObject().apply {
                        put("rid", rid)
                        put("iv", sealed.ivB64)
                        put("ct", sealed.ctB64)
                    }.toString(),
                )
            } catch (_: Exception) {
                // Wrong token / tampered frame / dispatch failure — drop it; the caller times out.
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        val backoffMs = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L) // 1s → 30s cap
        attempt++
        scope.launch {
            delay(backoffMs)
            if (running) connect()
        }
    }
}
