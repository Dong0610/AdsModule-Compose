@file:Suppress("unused")

package com.dong.adsmodule.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SharePreferenceUtils {

    private const val PREF = "ad_module_pref"

    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_CURRENT_TOTAL_REVENUE_AD = "KEY_CURRENT_TOTAL_REVENUE_AD"
    private const val KEY_CURRENT_TOTAL_REVENUE_001_AD = "KEY_CURRENT_TOTAL_REVENUE_001_AD"
    private const val KEY_PUSH_EVENT_REVENUE_3_DAY = "KEY_PUSH_EVENT_REVENUE_3_DAY"
    private const val KEY_PUSH_EVENT_REVENUE_7_DAY = "KEY_PUSH_EVENT_REVENUE_7_DAY"
    private const val KEY_LAST_IMPRESSION_INTERSTITIAL_TIME = "KEY_LAST_IMPRESSION_INTERSTITIAL_TIME"
    private const val KEY_COMPLETE_RATED = "COMPLETE_RATED"

    private val Context.prefs: SharedPreferences
        get() = getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // --- Install time ---

    fun getInstallTime(context: Context): Long =
        context.prefs.getLong(KEY_INSTALL_TIME, 0L)


    fun setInstallTime(context: Context) {
        context.prefs.edit { putLong(KEY_INSTALL_TIME, System.currentTimeMillis()) }
    }

    // --- Total revenue (in USD, float) ---

    fun getCurrentTotalRevenueAd(context: Context): Float =
        context.prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f)

    /**
     * @param revenueMicros Revenue in micros (1e-6). Will be converted to whole currency.
     */

    fun updateCurrentTotalRevenueAd(context: Context, revenueMicros: Float) {
        val newValue = getCurrentTotalRevenueAd(context) + (revenueMicros / 1_000_000f)
        context.prefs.edit { putFloat(KEY_CURRENT_TOTAL_REVENUE_AD, newValue) }
    }

    // --- Rolling 0.01 tracking bucket (raw float) ---

    fun getCurrentTotalRevenue001Ad(context: Context): Float =
        context.prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, 0f)


    fun updateCurrentTotalRevenue001Ad(context: Context, revenue: Float) {
        context.prefs.edit { putFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, revenue) }
    }

    // --- 3-day / 7-day push flags ---

    fun isPushRevenue3Day(context: Context): Boolean =
        context.prefs.getBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, false)


    fun setPushedRevenue3Day(context: Context) {
        context.prefs.edit { putBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, true) }
    }


    fun isPushRevenue7Day(context: Context): Boolean =
        context.prefs.getBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, false)


    fun setPushedRevenue7Day(context: Context) {
        context.prefs.edit { putBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, true) }
    }

    // --- Interstitial impression timestamp ---

    fun getLastImpressionInterstitialTime(context: Context): Long =
        context.prefs.getLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, 0L)


    fun setLastImpressionInterstitialTime(context: Context) {
        context.prefs.edit {
            putLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, System.currentTimeMillis())
        }
    }

    // --- Rating flag ---

    fun getCompleteRated(context: Context): Boolean =
        context.prefs.getBoolean(KEY_COMPLETE_RATED, false)


    fun setCompleteRated(context: Context, isCompleteRated: Boolean) {
        context.prefs.edit { putBoolean(KEY_COMPLETE_RATED, isCompleteRated) }
    }
}
