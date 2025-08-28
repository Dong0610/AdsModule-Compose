package com.dong.adsmodule.purchase

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.dong.adsmodule.ads.event.BillingListener
import com.dong.adsmodule.ads.event.PurchaseListener
import com.dong.adsmodule.ads.event.UpdatePurchaseListener
import com.dong.adsmodule.log.AzLogEventManager
import com.dong.adsmodule.log.FirebaseAnalyticsUtil
import com.dong.adsmodule.utils.AppUtil
import java.text.NumberFormat
import java.util.Currency
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.toSet
import kotlin.math.max

/**
 * Google Play Billing 8.0.0–ready purchase helper.
 * Variable names are clarified for readability while preserving public API behavior.
 */
class AppPurchase private constructor() {
    companion object {
        private const val TAG = "PurchaseEG"
        const val PRODUCT_ID_TEST = "android.test.purchased"
        @Volatile
        private var instance: AppPurchase? = null
        @JvmStatic
        fun getInstance(): AppPurchase =
            instance ?: synchronized(this) { instance ?: AppPurchase().also { instance = it } }
    }

    private var playBillingClient: BillingClient? = null
    private var isBillingConnected: Boolean = false
    private var hasInitCallbackFired: Boolean = false
    private val cachedInAppProducts = ConcurrentHashMap<String, ProductDetails>()
    /** Cache of SUBS productId -> ProductDetails */
    private val cachedSubscriptionProducts = ConcurrentHashMap<String, ProductDetails>()
    /** Products to query for INAPP details */
    private var pendingInAppQueryProducts: List<QueryProductDetailsParams.Product> = emptyList()
    /** Products to query for SUBS details */
    private var pendingSubsQueryProducts: List<QueryProductDetailsParams.Product> = emptyList()
    /** Items configured by the app (used for picking offers/trials) */
    private var configuredPurchaseItems: List<PurchaseItem> = emptyList()
    // endregion
    // region Listeners
    private var onPurchaseListener: PurchaseListener? = null
    private var onPurchaseUpdateListener: UpdatePurchaseListener? = null
    private var onBillingInitListener: BillingListener? = null
    // endregion
    // region Purchase options/flags
    /** Auto-consume INAPP purchases if true */
    private var shouldConsumeInAppPurchases: Boolean = false
    /** Multiplier (1.0 = no discount). When showing sale prices, we divide by this value. */
    private var discountMultiplier: Double = 1.0
    // endregion
    // region Last-requested purchase metadata
    private var lastRequestedProductId: String = ""
    @TYPE_IAP
    private var lastRequestedProductType: Int = TYPE_IAP.PURCHASE
    // endregion
    // region Ownership state
    /** Currently owned subscription purchases (from queries) */
    private val ownedSubscriptionPurchases = mutableListOf<PurchaseResult>()
    /** Currently owned one-time in-app purchases (from queries) */
    private val ownedInAppPurchases = mutableListOf<PurchaseResult>()
    /** Convenience flag: true if user owns any purchase/sub */
    private var hasAnyPurchase: Boolean = false
    /** Last completed purchase orderId (when available) */
    private var lastPurchaseOrderId: String = ""
    // endregion
    // region Optional init-timeout
    private var initTimeoutHandler: Handler? = null
    private var initTimeoutRunnable: Runnable? = null
    // endregion
    // region Google Play callbacks
    private val purchasesUpdatedCallback = PurchasesUpdatedListener { result, purchases ->
        Log.d(TAG, "onPurchasesUpdated code=${result.responseCode} msg=${result.debugMessage}")
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach(::handlePurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                onPurchaseListener?.onUserCancelBilling()
                Log.d(TAG, "User canceled billing flow")
            }
            else -> {
                onPurchaseListener?.displayErrorMessage(result.debugMessage ?: "Billing error")
                Log.w(TAG, "Billing error: ${result.debugMessage}")
            }
        }
    }
    private val billingConnectionListener = object : BillingClientStateListener {
        override fun onBillingServiceDisconnected() {
            isBillingConnected = false
            Log.w(TAG, "Billing service disconnected")
        }

        override fun onBillingSetupFinished(result: BillingResult) {
            Log.d(TAG, "onBillingSetupFinished: ${result.responseCode} ${result.debugMessage}")
            initTimeoutHandler?.removeCallbacks(initTimeoutRunnable ?: return)

            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                isBillingConnected = true
                // Load product details then verify ownership
                queryAllConfiguredProductDetails()
                if (!hasInitCallbackFired) verifyPurchased(isCallback = true)
            } else {
                // Notify anyway so UI can proceed
                maybeNotifyInitFinished(isCallback = true, code = result.responseCode)
                Log.e(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
            }
            hasInitCallbackFired = true
        }
    }
    // endregion
    // region Public API (kept names/behavior)
    fun setPurchaseListener(listener: PurchaseListener?) {
        onPurchaseListener = listener
    }

    fun setUpdatePurchaseListener(listener: UpdatePurchaseListener?) {
        onPurchaseUpdateListener = listener
    }

    fun setBillingListener(listener: BillingListener?) {
        onBillingInitListener = listener
        if (isBillingConnected) {
            listener?.onInitBillingFinished(BillingClient.BillingResponseCode.OK)
            hasInitCallbackFired = true
        }
    }

    fun setBillingListener(listener: BillingListener?, timeoutMs: Int) {
        Log.d(TAG, "setBillingListener(timeout=$timeoutMs)")
        onBillingInitListener = listener
        if (!hasInitCallbackFired && !isBillingConnected) {
            initTimeoutHandler?.removeCallbacks(initTimeoutRunnable ?: Runnable {})
            initTimeoutHandler = Handler(Looper.getMainLooper())
            initTimeoutRunnable = Runnable {
                Log.d(TAG, "Billing init timeout fired")
                hasInitCallbackFired = true
                listener?.onInitBillingFinished(BillingClient.BillingResponseCode.SERVICE_TIMEOUT)
            }.also { initTimeoutHandler?.postDelayed(it, timeoutMs.toLong()) }
        } else {
            listener?.onInitBillingFinished(BillingClient.BillingResponseCode.OK)
        }
    }

    fun setConsumePurchase(enabled: Boolean) {
        shouldConsumeInAppPurchases = enabled
    }

    fun setDiscount(discount: Double) {
        discountMultiplier = discount
    }

    fun getDiscount(): Double = discountMultiplier

    fun setPurchase(purchased: Boolean) {
        hasAnyPurchase = purchased
    }

    fun isPurchased(): Boolean = hasAnyPurchase
    fun isPurchased(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = hasAnyPurchase
    fun getIdPurchased(): String = lastPurchaseOrderId

    fun setEventConsumePurchaseTest(view: View) {
        view.setOnClickListener {
            if (AppUtil.VARIANT_DEV) {
                Log.d(TAG, "consume test purchase")
                consumePurchase(PRODUCT_ID_TEST)
            }
        }
    }
    /** Legacy init with raw product IDs (kept for compatibility). */
    @Deprecated("Use initBilling(Application, MutableList<PurchaseItem>)")
    fun initBilling(
        application: Application,
        listInappIds: MutableList<String>,
        listSubsIds: List<String>
    ) {
        if (AppUtil.VARIANT_DEV && PRODUCT_ID_TEST !in listInappIds) {
            listInappIds.add(PRODUCT_ID_TEST)
        }
        pendingSubsQueryProducts = listSubsIds.map { id ->
            queryProduct(id, BillingClient.ProductType.SUBS)
        }
        pendingInAppQueryProducts = listInappIds.map { id ->
            queryProduct(id, BillingClient.ProductType.INAPP)
        }
        buildBillingClient(application).startConnection(billingConnectionListener)
    }
    /** Preferred init with typed purchase items. */
    fun initBilling(application: Application, items: MutableList<PurchaseItem>) {
        if (AppUtil.VARIANT_DEV && items.none { it.productId == PRODUCT_ID_TEST }) {
            items.add(
                PurchaseItem(
                    PRODUCT_ID_TEST,
                    trialId = "",
                    type = PurchaseItem.ProductType.INAPP
                )
            )
        }
        configuredPurchaseItems = items
        val inapp = mutableListOf<QueryProductDetailsParams.Product>()
        val subs = mutableListOf<QueryProductDetailsParams.Product>()
        items.forEach { item ->
            when (item.type) {
                PurchaseItem.ProductType.INAPP ->
                    inapp += queryProduct(item.productId, BillingClient.ProductType.INAPP)
                PurchaseItem.ProductType.SUBS ->
                    subs += queryProduct(item.productId, BillingClient.ProductType.SUBS)
            }
        }
        pendingInAppQueryProducts = inapp
        pendingSubsQueryProducts = subs

        buildBillingClient(application).startConnection(billingConnectionListener)
    }
    /** Query current ownership and optionally notify init finished. */
    fun verifyPurchased(isCallback: Boolean) {
        // INAPP
        playBillingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            Log.d(TAG, "verifyPurchased INAPP code=${result.responseCode} size=${purchases.size}")
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { purchase ->
                    pendingInAppQueryProducts.forEach { prod ->
                        if (purchase.products.contains(prod._productIdCompat())) {
                            upsertOwned(
                                ownedInAppPurchases,
                                PurchaseResult.from(purchase, packageName = purchase.packageName)
                            )
                            hasAnyPurchase = true
                        }
                    }
                }
            }
            maybeNotifyInitFinished(isCallback, result.responseCode)
        }
        // SUBS
        playBillingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            Log.d(TAG, "verifyPurchased SUBS code=${result.responseCode} size=${purchases.size}")
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { purchase ->
                    pendingSubsQueryProducts.forEach { prod ->
                        if (purchase.products.contains(prod._productIdCompat())) {
                            upsertOwned(
                                ownedSubscriptionPurchases,
                                PurchaseResult.from(purchase, packageName = purchase.packageName)
                            )
                            hasAnyPurchase = true
                        }
                    }
                }
            }
            maybeNotifyInitFinished(isCallback, result.responseCode)
        }
    }
    @Suppress("FunctionName")
    private fun QueryProductDetailsParams.Product._productIdCompat(): String {
        // Thử getter “đẹp” nếu có (một số version có getProductId)
        runCatching {
            return this::class.java.getMethod("getProductId").invoke(this) as String
        }
        // Fallback về tên obfuscate hiện tại
        runCatching {
            val m = this::class.java.getMethod("zza")
            @Suppress("UNCHECKED_CAST")
            return m.invoke(this) as String
        }
        // Không nên rơi vào đây, nhưng để an toàn:
        throw IllegalStateException("Cannot resolve productId from QueryProductDetailsParams.Product")
    }
    /** Refresh ownership and notify once via UpdatePurchaseListener when both queries return. */
    fun updatePurchaseStatus() {
        var inappQueryFinished = false
        var subsQueryFinished = false
        val inappIds = pendingInAppQueryProducts.map { it._productIdCompat() }.toSet()
        val subsIds = pendingSubsQueryProducts.map { it._productIdCompat() }.toSet()
        // INAPP
        playBillingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { purchase ->
                    // purchase.products: List<String> (productIds)
                    if (purchase.products.any { it in inappIds }) {
                        upsertOwned(
                            ownedInAppPurchases,
                            PurchaseResult.from(purchase, packageName = purchase.packageName)
                        )
                    }
                }
            }
            inappQueryFinished = true
            if (inappQueryFinished && subsQueryFinished) finalizeUpdateAndNotify()
        }
        // SUBS
        playBillingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { purchase ->
                    if (purchase.products.any { it in subsIds }) {
                        upsertOwned(
                            ownedSubscriptionPurchases,
                            PurchaseResult.from(purchase, packageName = purchase.packageName)
                        )
                    }
                }
            }
            subsQueryFinished = true
            if (inappQueryFinished && subsQueryFinished) finalizeUpdateAndNotify()
        }
    }
    /** Start a one-time in-app purchase. */
    fun purchase(activity: Activity, productId: String): String {
        val details = cachedInAppProducts[productId]
            ?: return run {
                onPurchaseListener?.displayErrorMessage("Billing not initialized")
                ""
            }

        if (AppUtil.VARIANT_DEV) {
            PurchaseDevBottomSheet(1, details, activity, onPurchaseListener).show()
            return ""
        }

        lastRequestedProductId = productId
        lastRequestedProductType = TYPE_IAP.PURCHASE
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        return launchBillingFlow(activity, flowParams)
    }
    /** Start a subscription purchase. Prefers offer matching the configured trialId if any. */
    fun subscribe(activity: Activity, subsId: String): String {
        val details = cachedSubscriptionProducts[subsId]
            ?: return run {
                onPurchaseListener?.displayErrorMessage("Billing not initialized")
                ""
            }

        if (AppUtil.VARIANT_DEV) {
            purchase(activity, PRODUCT_ID_TEST)
            return "Billing test"
        }
        val offerToken = pickBestOfferTokenFor(details, subsId)
            ?: return "No eligible offer for this subscription"

        lastRequestedProductId = subsId
        lastRequestedProductType = TYPE_IAP.SUBSCRIPTION
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        return launchBillingFlow(activity, flowParams)
    }

    fun consumePurchase(productId: String) {
        Log.d(TAG, "consumePurchase: $productId")
        playBillingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val target = purchases.firstOrNull { it.products.contains(productId) }
                ?: return@queryPurchasesAsync
            val params = ConsumeParams.newBuilder().setPurchaseToken(target.purchaseToken).build()
            playBillingClient?.consumeAsync(params) { br, _ ->
                if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "consumePurchase: OK")
                    updatePurchaseStatus()
                } else {
                    Log.e(TAG, "consumePurchase error: ${br.debugMessage}")
                }
            }
        }
    }
    // endregion
    // region Info getters (name, price, currency, periods)
    fun getPrice(productId: String): String =
        cachedInAppProducts[productId]?.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()

    fun getName(productId: String, @TYPE_IAP typeIap: Int): String =
        when (typeIap) {
            TYPE_IAP.SUBSCRIPTION -> cachedSubscriptionProducts[productId]?.name.orEmpty()
            else -> cachedInAppProducts[productId]?.name.orEmpty()
        }

    fun getPeriod(productId: String): String {
        val d = cachedSubscriptionProducts[productId] ?: return ""
        val phases =
            d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList ?: return ""
        return phases.firstOrNull()?.billingPeriod.orEmpty()
    }

    fun getTrialPeriod(productId: String): String {
        val d = cachedSubscriptionProducts[productId] ?: return ""
        d.subscriptionOfferDetails.orEmpty().forEach { offer ->
            offer.pricingPhases.pricingPhaseList.forEach { phase ->
                if (phase.priceAmountMicros == 0L && phase.billingCycleCount == 1) {
                    return phase.billingPeriod
                }
            }
        }
        return ""
    }

    fun getPriceSub(productId: String): String {
        val d = cachedSubscriptionProducts[productId] ?: return ""
        val phases =
            d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList ?: return ""
        return phases.lastOrNull()?.formattedPrice.orEmpty()
    }

    fun getPricePricingPhaseList(productId: String): List<ProductDetails.PricingPhase>? {
        val d = cachedSubscriptionProducts[productId] ?: return null
        return d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList
    }

    fun getIntroductorySubPrice(productId: String): String {
        val d = cachedSubscriptionProducts[productId] ?: return ""
        d.oneTimePurchaseOfferDetails?.let { return it.formattedPrice }
        val phases =
            d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList ?: return ""
        return phases.lastOrNull()?.formattedPrice.orEmpty()
    }

    fun getCurrency(productId: String, @TYPE_IAP typeIAP: Int): String {
        val d = when (typeIAP) {
            TYPE_IAP.SUBSCRIPTION -> cachedSubscriptionProducts[productId]
            else -> cachedInAppProducts[productId]
        } ?: return ""
        return if (typeIAP == TYPE_IAP.PURCHASE) {
            d.oneTimePurchaseOfferDetails?.priceCurrencyCode.orEmpty()
        } else {
            val phases = d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList
                ?: return ""
            phases.lastOrNull()?.priceCurrencyCode.orEmpty()
        }
    }

    fun getPriceWithoutCurrency(productId: String, @TYPE_IAP typeIAP: Int): Double {
        val d = when (typeIAP) {
            TYPE_IAP.SUBSCRIPTION -> cachedSubscriptionProducts[productId]
            else -> cachedInAppProducts[productId]
        } ?: return 0.0
        return if (typeIAP == TYPE_IAP.PURCHASE) {
            d.oneTimePurchaseOfferDetails?.priceAmountMicros?.toDouble() ?: 0.0
        } else {
            val phases = d.subscriptionOfferDetails?.lastOrNull()?.pricingPhases?.pricingPhaseList
                ?: return 0.0
            phases.lastOrNull()?.priceAmountMicros?.toDouble() ?: 0.0
        }
    }

    fun getPriceWithCurrency(productId: String, @TYPE_IAP typeIAP: Int): String {
        val micros = getPriceWithoutCurrency(productId, typeIAP)
        val currency = getCurrency(productId, typeIAP)
        return formatCurrency(micros / 1_000_000.0, currency)
    }

    fun getPriceWithCurrency(productId: String, @TYPE_IAP typeIAP: Int, sale: Double): String {
        val micros = getPriceWithoutCurrency(productId, typeIAP)
        val currency = getCurrency(productId, typeIAP)
        val divisor = sale.coerceAtLeast(0.000001)
        return formatCurrency((micros / 1_000_000.0) / divisor, currency)
    }

    fun getOwnerIdSubs(): List<PurchaseResult> = ownedSubscriptionPurchases.toList()
    fun getOwnerIdInApp(): List<PurchaseResult> = ownedInAppPurchases.toList()
    // endregion
    // region Internals
    private fun buildBillingClient(application: Application): BillingClient {
        val client = BillingClient.newBuilder(application)
            .setListener(purchasesUpdatedCallback)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts() // required since v6+
                    .build()
            )
            .build()
        playBillingClient = client
        return client
    }

    private fun queryProduct(id: String, type: String): QueryProductDetailsParams.Product =
        QueryProductDetailsParams.Product.newBuilder()
            .setProductId(id)
            .setProductType(type)
            .build()

    private fun queryAllConfiguredProductDetails() {
        // INAPP
        if (pendingInAppQueryProducts.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(pendingInAppQueryProducts)
                .build()

            playBillingClient?.queryProductDetailsAsync(params) { billingResult: BillingResult,
                                                                  productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "INAPP details count=${productDetailsList.productDetailsList.size}")
                    for (details in productDetailsList.productDetailsList) {
                        cachedInAppProducts[details.productId] = details
                    }
                } else {
                    android.util.Log.w(TAG, "INAPP details failed: ${billingResult.responseCode}")
                }
            }
        }
        // SUBS
        if (pendingSubsQueryProducts.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(pendingSubsQueryProducts)
                .build()

            playBillingClient?.queryProductDetailsAsync(params) { billingResult: BillingResult,
                                                                  productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    android.util.Log.d(
                        TAG,
                        "SUBS details count=${productDetailsList.productDetailsList.size}"
                    )
                    for (details in productDetailsList.productDetailsList) {
                        cachedSubscriptionProducts[details.productId] = details
                    }
                } else {
                    android.util.Log.w(TAG, "SUBS details failed: ${billingResult.responseCode}")
                }
            }
        }
    }

    private fun pickBestOfferTokenFor(details: ProductDetails, subsId: String): String? {
        val offers = details.subscriptionOfferDetails ?: return null
        val desiredTrialOfferId =
            configuredPurchaseItems.firstOrNull { it.productId == subsId }?.trialId
        val matchingTrial =
            offers.firstOrNull { it.offerId != null && it.offerId == desiredTrialOfferId }
        return (matchingTrial ?: offers.lastOrNull())?.offerToken
    }

    private fun launchBillingFlow(activity: Activity, params: BillingFlowParams): String {
        val code = playBillingClient?.launchBillingFlow(activity, params)?.responseCode
            ?: BillingClient.BillingResponseCode.ERROR
        return when (code) {
            BillingClient.BillingResponseCode.OK -> "Subscribed Successfully"
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                onPurchaseListener?.displayErrorMessage("Request Canceled"); "Request Canceled"
            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                onPurchaseListener?.displayErrorMessage("Network error."); "Network Connection down"
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                onPurchaseListener?.displayErrorMessage("Billing not supported for type of request")
                "Billing not supported for type of request"
            }
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "Item not available"
            BillingClient.BillingResponseCode.ERROR -> {
                onPurchaseListener?.displayErrorMessage("Error completing request"); "Error completing request"
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                "Play Store service is not connected now"
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> "Timeout"
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "Selected item is already owned"
            else -> ""
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "handlePurchase: orderId=${purchase.orderId}, state=${purchase.purchaseState}")
        // Track revenue (best effort) — ignore failures if analytics unavailable
        runCatching {
            val micros = getPriceWithoutCurrency(lastRequestedProductId, lastRequestedProductType)
            val currency = getCurrency(lastRequestedProductId, lastRequestedProductType)
            val value = (micros / 1_000_000.0).toFloat()
            AzLogEventManager.onTrackRevenuePurchase(
                value,
                currency,
                lastRequestedProductId,
                lastRequestedProductType
            )
        }

        onPurchaseListener?.let {
            hasAnyPurchase = true
            lastPurchaseOrderId = purchase.orderId ?: ""
            it.onProductPurchased(lastPurchaseOrderId, purchase.originalJson)
        }

        if (shouldConsumeInAppPurchases) {
            val params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            playBillingClient?.consumeAsync(params) { br, _ ->
                Log.d(TAG, "consumeAsync: ${br.debugMessage}")
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            playBillingClient?.acknowledgePurchase(ack) { br ->
                Log.d(TAG, "acknowledgePurchase: ${br.debugMessage}")
                if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                    FirebaseAnalyticsUtil.logConfirmPurchaseGoogle(
                        purchase.orderId ?: "",
                        lastRequestedProductId,
                        purchase.purchaseToken
                    )
                }
            }
        }
    }

    private fun upsertOwned(store: MutableList<PurchaseResult>, item: PurchaseResult) {
        val idx =
            store.indexOfFirst { it.products.containsAll(item.products) || it.purchaseToken == item.purchaseToken }
        if (idx >= 0) store[idx] = item else store += item
    }

    private fun maybeNotifyInitFinished(isCallback: Boolean, code: Int) {
        if (!isBillingConnected) return
        if (!hasInitCallbackFired && isCallback) {
            onBillingInitListener?.onInitBillingFinished(code)
            initTimeoutHandler?.removeCallbacks(initTimeoutRunnable ?: return)
        }
    }

    private fun finalizeUpdateAndNotify() {
        hasAnyPurchase = ownedInAppPurchases.isNotEmpty() || ownedSubscriptionPurchases.isNotEmpty()
        onPurchaseUpdateListener?.onUpdateFinished()
    }

    private fun formatCurrency(amount: Double, currencyCode: String): String {
        if (currencyCode.isBlank()) return ""
        val f = NumberFormat.getCurrencyInstance()
        f.maximumFractionDigits = max(f.maximumFractionDigits, 0)
        f.currency = Currency.getInstance(currencyCode)
        return f.format(amount)
    }
    // endregion
    // region Type marker
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.SOURCE)
    annotation class TYPE_IAP {
        companion object {
            const val PURCHASE = 1
            const val SUBSCRIPTION = 2
        }
    }
}
