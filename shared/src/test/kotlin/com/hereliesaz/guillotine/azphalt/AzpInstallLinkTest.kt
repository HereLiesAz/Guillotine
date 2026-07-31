package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzpInstallLinkTest {

    @Test fun parsesIdAndVersion() {
        val link = AzpInstallLink.parse("azphalt://install?id=com.hereliesaz.azphalt.3d-protrusion&version=1.0.0")
        assertEquals(AzpInstallLink("com.hereliesaz.azphalt.3d-protrusion", "1.0.0"), link)
    }

    @Test fun absentVersionMeansLatest() {
        // Null, not a made-up default: resolving "latest" is the registry's answer to give, not ours.
        val link = AzpInstallLink.parse("azphalt://install?id=com.example.pkg")
        assertEquals(AzpInstallLink("com.example.pkg", null), link)
    }

    @Test fun blankVersionMeansLatest() {
        assertEquals(AzpInstallLink("com.example.pkg", null), AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version="))
    }

    @Test fun paramOrderDoesNotMatter() {
        assertEquals(AzpInstallLink("com.example.pkg", "2.1"), AzpInstallLink.parse("azphalt://install?version=2.1&id=com.example.pkg"))
    }

    @Test fun unknownParamsAreIgnored() {
        assertEquals(
            AzpInstallLink("com.example.pkg", "1.0.0"),
            AzpInstallLink.parse("azphalt://install?ref=twitter&id=com.example.pkg&version=1.0.0&utm_source=x"),
        )
    }

    @Test fun schemeAndHostAreCaseInsensitive() {
        assertEquals(AzpInstallLink("com.example.pkg", null), AzpInstallLink.parse("AZPHALT://INSTALL?id=com.example.pkg"))
    }

    @Test fun missingIdIsRejected() {
        assertNull(AzpInstallLink.parse("azphalt://install?version=1.0.0"))
        assertNull(AzpInstallLink.parse("azphalt://install?id="))
        assertNull(AzpInstallLink.parse("azphalt://install"))
    }

    @Test fun wrongSchemeIsRejected() {
        assertNull(AzpInstallLink.parse("https://install?id=com.example.pkg"))
        assertNull(AzpInstallLink.parse("azp://install?id=com.example.pkg"))
    }

    @Test fun wrongHostIsRejected() {
        // Only the install verb is claimed; a future azphalt://browse must not silently install.
        assertNull(AzpInstallLink.parse("azphalt://browse?id=com.example.pkg"))
        assertNull(AzpInstallLink.parse("azphalt:install?id=com.example.pkg")) // opaque, no authority
    }

    @Test fun nullOrGarbageIsRejected() {
        assertNull(AzpInstallLink.parse(null))
        assertNull(AzpInstallLink.parse(""))
        assertNull(AzpInstallLink.parse("   "))
        assertNull(AzpInstallLink.parse("not a uri at all"))
    }

    @Test fun percentEncodedValuesAreDecoded() {
        // %2E is '.', so this is an ordinary reverse-DNS id written the long way round.
        assertEquals(AzpInstallLink("com.example.pkg", "1.0.0"), AzpInstallLink.parse("azphalt://install?id=com%2Eexample%2Epkg&version=1%2E0%2E0"))
    }

    @Test fun percentEncodedPlusSurvivesAsPlus() {
        // '+' is legal in a semver build identifier. Form-decoding would turn it into a space and the
        // version would then fail validation, so the decoder is percent-only on purpose.
        assertEquals(AzpInstallLink("com.example.pkg", "1.0.0+build.7"), AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=1.0.0%2Bbuild.7"))
        assertEquals(AzpInstallLink("com.example.pkg", "1.0.0+build.7"), AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=1.0.0+build.7"))
    }

    @Test fun malformedPercentEscapeIsRejected() {
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example%2"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example%ZZ"))
    }

    @Test fun traversalInIdIsRejected() {
        // Both halves matter: the id is interpolated into a registry URL path, and a link comes from a
        // web page. Raw and percent-encoded forms are refused, before and after decoding.
        assertNull(AzpInstallLink.parse("azphalt://install?id=../../etc/passwd"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=%2E%2E%2F%2E%2E%2Fetc"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example..pkg"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com/example"))
    }

    @Test fun traversalInVersionIsRejected() {
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=../../secret"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=1.0.0%2F..%2Fdownload"))
    }

    @Test fun invalidVersionIsRejectedRatherThanFallingBackToLatest() {
        // Installing a *different* version than the link asked for would be worse than doing nothing.
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=1.0.0%20OR%201"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=.1.0"))
    }

    @Test fun oversizedValuesAreRejected() {
        val longId = "a".repeat(129)
        assertNull(AzpInstallLink.parse("azphalt://install?id=$longId"))
        assertEquals(AzpInstallLink("a".repeat(128), null), AzpInstallLink.parse("azphalt://install?id=${"a".repeat(128)}"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=com.example.pkg&version=${"1".repeat(65)}"))
    }

    @Test fun idMustStartAlphanumeric() {
        assertNull(AzpInstallLink.parse("azphalt://install?id=.hidden"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=-dash"))
        assertNull(AzpInstallLink.parse("azphalt://install?id=_under"))
    }

    @Test fun firstIdWins() {
        // A duplicated param must not let a later copy override the one a reader would call authoritative.
        assertEquals(AzpInstallLink("com.example.first", null), AzpInstallLink.parse("azphalt://install?id=com.example.first&id=com.example.second"))
    }
}
