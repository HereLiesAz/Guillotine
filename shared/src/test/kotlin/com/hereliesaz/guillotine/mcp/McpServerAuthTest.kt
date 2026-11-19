package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class McpServerAuthTest {

    private val tools = object : McpToolsSurface {
        override fun definitions() = JSONArray().put(JSONObject().put("name", "ping"))
        override fun call(name: String, args: JSONObject) = JSONObject().put("pong", true)
        override fun resourceDefinitions() = JSONArray()
        override fun readResource(uri: String) = JSONObject()
    }

    private var server: McpServer? = null

    @After fun stop() { server?.stopServer() }

    /** Start on an ephemeral loopback port with [token] and return the port. */
    private fun start(token: String): Int {
        val s = McpServer(port = 0)
        s.startServer(tools) { token }
        server = s
        return s.listeningPort
    }

    private fun post(port: Int, auth: String?): Int {
        val c = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        auth?.let { c.setRequestProperty("Authorization", it) }
        c.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray()) }
        return c.responseCode.also { c.disconnect() }
    }

    @Test fun blankConfiguredTokenFailsClosed() {
        val port = start("")
        assertEquals(401, post(port, "Bearer "))
        assertEquals(401, post(port, "Bearer anything"))
        assertEquals(401, post(port, null))
    }

    @Test fun wrongTokenIsRejected() {
        val port = start("s3cret-token")
        assertEquals(401, post(port, "Bearer wrong-token"))
        assertEquals(401, post(port, "s3cret-token")) // no Bearer scheme
    }

    @Test fun rightTokenPasses() {
        val port = start("s3cret-token")
        assertEquals(200, post(port, "Bearer s3cret-token"))
    }
}
