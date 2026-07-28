package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Test

class AzphaltTrustTest {

    @Test fun flagshipSigningKeyIsPinned() {
        // Pins the exact key so an accidental edit (or a future key rotation upstream) fails loudly
        // here instead of silently regressing every install back to an untrusted-publisher prompt.
        // Verified live against multiple independently-downloaded packages via curl + unzip; every
        // package in the flagship catalog is signed with this one key.
        assertEquals(
            "MCowBQYDK2VwAyEAzmko3VFIYjx0fhXcGUQVmTpBQc33OlfRdJZ03MirPjU=",
            AzphaltTrust.FLAGSHIP_SIGNING_KEY,
        )
    }

    @Test fun storeWebUrlIsFlagshipDomain() {
        assertEquals("https://azphalt.store", AzphaltTrust.STORE_WEB_URL)
    }
}
