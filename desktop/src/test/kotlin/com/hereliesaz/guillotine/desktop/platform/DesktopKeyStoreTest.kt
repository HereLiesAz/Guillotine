package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.byoProviders
import com.hereliesaz.guillotine.ai.AiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopKeyStoreTest {

    companion object {
        private lateinit var home: File

        /** Point DesktopStorage at a throwaway home BEFORE it is first touched, so the real data dir is never used. */
        @BeforeClass @JvmStatic
        fun isolateHome() {
            assumeFalse("Windows resolves the data dir from %APPDATA%", System.getProperty("os.name").lowercase().contains("win"))
            home = Files.createTempDirectory("guillotine-home").toFile()
            System.setProperty("user.home", home.absolutePath)
            check(DesktopStorage.dataDir.absolutePath.startsWith(home.absolutePath)) {
                "DesktopStorage was initialised before the test could isolate it: ${DesktopStorage.dataDir}"
            }
        }
    }

    @Test
    fun settingsRoundTripThroughANewInstanceAndAreEncryptedAtRest() = runBlocking {
        val provider = byoProviders.first() // only bring-your-own-key providers are persisted
        val value = "sk-test-${System.nanoTime()}"
        DesktopKeyStore().save(AiSettings(keys = mapOf(provider to value), leonardoKey = "leo-$value"))

        val reopened = DesktopKeyStore()
        assertNull(reopened.unlockError.value)
        assertEquals(value, reopened.settings.value.keys[provider])
        assertEquals("leo-$value", reopened.settings.value.leonardoKey)

        val onDisk = File(DesktopStorage.dataDir, "settings.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("API key must not be stored in plaintext", onDisk.contains(value))
    }
}
