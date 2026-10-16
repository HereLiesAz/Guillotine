package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AzpExternalOpenReadTest {
    @Test
    fun readsWithinLimit() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, AzpExternalOpen.readBounded(ByteArrayInputStream(data), maxBytes = 1000))
    }

    @Test(expected = IOException::class)
    fun refusesOverLimitWithoutReadingEndlessStream() {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        AzpExternalOpen.readBounded(endless, maxBytes = 1024 * 1024)
    }
}
