package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltTrustTest {

    @Test fun flagshipSigningKeyIsPinned() {
        // Pins the exact key so an accidental edit (or a future key rotation upstream) fails loudly
        // here instead of silently regressing every install back to an untrusted-publisher prompt.
        // Every package in the flagship catalog is signed with this key since the 2026-10 rotation.
        assertEquals(
            "MCowBQYDK2VwAyEAWNptGhJCdyjabJ/pEnw+nh41woxC01z6mS8XnL8Cv+M=",
            AzphaltTrust.FLAGSHIP_SIGNING_KEY,
        )
        assertEquals(
            "MCowBQYDK2VwAyEAzmko3VFIYjx0fhXcGUQVmTpBQc33OlfRdJZ03MirPjU=",
            AzphaltTrust.RETIRED_FLAGSHIP_SIGNING_KEY,
        )
    }

    @Test fun storeInstallsTrustBothFlagshipKeys() {
        assertEquals(
            setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY, AzphaltTrust.RETIRED_FLAGSHIP_SIGNING_KEY),
            AzphaltTrust.FLAGSHIP_SIGNING_KEYS,
        )
    }

    @Test fun onlyRetiredToCurrentCountsAsTheStoreRotation() {
        val old = AzphaltTrust.RETIRED_FLAGSHIP_SIGNING_KEY
        val new = AzphaltTrust.FLAGSHIP_SIGNING_KEY
        val other = "MCowBQYDK2VwAyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertTrue(AzphaltTrust.isFlagshipRotation(old, new))
        assertFalse(AzphaltTrust.isFlagshipRotation(new, old)) // never back to the retired key
        assertFalse(AzphaltTrust.isFlagshipRotation(old, other)) // a third party is still a change
        assertFalse(AzphaltTrust.isFlagshipRotation(other, new)) // a non-store pin isn't the store's
        assertFalse(AzphaltTrust.isFlagshipRotation(old, null)) // signed → unsigned is never a rotation
        assertFalse(AzphaltTrust.isFlagshipRotation(null, new))
    }

    @Test fun storeWebUrlIsFlagshipDomain() {
        assertEquals("https://azphalt.store", AzphaltTrust.STORE_WEB_URL)
    }
}
