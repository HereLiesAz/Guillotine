package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `compat` decides whether a package is installable at all, so the `0.1` grammar
 * (`spec/extension-manifest.md` § `compat`) is pinned exactly: an optional comparator defaulting to
 * `>=`, then `MAJOR[.MINOR[.PATCH]]` with omitted parts zero. Nothing else is in `0.1`.
 *
 * The three-way result matters as much as the comparison: null means "not expressible in this grammar",
 * which is not the same as false and must not refuse an install.
 */
class AzpCompatTest {

    @Test fun theConventionalValueIsSatisfied() {
        // ">=0.1" is what every package in the flagship catalog declares.
        assertEquals(true, AzpCompat.satisfies(">=0.1", "0.1"))
    }

    @Test fun bareVersionMeansAtLeast() {
        assertEquals(true, AzpCompat.satisfies("0.1", "0.1"))
        assertEquals(true, AzpCompat.satisfies("0.1", "0.2"))
        assertEquals(false, AzpCompat.satisfies("0.2", "0.1"))
    }

    @Test fun omittedPartsAreZero() {
        // 0.1 == 0.1.0 by the spec's own example.
        assertEquals(true, AzpCompat.satisfies("=0.1", "0.1.0"))
        assertEquals(true, AzpCompat.satisfies("=0.1.0", "0.1"))
        assertEquals(true, AzpCompat.satisfies("=1", "1.0.0"))
    }

    @Test fun everyComparator() {
        assertEquals(true, AzpCompat.satisfies(">0.0.9", "0.1"))
        assertEquals(false, AzpCompat.satisfies(">0.1", "0.1"))
        assertEquals(true, AzpCompat.satisfies("<=0.1", "0.1"))
        assertEquals(true, AzpCompat.satisfies("<0.2", "0.1"))
        assertEquals(false, AzpCompat.satisfies("<0.1", "0.1"))
        assertEquals(false, AzpCompat.satisfies("=0.2", "0.1"))
    }

    @Test fun greaterOrEqualIsNotMisreadAsGreater() {
        // ">=" must be matched before ">", or ">=0.2" parses as ">" on version "=0.2" and falls apart.
        assertEquals(true, AzpCompat.satisfies(">=0.1", "0.1"))
        assertEquals(false, AzpCompat.satisfies(">=0.2", "0.1"))
        assertEquals(true, AzpCompat.satisfies("<=0.1", "0.1"))
    }

    @Test fun patchAndMinorPrecedence() {
        assertEquals(true, AzpCompat.satisfies(">=1.2.3", "1.2.4"))
        assertEquals(false, AzpCompat.satisfies(">=1.2.4", "1.2.3"))
        assertEquals(true, AzpCompat.satisfies(">=1.9", "1.10"))
        assertEquals(false, AzpCompat.satisfies(">=1.10", "1.9"))
    }

    @Test fun whitespaceIsTolerated() {
        assertEquals(true, AzpCompat.satisfies("  >=0.1  ", "0.1"))
    }

    @Test fun grammarOutsideZeroPointOneIsNullNotFalse() {
        // These are the forms the spec explicitly excludes. Returning false would refuse the install;
        // null lets the caller install rather than reject syntax a later spec may legitimise.
        for (bad in listOf(">=0.1 <0.3", "^1.0", "~0.1", ">=0.1 || >=0.2", "1.0.0-beta.1", "1.0.0+build")) {
            assertNull("expected null for “$bad”", AzpCompat.satisfies(bad, "0.1"))
        }
    }

    @Test fun malformedIsNull() {
        for (bad in listOf("", "   ", "abc", ">=", ">=abc", "1.2.3.4", "1..2", "-1", ">=-1")) {
            assertNull("expected null for “$bad”", AzpCompat.satisfies(bad, "0.1"))
        }
    }

    @Test fun aBadHostVersionIsNullRatherThanAFalseRefusal() {
        assertNull(AzpCompat.satisfies(">=0.1", "not-a-version"))
    }

    @Test fun thisHostSatisfiesTheWholeLiveCatalog() {
        // Every package on the flagship registry declares ">=0.1"; if this build stopped satisfying it,
        // enforcing compat would refuse the entire catalog — the failure mode most worth catching here.
        assertEquals(true, AzpCompat.satisfies(">=0.1", AzpCompat.HOST_API_VERSION))
    }
}
