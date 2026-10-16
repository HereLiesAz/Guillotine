package com.hereliesaz.guillotine.data

import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val provider = AiProviderType.entries.first()
    private val settings = AiSettings(
        keys = mapOf(provider to "sk-secret"),
        leonardoKey = "leo-secret",
        ffmpegPath = "/bin/ffmpeg",
        azpSandboxToken = "tok-secret",
    )

    @Test
    fun secretsExcludedByDefault() {
        val text = SettingsBackup.encode(SettingsBackup.toBundle(settings, emptyList(), includeSecrets = false))
        assertFalse(text.contains("sk-secret"))
        assertFalse(text.contains("leo-secret"))
        assertFalse(text.contains("tok-secret"))
        assertTrue(text.contains("/bin/ffmpeg"))
    }

    @Test
    fun secretsIncludedOnOptIn() {
        val text = SettingsBackup.encode(SettingsBackup.toBundle(settings, emptyList(), includeSecrets = true))
        assertTrue(text.contains("sk-secret"))
        assertTrue(text.contains("tok-secret"))
    }

    @Test
    fun restoringSecretFreeBackupKeepsCurrentKeys() {
        val bundle = SettingsBackup.decode(
            SettingsBackup.encode(SettingsBackup.toBundle(settings.copy(ffmpegPath = "/new"), emptyList(), includeSecrets = false)),
        )
        val restored = SettingsBackup.fromBundle(bundle, current = settings)
        assertEquals("sk-secret", restored.keys[provider])
        assertEquals("leo-secret", restored.leonardoKey)
        assertEquals("tok-secret", restored.azpSandboxToken)
        assertEquals("/new", restored.ffmpegPath)
    }

    @Test
    fun restoringBackupWithSecretsOverridesKeys() {
        val bundle = SettingsBackup.toBundle(settings.copy(leonardoKey = "new-leo"), emptyList(), includeSecrets = true)
        val restored = SettingsBackup.fromBundle(bundle, current = settings)
        assertEquals("new-leo", restored.leonardoKey)
    }
}
