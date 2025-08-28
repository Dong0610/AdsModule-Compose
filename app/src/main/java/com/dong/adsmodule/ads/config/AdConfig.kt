package com.dong.adsmodule.ads.config

import android.app.Application

class AdConfig(
    val application: Application,
    mediationProvider: Int = PROVIDER_ADMOB,
    environment: String = ENVIRONMENT_PRODUCTION
) {
    companion object {
        const val PROVIDER_ADMOB = 0
        const val PROVIDER_MAX = 1
        const val ENVIRONMENT_DEVELOP = "develop"
        const val ENVIRONMENT_PRODUCTION = "production"
        const val DEFAULT_TOKEN_FACEBOOK_SDK = "client_token"
    }

    private var numberOfTimesReloadAds: Int = 0
    private var mediationProvider: Int = 0
    private var isVariantDev: Boolean = environment == ENVIRONMENT_DEVELOP
    private var adjustConfig: AdjustConfig? = null
    private var adsConsentConfig: AdsConsentConfig? = null
    private var eventNamePurchase: String = ""
    private var idAdResume: String? = null
    private var idAdResumeHigh: String? = null
    private var listDeviceTest: List<String> = emptyList()
    private var enableAdResume: Boolean = false
    private var facebookClientToken: String = DEFAULT_TOKEN_FACEBOOK_SDK
    private var intervalInterstitialAd: Int = 0
    // region getters & setters
    fun getNumberOfTimesReloadAds(): Int = numberOfTimesReloadAds
    fun setNumberOfTimesReloadAds(times: Int) {
        numberOfTimesReloadAds = times
    }

    fun getMediationProvider(): Int = mediationProvider
    fun setMediationProvider(provider: Int) {
        mediationProvider = provider
    }
    fun setVariant(isDev: Boolean) {
        isVariantDev = isDev
    }
    fun setEnvironment(environment: String) {
        isVariantDev = environment == ENVIRONMENT_DEVELOP
    }

    fun getAdjustConfig(): AdjustConfig? = adjustConfig
    fun setAdjustConfig(config: AdjustConfig) {
        adjustConfig = config
    }

    fun getAdsConsentConfig(): AdsConsentConfig? = adsConsentConfig
    fun setAdsConsentConfig(config: AdsConsentConfig) {
        adsConsentConfig = config
    }

    fun getEventNamePurchase(): String = eventNamePurchase
    fun setEventNamePurchase(name: String) {
        eventNamePurchase = name
    }

    fun isVariantDev(): Boolean = isVariantDev

    fun getIdAdResume(): String? = idAdResume
    fun setIdAdResume(id: String) {
        idAdResume = id
        enableAdResume = true
    }

    fun getIdAdResumeHigh(): String? = idAdResumeHigh
    fun setIdAdResumeHigh(id: String) {
        idAdResumeHigh = id
    }

    fun isEnableAdResume(): Boolean = enableAdResume

    fun getListDeviceTest(): List<String> = listDeviceTest
    fun setListDeviceTest(list: List<String>) {
        listDeviceTest = list
    }

    fun getIntervalInterstitialAd(): Int = intervalInterstitialAd
    fun setIntervalInterstitialAd(interval: Int) {
        intervalInterstitialAd = interval
    }

    fun getFacebookClientToken(): String = facebookClientToken
    fun setFacebookClientToken(token: String) {
        facebookClientToken = token
    }
    // endregion
}
