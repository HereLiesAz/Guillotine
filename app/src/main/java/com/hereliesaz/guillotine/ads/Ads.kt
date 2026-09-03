package com.hereliesaz.guillotine.ads

import androidx.compose.runtime.mutableStateOf

/** Ad-Free entitlement state, read/written by [com.hereliesaz.guillotine.billing.BillingManager]. */
object AdsState {
    /** True if the user owns Ad-Free (the one-time IAP). Set only from a real, owned purchase. */
    val isAdFree = mutableStateOf(false)

    /** True ONLY if the user has permanently purchased Ad-Free (so we can hide the menu option). */
    val isAdFreePermanently = mutableStateOf(false)
}
