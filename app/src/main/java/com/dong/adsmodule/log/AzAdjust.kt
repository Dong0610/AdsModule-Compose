@file:Suppress("unused")

package com.dong.adsmodule.log

import android.content.Context
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.google.android.gms.ads.AdValue

object AzAdjust {

    @JvmStatic
    fun trackAdRevenue(id: String) {
        Adjust.trackAdRevenue(AdjustAdRevenue(id))
    }

    @JvmStatic
    fun pushTrackTokenFcm(token: String, context: Context) {
        Adjust.setPushToken(token, context)
    }

    @JvmStatic
    fun onTrackEvent(eventName: String) {
        Adjust.trackEvent(AdjustEvent(eventName))
    }

    @JvmStatic
    fun onTrackEvent(eventName: String, id: String) {
        val e = AdjustEvent(eventName)
        e.setCallbackId(id)
        Adjust.trackEvent(e)
    }

    @JvmStatic
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) {
        val e = AdjustEvent(eventName)
        e.setRevenue(revenue.toDouble(), currency)
        Adjust.trackEvent(e)
    }

    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String) {
//        onTrackRevenue(
//            AzAds.getInstance().adConfig.adjustConfig.eventNamePurchase,
//            revenue,
//            currency
//        )
    }

    @JvmStatic
    fun onTrackImpression() {
//        val evt = AzAds.getInstance().adConfig.adjustConfig.eventAdImpression
//        if (!evt.isNullOrEmpty()) {
//            Adjust.trackEvent(AdjustEvent(evt))
//        }
    }

    @JvmStatic
    fun pushTrackEventAdmob(adValue: AdValue, adSourceName: String) {
        val ar = AdjustAdRevenue("admob_sdk")
        val revenue = adValue.valueMicros.toDouble() / 1_000_000.0
        ar.setRevenue(revenue, adValue.currencyCode)
        ar.setAdRevenueNetwork(adSourceName)
        Adjust.trackAdRevenue(ar)
    }


    /**
     * Kept for compatibility (called from AzLogEventManager).
     * Logs impression value as a revenue event.
     */
    @JvmStatic
    fun a(revenue: Double, currency: String) {
//        val e = AdjustEvent(
//            AzAds.getInstance().adConfig.adjustConfig.eventAdImpressionValue
//        )
//        e.setRevenue(revenue, currency)
//        Adjust.trackEvent(e)
    }
}
