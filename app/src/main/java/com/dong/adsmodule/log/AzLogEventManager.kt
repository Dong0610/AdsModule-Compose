package com.dong.adsmodule.log

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.dong.adsmodule.utils.AdType
import com.dong.adsmodule.utils.AppUtil
import com.dong.adsmodule.utils.SharePreferenceUtils
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.ResponseInfo

object AzLogEventManager {
    private const val TAG = "AzLogEventManager"
    @JvmStatic
    fun logPaidAdImpression(
        context: Context,
        adValue: AdValue,
        adUnitId: String,
        responseInfo: ResponseInfo,
    ) {
        val mediation = responseInfo.mediationAdapterClassName ?: ""
        val adSourceName = responseInfo.loadedAdapterResponseInfo?.adSourceName ?: ""
        val revenueMicros = adValue.valueMicros.toFloat()
        // Store/forward revenue and fan out to analytics SDKs
        logRevenueMicros(
            context = context,
            revenueMicros = revenueMicros,
            precision = adValue.precisionType,
            adUnitId = adUnitId,
            network = mediation,
            mediationProvider = 0
        )

        AzAdjust.pushTrackEventAdmob(adValue, adSourceName)
    }
    // ------------------------------------------------------------------------
    private fun logRevenueMicros(
        context: Context,
        revenueMicros: Float,
        precision: Int,
        adUnitId: String,
        network: String?,
        mediationProvider: Int
    ) {
        Log.d(
            TAG,
            String.format(
                "Paid event of value %.0f microcents in currency USD of precision %s%n occurred for ad unit %s from ad network %s. mediation provider: %s%n",
                revenueMicros, precision, adUnitId, network, mediationProvider
            )
        )
        val extras = Bundle().apply {
            putDouble("valuemicros", revenueMicros.toDouble())
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network ?: "")
        }
        // Also emit normalized value (currency units) to other trackers
        logRevenueUnits(
            context = context,
            value = revenueMicros.toDouble() / 1_000_000.0,
            precision = precision,
            adUnitId = adUnitId,
            network = network ?: "",
            mediationProvider = mediationProvider
        )

        FirebaseAnalyticsUtil.logEventWithAds(context, extras)
        SharePreferenceUtils.updateCurrentTotalRevenueAd(context, revenueMicros)
        logCurrentTotalRevenueAd(context, "event_current_total_revenue_ad")

        AppUtil.currentTotalRevenue001Ad += revenueMicros
        SharePreferenceUtils.updateCurrentTotalRevenue001Ad(
            context,
            AppUtil.currentTotalRevenue001Ad
        )

        logTotalRevenue001Ad(context)
        logTotalRevenueAdIn3DaysIfNeed(context)
        logTotalRevenueAdIn7DaysIfNeed(context)
    }

    private fun logRevenueUnits(
        context: Context,
        value: Double,
        precision: Int,
        adUnitId: String,
        network: String,
        mediationProvider: Int
    ) {
        val bundle = Bundle().apply {
            putDouble("value", value)
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network)
        }
        // Keep original method names used by your SDK wrappers
        AzAdjust.a(value, "USD")
        FirebaseAnalyticsUtil.a(context, bundle, mediationProvider)
    }
    // ------------------------------------------------------------------------
    @JvmStatic
    fun logClickAdsEvent(context: Context, adUnitId: String) {
        Log.d(TAG, "User click ad for ad unit $adUnitId.")
        val b = Bundle().apply { putString("ad_unit_id", adUnitId) }
        FirebaseAnalyticsUtil.logClickAdsEvent(context, b)
    }
    @JvmStatic
    fun logCurrentTotalRevenueAd(context: Context, eventName: String) {
        val value = SharePreferenceUtils.getCurrentTotalRevenueAd(context)
        val b = Bundle().apply { putFloat("value", value) }
        FirebaseAnalyticsUtil.logCurrentTotalRevenueAd(context, eventName, b)
    }
    @JvmStatic
    fun logTotalRevenue001Ad(context: Context) {
        val value = AppUtil.currentTotalRevenue001Ad / 1_000_000f
        if (value.toDouble() >= 0.01) {
            AppUtil.currentTotalRevenue001Ad = 0f
            SharePreferenceUtils.updateCurrentTotalRevenue001Ad(context, 0f)
            val b = Bundle().apply { putFloat("value", value) }
            FirebaseAnalyticsUtil.logTotalRevenue001Ad(context, b)
        }
    }
    @JvmStatic
    fun logTotalRevenueAdIn3DaysIfNeed(context: Context) {
        val install = SharePreferenceUtils.getInstallTime(context)
        if (!SharePreferenceUtils.isPushRevenue3Day(context) &&
            System.currentTimeMillis() - install >= 259_200_000L
        ) {
            Log.d(TAG, "logTotalRevenueAdAt3DaysIfNeed:")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_3_days")
            SharePreferenceUtils.setPushedRevenue3Day(context)
        }
    }
    @JvmStatic
    fun logTotalRevenueAdIn7DaysIfNeed(context: Context) {
        val install = SharePreferenceUtils.getInstallTime(context)
        if (!SharePreferenceUtils.isPushRevenue7Day(context) &&
            System.currentTimeMillis() - install >= 604_800_000L
        ) {
            Log.d(TAG, "logTotalRevenueAdAt7DaysIfNeed:")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_7_days")
            SharePreferenceUtils.setPushedRevenue7Day(context)
        }
    }
    // Pass-throughs to your tracking SDKs (kept same names/signatures)
    @JvmStatic
    fun trackAdRevenue(id: String) = AzAdjust.trackAdRevenue(id)
    @JvmStatic
    fun onTrackEvent(eventName: String) = AzAdjust.onTrackEvent(eventName)
    @JvmStatic
    fun onTrackTokenFcm(token: String, context: Context) =
        AzAdjust.pushTrackTokenFcm(token, context)

    @JvmStatic
    fun onTrackEvent(eventName: String, id: String) = AzAdjust.onTrackEvent(eventName, id)
    @JvmStatic
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) =
        AzAdjust.onTrackRevenue(eventName, revenue, currency)
    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String, idPurchase: String, typeIAP: Int) {
        AzAdjust.onTrackRevenuePurchase(revenue, currency)
    }

    @JvmStatic
    fun pushTrackEventAdmob(adValue: AdValue, adSourceName: String) =
        AzAdjust.pushTrackEventAdmob(adValue, adSourceName)

    @JvmStatic
    fun onTrackImpression() = AzAdjust.onTrackImpression()
}

