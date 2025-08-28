package com.dong.adsmodule.ads.event

import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd

open class AdCallback {
    // Common
    open fun onAdLoaded() {}
    open fun onAdFailedToLoad(error: LoadAdError?) {}
    open fun onAdImpression() {}
    open fun onAdClicked() {}
    open fun onAdClosed() {}
    open fun onAdClosedByTime() {}
    open fun onAdFailedToShow(error: AdError?) {}
    open fun onNextAction() {}
    //Splash
    fun onAdSplashReady() {}
    // Interstitial
    open fun onInterstitialLoad(ad: InterstitialAd?) {}
    open fun onInterstitialShow() {}
    // Rewarded
    open fun onRewardAdLoaded(ad: RewardedAd?) {}
    open fun onRewardedInterstitialLoaded(ad: RewardedInterstitialAd?) {}
    open fun onUserEarnedReward(item: RewardItem) {}
    open fun onRewardedAdClosed() {}
    open fun onRewardedAdFailedToShow(code: Int) {}
    // Native
    open fun onUnifiedNativeAdLoaded(ad: NativeAd) {}
}