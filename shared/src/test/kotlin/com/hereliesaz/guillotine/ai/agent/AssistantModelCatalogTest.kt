package com.hereliesaz.guillotine.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantModelCatalogTest {

    @Test
    fun catalogUsesCurrentMobileTiers() {
        val ids = RECOMMENDED_ON_DEVICE_MODELS.map { it.id }.toSet()

        assertTrue("smollm2-360m" in ids)
        assertTrue("minicpm5-1b-int4" in ids)
        assertTrue("qwen3-1.7b-int4" in ids)
        assertTrue("minicpm5-2b-int4" in ids)

        assertFalse("qwen2.5-0.5b-q8" in ids)
        assertFalse("qwen2.5-1.5b-q8" in ids)
        assertFalse("deepseek-r1-qwen-1.5b-q8" in ids)
        assertFalse("phi4-mini-q8" in ids)
    }

    @Test
    fun modernAssistantDownloadsUseLiteRtLmBundles() {
        val modern = RECOMMENDED_ON_DEVICE_MODELS.filter {
            it.id in setOf(
                "smollm2-360m",
                "qwen3-0.6b-int4",
                "minicpm5-1b-int4",
                "qwen3-1.7b-int4",
                "minicpm5-2b-int4",
            )
        }

        assertTrue(modern.isNotEmpty())
        assertTrue(modern.all { it.fileName.endsWith(".litertlm") })
        assertTrue(modern.all { it.downloadUrl?.contains("huggingface.co/litert-community/") == true })
    }
}
