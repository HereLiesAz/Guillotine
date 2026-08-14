package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject

interface McpToolsSurface {
    fun definitions(): JSONArray
    fun call(name: String, args: JSONObject): JSONObject
    fun resourceDefinitions(): JSONArray
    fun readResource(uri: String): JSONObject

    /**
     * Best-effort request to stop whatever's currently running for the JSON-RPC request [requestId]
     * (as sent by the client's `notifications/cancelled`), called from [McpDispatcher] either on that
     * notification or when its own per-call timeout expires. Implementations that can only track one
     * global in-flight operation (e.g. Android's `OperationController`, which has a single slot) may
     * ignore [requestId] and just cancel whatever that is — there's nothing else it could be. Default
     * no-op for surfaces with nothing cancellable yet.
     */
    fun cancel(requestId: Any?) {}
}
