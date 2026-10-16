package com.hereliesaz.guillotine.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCryptoTest {

    private val token = "test-token"
    private val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

    @Test fun roundTrip() {
        val s = McpCrypto.seal(token, McpCrypto.DIR_TO_DEVICE, "rid-1", 7, 1_000L, body)
        assertEquals(body, McpCrypto.open(token, McpCrypto.DIR_TO_DEVICE, "rid-1", 7, 1_000L, s.ivB64, s.ctB64))
    }

    /** A frame sealed by tools/mcp-relay/proxy.js (fixed IV) — keeps the two implementations in lockstep. */
    @Test fun opensAFrameSealedByTheNodeProxy() {
        val iv = "AQEBAQEBAQEBAQEB"
        val ct = "vHsBX1sw3oBdpyN7tNr209kiHteiO6sQy5oItJEqobIyYtJdeqQ2h8j2pgxHzVIlvEf/lXUhpJP333wEbRA="
        assertEquals(body, McpCrypto.open(token, McpCrypto.DIR_TO_DEVICE, "rid-1", 7, 1_700_000_000_000L, iv, ct))
        assertEquals("6534bcb0771f8d556dba201314ec168618d6c5a0555bd61847bd80d51ab0a02a", McpCrypto.roomId(token))
    }

    @Test fun aadBindsRidDirectionCounterAndTime() {
        val s = McpCrypto.seal(token, McpCrypto.DIR_TO_TOOL, "rid-1", 7, 1_000L, body)
        val fail = { block: () -> Unit -> assertThrows(Exception::class.java) { block() } }
        fail { McpCrypto.open(token, McpCrypto.DIR_TO_TOOL, "rid-2", 7, 1_000L, s.ivB64, s.ctB64) }
        fail { McpCrypto.open(token, McpCrypto.DIR_TO_DEVICE, "rid-1", 7, 1_000L, s.ivB64, s.ctB64) }
        fail { McpCrypto.open(token, McpCrypto.DIR_TO_TOOL, "rid-1", 8, 1_000L, s.ivB64, s.ctB64) }
        fail { McpCrypto.open(token, McpCrypto.DIR_TO_TOOL, "rid-1", 7, 1_001L, s.ivB64, s.ctB64) }
        fail { McpCrypto.open("other-token", McpCrypto.DIR_TO_TOOL, "rid-1", 7, 1_000L, s.ivB64, s.ctB64) }
    }

    @Test fun replayGuardAcceptsOnceWithinWindow() {
        var now = 1_000_000L
        val guard = McpCrypto.ReplayGuard(windowMs = 60_000L, clock = { now })
        assertTrue(guard.accept("a", now))
        assertFalse("same rid replayed", guard.accept("a", now))
        assertTrue(guard.accept("b", now - 30_000L))
        assertFalse("too old", guard.accept("c", now - 61_000L))
        assertFalse("from the future", guard.accept("d", now + 61_000L))
        assertFalse("blank rid", guard.accept("", now))
        now += 200_000L
        // "a" was forgotten after the window, but an old frame still can't pass the timestamp check.
        assertFalse(guard.accept("a", 1_000_000L))
    }
}
