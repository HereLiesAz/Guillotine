package com.hereliesaz.guillotine.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelAdvisorTest {

    @Test
    fun capableDeviceCanPreferStrongAssistant() {
        val profile = DeviceModelProfile(
            deviceLabel = "Test flagship",
            totalRamBytes = 16_000_000_000L,
            freeStorageBytes = 40_000_000_000L,
            cpuCores = 8,
            is64Bit = true,
            lowRam = false,
            osLabel = "Android",
        )

        val advice = DeviceModelAdvisor.advise(profile, RECOMMENDED_ON_DEVICE_MODELS)
        assertEquals("qwen3-1.7b-int4", advice.first { it.fit == DeviceModelFit.BEST_FIT }.model.id)
        assertTrue(advice.first().reason.startsWith("Best fit:"))
    }

    @Test
    fun constrainedDeviceExplainsWhyLargeModelsAreRejected() {
        val profile = DeviceModelProfile(
            deviceLabel = "Test budget phone",
            totalRamBytes = 4_000_000_000L,
            freeStorageBytes = 2_000_000_000L,
            cpuCores = 4,
            is64Bit = true,
            lowRam = true,
            osLabel = "Android",
        )

        val advice = DeviceModelAdvisor.advise(profile, RECOMMENDED_ON_DEVICE_MODELS)
        val phi = advice.first { it.model.id == "phi4-mini-q8" }
        assertEquals(DeviceModelFit.NOT_RECOMMENDED, phi.fit)
        assertTrue(phi.reason.contains("Not recommended"))
        assertTrue(phi.reason.contains("storage") || phi.reason.contains("RAM"))
    }

    @Test
    fun archiveRecommendationReservesExtractionHeadroom() {
        val archive = OnDeviceModel(
            id = "archive-test",
            label = "Archive test",
            fileName = "archive.tar.bz2",
            sizeBytes = 500_000_000L,
            license = "Test",
            gated = false,
            repoUrl = "https://example.invalid",
            downloadUrl = "https://example.invalid/archive.tar.bz2",
            isArchive = true,
            archiveMarker = "model.onnx",
            category = ModelCategory.STEM,
        )
        val profile = DeviceModelProfile(
            deviceLabel = "Storage constrained",
            totalRamBytes = 8_000_000_000L,
            freeStorageBytes = 1_000_000_000L,
            cpuCores = 8,
            is64Bit = true,
            lowRam = false,
            osLabel = "Android",
        )

        val advice = DeviceModelAdvisor.advise(profile, listOf(archive)).single()
        assertEquals(DeviceModelFit.NOT_RECOMMENDED, advice.fit)
        assertTrue(advice.reason.contains("archive is extracted"))
    }
}
