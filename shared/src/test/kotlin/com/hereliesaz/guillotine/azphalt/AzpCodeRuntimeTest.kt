package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzpCodeRuntimeTest {

    private fun manifest(kind: String, caps: List<String>) = AzpManifest(
        azphalt = "0.1", id = "com.example.ext", name = "Ext", version = "1.0.0",
        kind = kind, license = "MIT", compat = ">=0.1", files = emptyMap(), capabilities = caps,
    )

    private fun loaded(kind: String, caps: List<String>) =
        AzpPackage.Loaded(manifest(kind, caps), emptyMap())

    @Test fun `missingGrants reports ungranted capabilities`() {
        val m = manifest("code", listOf("clip.read", "clip.write", "params.write"))
        assertEquals(listOf("clip.write", "params.write"), AzpCodeRuntime.missingGrants(m, setOf("clip.read")))
        assertTrue(AzpCodeRuntime.fullyGranted(m, setOf("clip.read", "clip.write", "params.write")))
        assertFalse(AzpCodeRuntime.fullyGranted(m, setOf("clip.read")))
    }

    @Test fun `unavailable runtime denies an ungranted capability before reporting unavailable`() {
        val out = UnavailableAzpCodeRuntime.run(loaded("code", listOf("clip.write")), "{}", AzpCodeRuntime.Grant(emptySet()))
        assertEquals(AzpCodeRuntime.Outcome.Denied("clip.write"), out)
    }

    @Test fun `unavailable runtime reports unavailable when fully granted`() {
        val out = UnavailableAzpCodeRuntime.run(loaded("code", listOf("clip.write")), "{}", AzpCodeRuntime.Grant(setOf("clip.write")))
        assertTrue(out is AzpCodeRuntime.Outcome.Unavailable)
    }

    @Test fun `unavailable runtime errors on a non-code package`() {
        val out = UnavailableAzpCodeRuntime.run(loaded("asset", emptyList()), "{}", AzpCodeRuntime.Grant(emptySet()))
        assertTrue(out is AzpCodeRuntime.Outcome.Error)
    }
}
