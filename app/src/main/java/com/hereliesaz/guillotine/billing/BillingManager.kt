package com.hereliesaz.guillotine.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.hereliesaz.guillotine.ads.AdsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persisted "last known" Ad-Free entitlement, kept ONLY so a paying user's UI doesn't default to
 * `false` (and show an ad) on cold start while the async Play Billing connect+query round-trip is
 * still in flight and racing the independent, faster-starting ad-load pipeline. This is not a
 * substitute for verification: [BillingManager] re-queries Play on every connection and corrects
 * this flag (and the live [AdsState]) if it's since changed, e.g. a refund.
 *
 * NOTE: this does not perform cryptographic purchase verification (signature check against the
 * Play Console license key) — that requires a real key value only the app's maintainer has, and
 * is a known follow-up. A device could theoretically be tampered with to set this flag directly,
 * same as it always could have forced `AdsState.isAdFree` in memory; persistence doesn't change
 * that exposure, it just avoids the startup race for legitimate purchasers.
 */
private object AdFreeEntitlement {
    private const val PREFS = "guillotine_billing"
    private const val KEY_AD_FREE = "ad_free"

    fun read(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AD_FREE, false)

    fun write(context: Context, owned: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AD_FREE, owned).apply()
    }
}

/**
 * Manages Google Play Billing for the "Ad-Free" in-app purchase.
 */
class BillingManager(private val context: Context, private val coroutineScope: CoroutineScope) : PurchasesUpdatedListener {

    private lateinit var billingClient: BillingClient

    // Product ID must match what's configured in the Google Play Console
    private val adFreeProductId = "guillotine_ad_free"

    private val _adFreeProductDetails = MutableStateFlow<ProductDetails?>(null)
    val adFreeProductDetails = _adFreeProductDetails.asStateFlow()

    fun initialize() {
        // Seed from the last-known entitlement immediately (before Play Billing's async
        // connect+query round-trip completes) so a paying user isn't shown an ad on cold start
        // just because AppOpenAdManager's independent ad-load chain started first and finished
        // before this one did. queryPurchases() below re-verifies against Play on every
        // connection and will correct this (see reconcileEntitlement) if it's since changed.
        if (AdFreeEntitlement.read(context)) {
            AdsState.isAdFree.value = true
            AdsState.isAdFreePermanently.value = true
        }

        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        connectToPlayBilling()
    }

    private fun connectToPlayBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here. This also acts as
                    // the retry point for any acknowledgePurchase() call that failed last session:
                    // an unacknowledged purchase is still returned by queryPurchasesAsync, and
                    // processPurchases() below re-attempts acknowledgement for it.
                    queryPurchases()
                    queryProductDetails()
                }
            }
            override fun onBillingServiceDisconnected() {
                // A dropped billing service must not permanently stop entitlement re-checks for
                // the rest of the session (e.g. a later purchase or refund would never be seen).
                // Reconnect with a small fixed delay rather than hammering it in a tight loop.
                Log.w(TAG, "Billing service disconnected; reconnecting in ${RECONNECT_DELAY_MS}ms")
                coroutineScope.launch(Dispatchers.Main) {
                    delay(RECONNECT_DELAY_MS)
                    connectToPlayBilling()
                }
            }
        })
    }

    private fun queryProductDetails() {
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(adFreeProductId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, queryProductDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _adFreeProductDetails.value =
                    queryProductDetailsResult.productDetailsList.firstOrNull { it.productId == adFreeProductId }
            }
        }
    }

    private fun queryPurchases() {
        if (!billingClient.isReady) {
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
            
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList)
                reconcileEntitlement(purchasesList)
            }
        }
    }

    /**
     * Play is the source of truth. [initialize] optimistically seeds entitlement from the
     * persisted flag to win the startup race against the ad pipeline, but once a real query
     * result is in, correct both the live [AdsState] and the persisted flag to match — e.g. if
     * the purchase was refunded or revoked since the flag was last written.
     */
    private fun reconcileEntitlement(purchases: List<Purchase>) {
        val owned = purchases.any {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(adFreeProductId)
        }
        if (!owned && (AdsState.isAdFree.value || AdsState.isAdFreePermanently.value)) {
            coroutineScope.launch(Dispatchers.Main) {
                AdsState.isAdFree.value = false
                AdsState.isAdFreePermanently.value = false
                AdFreeEntitlement.write(context, false)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            processPurchases(purchases)
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        coroutineScope.launch(Dispatchers.Main) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (purchase.products.contains(adFreeProductId)) {
                        // Grant entitlement, and persist it immediately so the NEXT cold start
                        // can seed from it (see AdFreeEntitlement / initialize()) instead of
                        // defaulting to false and racing the ad pipeline again.
                        AdsState.isAdFree.value = true
                        AdsState.isAdFreePermanently.value = true
                        AdFreeEntitlement.write(context, true)

                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            // An unacknowledged INAPP purchase is auto-refunded by Play after ~3 days, so a
            // silently-dropped failure here is a real risk to a paying user's entitlement — log
            // it clearly instead of swallowing it. No extra retry logic is needed beyond that:
            // the purchase stays unacknowledged on Play's side, so the very next queryPurchases()
            // (every onBillingSetupFinished — including the reconnect added in
            // onBillingServiceDisconnected) will see it as unacknowledged again and retry here.
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(
                    TAG,
                    "acknowledgePurchase failed (code=${billingResult.responseCode}, " +
                        "${billingResult.debugMessage}); will retry on next billing connection.",
                )
            }
        }
    }

    fun purchaseAdFree(activity: Activity) {
        val productDetails = _adFreeProductDetails.value ?: return
        
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    companion object {
        private const val TAG = "BillingManager"
        private const val RECONNECT_DELAY_MS = 2_000L
    }
}
