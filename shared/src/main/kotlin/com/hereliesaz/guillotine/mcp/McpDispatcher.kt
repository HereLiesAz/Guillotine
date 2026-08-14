package com.hereliesaz.guillotine.mcp

import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Transport-agnostic JSON-RPC 2.0 dispatch for the MCP tools. Both the local HTTP server
 * ([McpServer]) and the encrypted Cloudflare relay ([McpRelayClient]) feed request bodies
 * here and get a response body back, so the two transports share identical behavior.
 */
object McpDispatcher {

    /** Ceiling for an ordinary tool call — generous enough for on-device ffmpeg/ML work, but bounded
     *  so a hung call can't hold the tool surface forever (see [OperationController] in :app, which
     *  only tracks one in-flight long operation at a time). */
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    /** Longer ceiling for cloud generation calls (`generate_*`), which poll an external provider and
     *  can legitimately take minutes. */
    private const val GENERATION_TIMEOUT_MS = 5 * 60_000L

    // Every `tools/call` runs on its own worker thread so it can be time-boxed with future.get(timeout)
    // even though the tool implementation itself is a blocking, synchronous call (see McpTools). Daemon
    // threads so a stuck call never keeps the JVM alive.
    private val toolExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-tool-call").apply { isDaemon = true }
    }

    // In-flight JSON-RPC request ids for `tools/call`, keyed by the id's string form (ids can arrive
    // as JSON strings or numbers of varying Java types, so string-normalize for comparison). Lets a
    // `notifications/cancelled` for a *stale or unknown* id be ignored, and the matching one actually
    // reach [McpToolsSurface.cancel].
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Parse a JSON-RPC request body and return the JSON-RPC response body, or an **empty string** for a
     * notification (a request with no `id`, e.g. the MCP client's `notifications/initialized`), which
     * per JSON-RPC 2.0 MUST NOT be answered. Never throws.
     */
    fun handle(tools: McpToolsSurface, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonRpcError(null, -32700, "Parse error").toString()
        val method = json.optString("method")
        // Per JSON-RPC 2.0, a "notification" is any request that omits `id` — not just one whose
        // method happens to start with "notifications/". Absence of `id` is the authoritative signal;
        // a client could in principle send other fire-and-forget calls the same way.
        val hasId = json.has("id") && !json.isNull("id")
        if (!hasId) {
            if (method == "notifications/cancelled") handleCancelled(tools, json.optJSONObject("params"))
            return ""
        }
        val req = JsonRpcRequest(
            id = json.opt("id"),
            method = method,
            params = json.optJSONObject("params"),
        )
        return dispatch(tools, req).toString()
    }

    /** `notifications/cancelled` carries `{requestId, reason?}` — cancel the matching in-flight call. */
    private fun handleCancelled(tools: McpToolsSurface, params: JSONObject?) {
        val cancelId = params?.opt("requestId") ?: return
        val key = cancelId.toString()
        if (inFlight.contains(key)) {
            runCatching { tools.cancel(cancelId) }
        }
    }

    private fun dispatch(tools: McpToolsSurface, req: JsonRpcRequest): JSONObject = when (req.method) {
        "initialize" -> jsonRpcResult(req.id, JSONObject().apply {
            // Echo back whatever protocol version the client actually asked for — falling back to our
            // default only when it sent none — instead of always claiming the hardcoded version
            // regardless of what was requested.
            val requested = req.params?.optString("protocolVersion")?.takeIf { it.isNotBlank() }
            put("protocolVersion", requested ?: "2024-11-05")
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
                val idKey = req.id?.toString()
                idKey?.let { inFlight.add(it) }
                val timeoutMs = if (name.startsWith("generate_")) GENERATION_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
                try {
                    val res = callWithTimeout(tools, name, args, timeoutMs)
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject().apply {
                            put("type", "text"); put("text", res.toString(2))
                        }))
                    })
                } catch (e: TimeoutException) {
                    // Ask the tool surface to stop whatever it's doing (e.g. OperationController.cancel()
                    // on Android) so the slot frees up, then return a clean error immediately instead of
                    // leaving the caller (and every other tool call behind it) hanging indefinitely.
                    runCatching { tools.cancel(req.id) }
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Error: tool \"$name\" timed out after ${timeoutMs}ms and was cancelled.")
                        }))
                        put("isError", true)
                    })
                } catch (e: Throwable) {
                    // Catch Throwable, not Exception — tool implementations can surface native
                    // Errors (UnsatisfiedLinkError from ML Kit, OOM on a large frame decode) and
                    // the "never throws" contract must hold for those too.
                    jsonRpcResult(req.id, JSONObject().apply {
                        put("content", org.json.JSONArray().put(JSONObject().apply {
                            put("type", "text"); put("text", "Error: ${e.message}")
                        }))
                        put("isError", true)
                    })
                } finally {
                    idKey?.let { inFlight.remove(it) }
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
                } catch (e: Throwable) {
                    // Catch Throwable, not Exception — tool implementations can surface native
                    // Errors (UnsatisfiedLinkError from ML Kit, OOM on a large frame decode) and
                    // the "never throws" contract must hold for those too.
                    jsonRpcError(req.id, -32602, "Resource error: ${e.message}")
                }
            }
        }

        else -> jsonRpcError(req.id, -32601, "Method not found: ${req.method}")
    }

    /** Run [tools].call(name, args) on [toolExecutor] and enforce [timeoutMs] on it via `Future.get`,
     *  since the call itself is a blocking, synchronous method with no cancellation support of its
     *  own. Throws [TimeoutException] on expiry (caller is responsible for asking the tool surface to
     *  stop), or the tool's own exception (unwrapped from [ExecutionException]) on failure. */
    private fun callWithTimeout(tools: McpToolsSurface, name: String, args: JSONObject, timeoutMs: Long): JSONObject {
        val future = toolExecutor.submit(Callable { tools.call(name, args) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true) // best-effort interrupt; the real stop signal is tools.cancel() above
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
