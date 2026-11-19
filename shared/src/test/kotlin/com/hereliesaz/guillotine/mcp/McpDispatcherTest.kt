package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDispatcherTest {

    private val calls = mutableListOf<String>()

    private val tools = object : McpToolsSurface {
        override fun definitions() = JSONArray().put(JSONObject().put("name", "ping"))
        override fun call(name: String, args: JSONObject): JSONObject {
            calls += name
            return JSONObject().put("echo", args.optString("v"))
        }
        override fun resourceDefinitions() = JSONArray().put(JSONObject().put("uri", "guillotine://timeline"))
        override fun readResource(uri: String) = JSONObject().put("uri", uri)
    }

    private fun rpc(body: String) = JSONObject(McpDispatcher.handle(tools, body))

    @Test fun initializeEchoesRequestedProtocol() {
        val r = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-01-01"}}""")
        assertEquals(1, r.getInt("id"))
        assertEquals("2025-01-01", r.getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun toolsListReturnsDefinitions() {
        val r = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals("ping", r.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test fun toolsCallRoutesToSurface() {
        val r = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{"v":"hi"}}}""")
        assertEquals(listOf("ping"), calls)
        val text = r.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("hi", JSONObject(text).getString("echo"))
    }

    @Test fun missingToolNameIsInvalidParams() {
        val r = rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{}}""")
        assertEquals(-32602, r.getJSONObject("error").getInt("code"))
    }

    @Test fun parseErrorAndNotifications() {
        assertEquals(-32700, rpc("not json").getJSONObject("error").getInt("code"))
        assertTrue(McpDispatcher.handle(tools, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").isEmpty())
    }

    @Test fun unknownMethodIsAnError() {
        assertTrue(rpc("""{"jsonrpc":"2.0","id":5,"method":"nope/nope"}""").has("error"))
    }
}
