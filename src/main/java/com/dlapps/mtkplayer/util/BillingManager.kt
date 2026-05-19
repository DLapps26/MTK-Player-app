package com.dlapps.mtkplayer.util

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BillingManager(private val context: Context) {

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        }
    }

    private var billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    private val _isPremium = MutableStateFlow<Boolean?>(null)
    val isPremium: StateFlow<Boolean?> = _isPremium

    companion object {
        const val PREMIUM_PRODUCT_ID = "mtkplayer_premium_features"
        const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0zxzQldn7eomV7giImD7+9RfmWg6LxDbbnlUWJjS71UfDI+SDsPoG vsRWyLcIJZ8zOjQxOlHLAzjRylTf81oJ5aGMjktB2K5c+V3HFrE96WFzsUnlH0HsYADNnbeIFNQihdrRwMW0A5ih5bCQDOVeckx 72kEEpMikjBGWjzRrGrQoBJ9oooVDb9klF4GFmy6cYzk+DvLG9TVrNenjsqAQFAiP6Rdw6MiM3Qkw4boXS4YswOSVo2oq/lLWa AcBuXHgemxK7RshuwYWiM8y1z5Q6vlVVS78qBo/X5RXwmXxHm5NwJ9yEk3CY1EKmfAJuEU3Mex6j11xqrncYYpHJVZcwIDAQAB"
    }

    init {
        startConnection()
    }

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                } else {
                    _isPremium.value = false
                }
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection in the next request
            }
        })
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _isPremium.value = purchases.any { purchase ->
                    purchase.products.contains(PREMIUM_PRODUCT_ID) && 
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
            } else {
                _isPremium.value = false
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        _isPremium.value = true
                    }
                }
            } else {
                _isPremium.value = true
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList[0]
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .build()
                        )
                    )
                    .build()
                billingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }
}
