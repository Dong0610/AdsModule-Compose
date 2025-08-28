package com.dong.adsmodule.log

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

object FirebaseAnalyticsUtil {

    private const val TAG = "FirebaseAnalyticsUtil"
    private var firebase: FirebaseAnalytics? = null

    @JvmStatic
    fun init(context: Context) {
        firebase = FirebaseAnalytics.getInstance(context)
    }

    @JvmStatic
    fun logEventWithAds(context: Context, params: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression", params)
    }

    /**
     * Kept signature/name for compatibility with existing calls.
     * mediationProvider: 1 = MAX, else AdMob (original behavior).
     */
    @JvmStatic
    fun a(context: Context, bundle: Bundle, mediationProvider: Int) {
        val event = if (mediationProvider == 1)
            "max_paid_ad_impression_value"
        else
            "paid_ad_impression_value"
        FirebaseAnalytics.getInstance(context).logEvent(event, bundle)
    }

    @JvmStatic
    fun logClickAdsEvent(context: Context, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("event_user_click_ads", bundle)
    }

    @JvmStatic
    fun logCurrentTotalRevenueAd(context: Context, eventName: String, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)
    }

    @JvmStatic
    fun logEventTracking(context: Context, eventName: String, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)
    }

    @JvmStatic
    fun logTotalRevenue001Ad(context: Context, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression_value_001", bundle)
    }

    @JvmStatic
    fun logConfirmPurchaseGoogle(orderId: String, purchaseId: String, purchaseToken: String) {
        val (part1, part2) =
            if (purchaseToken.length > 100)
                purchaseToken.substring(0, 100) to purchaseToken.substring(100)
            else
                purchaseToken to "EMPTY"

        val bundle = Bundle().apply {
            putString("purchase_order_id", orderId)
            putString("purchase_package_id", purchaseId)
            putString("purchase_token_part_1", part1)
            putString("purchase_token_part_2", part2)
        }

        firebase?.logEvent("confirm_purchased_with_google", bundle)
        Log.d(TAG, "logConfirmPurchaseGoogle: tracked")
    }

    @JvmStatic
    fun logRevenuePurchase(value: Double) {
        val bundle = Bundle().apply {
            putDouble("value", value)
            putString("currency", "USD")
        }
        firebase?.logEvent("user_purchased_value", bundle)
    }
}
