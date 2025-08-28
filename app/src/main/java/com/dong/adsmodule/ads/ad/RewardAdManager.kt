@file:Suppress("unused")

package com.dong.adsmodule.ads.ad

import android.app.Activity
import android.content.Context
import com.dong.adsmodule.ads.event.AdCallback
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class RewardAdManager private constructor(
    private val app: Context,
    private val logger: Logger = Logger("RewardAds")
) {

    companion object {
        @Volatile private var instance: RewardAdManager? = null

        fun init(app: Context, logger: Logger = Logger("RewardAds")): RewardAdManager {
            return instance ?: synchronized(this) {
                instance ?: RewardAdManager(app, logger).also { instance = it }
            }
        }
        fun getInstance(): RewardAdManager =
            requireNotNull(instance) { "Call RewardAdManager.init(app) first." }
    }

    data class Logger(private val tag: String) {
        fun d(msg: String) = android.util.Log.d(tag, msg)
        fun e(msg: String, tr: Throwable? = null) = android.util.Log.e(tag, msg, tr)
    }

    // ===== State =====
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isShowingAd = false
    private var currentRewarded: RewardedAd? = null
    private var loadedAt: Long = 0L
    private var isEnabled = true
    private var isLoading = false
    private var loadJob: Job? = null
    private var rewardIds: List<String> = emptyList()
    private val freshnessMs = TimeUnit.HOURS.toMillis(4)

    var fullScreenContentCallback: FullScreenContentCallback? = null

    // ===== Control =====
    fun enable(enable: Boolean) { isEnabled = enable; logger.d("enable Reward = $enable") }
    fun isAdReady(): Boolean = currentRewarded.isValid(loadedAt, freshnessMs)
    fun cancelLoading() { loadJob?.cancel(); isLoading = false }

    // =========================
    // Public API: LOAD (waterfall) — KHÔNG show
    // =========================
    fun load(
        ids: List<String>,
        force: Boolean = false,
        timeoutPerIdMs: Long = 12_000L,
        adCallback: AdCallback? = null,
        onLoaded: (Boolean) -> Unit = {}
    ) {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) { logger.d("reward.load: empty ids"); onLoaded(false); return }
        rewardIds = clean

        if (!force && currentRewarded.isValid(loadedAt, freshnessMs)) {
            logger.d("reward.load: cache valid -> skip")
            adCallback?.onRewardAdLoaded(currentRewarded)
            adCallback?.onAdLoaded()
            onLoaded(true)
            return
        }
        if (isLoading && loadJob?.isActive == true) { logger.d("reward.load: already loading"); return }

        isLoading = true
        loadJob = scope.launch {
            val ad = loadFirstAvailableRewarded(rewardIds, adCallback, timeoutPerIdMs)
            isLoading = false
            if (ad != null) {
                currentRewarded = ad
                loadedAt = System.currentTimeMillis()
                adCallback?.onRewardAdLoaded(ad)
                adCallback?.onAdLoaded()
                logger.d("reward.load: success -> cached")
                onLoaded(true)
            } else {
                logger.d("reward.load: all ids failed")
                onLoaded(false)
            }
        }
    }

    // =========================
    // Public API: SHOW — chỉ show cache
    // =========================
    fun show(
        activity: Context,
        adCallback: AdCallback? = null,
        reloadAfterShow: Boolean = true,
        fireNextActionOnImpression: Boolean = true  // true: impression, false: showed
    ): Boolean {
        if (!isEnabled || isShowingAd) {
            logger.d("reward.show: blocked (enabled=$isEnabled, showing=$isShowingAd)")
            return false
        }
        val ad = currentRewarded.takeIf { it.isValid(loadedAt, freshnessMs) }
        if (ad == null) {
            logger.d("reward.show: no cached ad -> call load() first")
            adCallback?.onRewardedAdFailedToShow(-1)
            adCallback?.onAdFailedToShow(AdError(0, "No cached rewarded", "RewardAdManager"))
            // tuỳ flow của bạn: nếu muốn tiếp tục ngay khi không có ad:
            adCallback?.onNextAction()
            return false
        }
        showInternal(activity, ad, adCallback, reloadAfterShow, fireNextActionOnImpression)
        currentRewarded = null // consume
        return true
    }

    // =========================
    // Public API: LOAD + SHOW “đơn phát”
    // =========================
    fun loadAndShow(
        activity: Context,
        ids: List<String>,
        useCachedFirst: Boolean = true,
        timeoutPerIdMs: Long = 12_000L,
        reloadAfterShow: Boolean = true,
        fireNextActionOnImpression: Boolean = true,
        adCallback: AdCallback? = null
    ) {
        if (!isEnabled) {
            adCallback?.onRewardedAdFailedToShow(-1)
            adCallback?.onAdFailedToShow(AdError(0, "Disabled", "RewardAdManager"))
            adCallback?.onNextAction()
            return
        }
        val act = activity as? Activity ?: run {
            adCallback?.onRewardedAdFailedToShow(-1)
            adCallback?.onAdFailedToShow(AdError(0, "Context is not Activity", "RewardAdManager"))
            adCallback?.onNextAction()
            return
        }
        if (isShowingAd) { logger.d("reward.loadAndShow: already showing"); return }

        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            adCallback?.onRewardedAdFailedToShow(-1)
            adCallback?.onAdFailedToShow(AdError(0, "Empty ad units", "RewardAdManager"))
            adCallback?.onNextAction()
            return
        }

        if (useCachedFirst) {
            val cached = currentRewarded.takeIf { it.isValid(loadedAt, freshnessMs) }
            if (cached != null) {
                showInternal(act, cached, adCallback, reloadAfterShow, fireNextActionOnImpression)
                currentRewarded = null
                return
            }
        }

        scope.launch {
            val ad = loadFirstAvailableRewarded(clean, adCallback, timeoutPerIdMs)
            if (ad != null) {
                currentRewarded = ad
                loadedAt = System.currentTimeMillis()
                showInternal(act, ad, adCallback, reloadAfterShow, fireNextActionOnImpression)
                currentRewarded = null
            } else {
                adCallback?.onRewardedAdFailedToShow(-1)
                adCallback?.onAdFailedToShow(AdError(0, "All ids failed", "RewardAdManager"))
                adCallback?.onNextAction()
            }
        }
    }

    // =========================
    // Internal: load waterfall + timeout per ID
    // =========================
    private suspend fun loadFirstAvailableRewarded(
        ids: List<String>,
        adCallback: AdCallback?,
        timeoutPerIdMs: Long
    ): RewardedAd? = withContext(Dispatchers.Main) {
        val req = AdRequest.Builder().build()
        ids.forEachIndexed { idx, id ->
            val result = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine<Result<RewardedAd>> { cont ->
                        RewardedAd.load(app, id, req, object : RewardedAdLoadCallback() {
                            override fun onAdLoaded(ad: RewardedAd) {
                                logger.d("reward.load[$idx] SUCCESS id=$id")
                                if (cont.isActive) cont.resume(Result.success(ad), onCancellation = {})
                            }
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                logger.d("reward.load[$idx] FAIL id=$id code=${error.code} msg=${error.message}")
                                adCallback?.onAdFailedToLoad(error)
                                if (cont.isActive) cont.resume(
                                    Result.failure(Exception(error.message)),
                                    onCancellation = {}
                                )
                            }
                        })
                    }
                }
            } catch (_: TimeoutCancellationException) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<RewardedAd>(Exception("timeout"))
            } catch (t: Throwable) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<RewardedAd>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }

    // =========================
    // Internal: show + callback wiring
    // =========================
    private fun showInternal(
        context: Context,
        ad: RewardedAd,
        adCallback: AdCallback?,
        reloadAfterShow: Boolean,
        fireNextActionOnImpression: Boolean
    ) {
        val activity = context as? Activity ?: run {
            adCallback?.onRewardedAdFailedToShow(-1)
            adCallback?.onAdFailedToShow(AdError(0, "Context is not Activity", "RewardAdManager"))
            adCallback?.onNextAction()
            return
        }
        if (isShowingAd) { logger.d("reward.showInternal: already showing"); return }

        isShowingAd = true
        var nextActionFired = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // Nếu bạn muốn “chuyển màn” khi vừa mở full screen thay vì impression:
                if (!fireNextActionOnImpression && !nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()
                }
                fullScreenContentCallback?.onAdShowedFullScreenContent()
            }

            override fun onAdImpression() {
                adCallback?.onAdImpression()
                if (fireNextActionOnImpression && !nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()
                }
            }

            override fun onAdClicked() {
                adCallback?.onAdClicked()
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adCallback?.onRewardedAdFailedToShow(error.code)
                adCallback?.onAdFailedToShow(error)
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(error)
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction() // fallback không kẹt flow
                }
                isShowingAd = false
                if (reloadAfterShow && rewardIds.isNotEmpty()) {
                    load(rewardIds, force = false, adCallback = adCallback)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                adCallback?.onRewardedAdClosed()
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
                isShowingAd = false
                if (reloadAfterShow && rewardIds.isNotEmpty()) {
                    load(rewardIds, force = false, adCallback = adCallback)
                }
            }
        }

        // show + reward listener
        ad.show(activity) { rewardItem: RewardItem ->
            adCallback?.onUserEarnedReward(rewardItem)
        }
    }

    // =========================
    // Utils
    // =========================
    private fun RewardedAd?.isValid(loadedAt: Long, freshMs: Long): Boolean {
        if (this == null) return false
        val age = System.currentTimeMillis() - loadedAt
        return age in 0 until freshMs
    }

    // =========================
    // (Tuỳ chọn) RewardedInterstitial (nếu bạn cần)
    // =========================
    @Suppress("unused")
    private suspend fun loadFirstAvailableRewardedInterstitial(
        ids: List<String>,
        adCallback: AdCallback?,
        timeoutPerIdMs: Long
    ): RewardedInterstitialAd? = withContext(Dispatchers.Main) {
        val req = AdRequest.Builder().build()
        ids.forEachIndexed { idx, id ->
            val result = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine<Result<RewardedInterstitialAd>> { cont ->
                        RewardedInterstitialAd.load(app, id, req, object : RewardedInterstitialAdLoadCallback() {
                            override fun onAdLoaded(ad: RewardedInterstitialAd) {
                                logger.d("rint.load[$idx] SUCCESS id=$id")
                                adCallback?.onRewardedInterstitialLoaded(ad)
                                if (cont.isActive) cont.resume(Result.success(ad), onCancellation = {})
                            }
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                logger.d("rint.load[$idx] FAIL id=$id code=${error.code} msg=${error.message}")
                                adCallback?.onAdFailedToLoad(error)
                                if (cont.isActive) cont.resume(
                                    Result.failure(Exception(error.message)),
                                    onCancellation = {}
                                )
                            }
                        })
                    }
                }
            } catch (_: TimeoutCancellationException) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<RewardedInterstitialAd>(Exception("timeout"))
            } catch (t: Throwable) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<RewardedInterstitialAd>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }
}
