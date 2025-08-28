@file:Suppress("unused")

package com.dong.adsmodule.ads.ad

import android.app.Activity
import android.content.Context
import com.dong.adsmodule.ads.event.AdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.ArrayDeque
import com.google.android.gms.ads.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class InterAdManager private constructor(
    private val app: Context,
    private val logger: Logger = Logger("InterAds")
) {
    companion object {
        @Volatile
        private var instance: InterAdManager? = null

        internal fun init(app: Context, logger: Logger = Logger("InterAds")): InterAdManager {
            return instance ?: synchronized(this) {
                instance ?: InterAdManager(app, logger).also { instance = it }
            }
        }
        private var loadJob: Job? = null
        @Volatile private var isLoading = false

        fun cancelLoading() {
            if (loadJob?.isActive == true) loadJob?.cancel()
            isLoading = false
        }
        fun getInstance(): InterAdManager =
            requireNotNull(instance) { "Call InterAdManager.init(app) first." }
    }

    data class Logger(private val tag: String) {
        fun d(msg: String) = android.util.Log.d(tag, msg)
        fun e(msg: String, tr: Throwable? = null) = android.util.Log.e(tag, msg, tr)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isShowingAd = false
    private var currentInterAd: InterstitialAd? = null
    private var resumeLoadedAt: Long = 0L
    private val interPool = ArrayDeque<InterEntry>(4)

    private data class InterEntry(
        val ad: InterstitialAd,
        val id: String,
        val loadedAt: Long
    )

    private val freshnessMs = TimeUnit.HOURS.toMillis(4)
    private var interIds: List<String> = emptyList()
    private var isEnabled = true
    var fullScreenContentCallback: FullScreenContentCallback? = null

    fun enable(enable: Boolean) {
        isEnabled = enable
        logger.d("enable Interstitial = $enable")
    }

    fun preload(ids: List<String>, adCallback: AdCallback? = null) {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            logger.d("preload(ids): empty ids"); return
        }
        interIds = clean // store for later show
        if (currentInterAd.isValid(resumeLoadedAt, freshnessMs)) {
            logger.d("preload(ids): cache already valid"); return
        }
        scope.launch {
            loadFirstAvailable(interIds, adCallback)?.let { ad ->
                currentInterAd = ad
                resumeLoadedAt = System.currentTimeMillis()
                logger.d("preload(ids): cached interstitial (index=${interIds.indexOfFirst { it == ad.adUnitId }})")
            } ?: logger.d("preload(ids): all ids failed")
        }
    }
    /** Preload using the last set interIds (waterfall). */
    fun preload(adCallback: AdCallback? = null) {
        if (currentInterAd.isValid(resumeLoadedAt, freshnessMs)) {
            logger.d("preload(): cache already valid"); return
        }
        if (interIds.isEmpty()) {
            logger.d("preload(): no inter IDs"); return
        }
        scope.launch {
            loadFirstAvailable(interIds, adCallback)?.let { ad ->
                currentInterAd = ad
                resumeLoadedAt = System.currentTimeMillis()
                logger.d("preload(): cached 1 interstitial")
            } ?: logger.d("preload(): all ids failed")
        }
    }
    /**
     * Waterfall loader: tries each id sequentially until one loads.
     * Returns the first loaded InterstitialAd or null if all fail.
     */
    private suspend fun loadFirstAvailable(
        ids: List<String>,
        adCallback: AdCallback?
    ): InterstitialAd? = withContext(Dispatchers.Main) {
        val req = AdRequest.Builder().build()
        ids.forEachIndexed { idx, id ->
            val result = try {
                suspendCancellableCoroutine<Result<InterstitialAd>> { cont ->
                    InterstitialAd.load(app, id, req, object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            logger.d("load[$idx] SUCCESS id=$id")
                            adCallback?.onAdLoaded()
                            if (cont.isActive) cont.resume(Result.success(ad), onCancellation = {})
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            logger.d("load[$idx] FAIL id=$id code=${error.code} msg=${error.message}")
                            adCallback?.onAdFailedToLoad(error)
                            if (cont.isActive) cont.resume(
                                Result.failure(Exception(error.message)),
                                onCancellation = {}
                            )
                        }
                    })
                }
            } catch (t: Throwable) {
                Result.failure<InterstitialAd>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }


    fun showIfReady(activity: Context, adCallback: AdCallback? = null) {
        if (!isEnabled || isShowingAd) {
            logger.d("showIfReady: blocked"); return
        }
        val cached = currentInterAd.takeIf { it.isValid(resumeLoadedAt, freshnessMs) }
        if (cached != null) {
            showInternal(activity, cached, adCallback)
            currentInterAd = null // consume the cached ad
        } else {
            logger.d("showIfReady: no cached interstitial; call preload(ids) first")
            adCallback?.onAdFailedToShow(AdError(0, "No cached interstitial", "InterAdManager"))
            adCallback?.onNextAction()
        }
    }

    fun preparePool(
        ids: List<String> = interIds,
        number: Int = 1,
        adCallback: AdCallback? = null,
        onComplete: ((loaded: Int, totalInPool: Int) -> Unit)? = null
    ) {
        scope.launch {
            val loaded = preparePoolSuspend(ids, number, adCallback)
            onComplete?.invoke(loaded, interPool.size)
        }
    }

    fun showFromPoolIfReady(activity: Context, adCallback: AdCallback? = null) {
        if (!isEnabled || isShowingAd) {
            logger.d("showFromPoolIfReady: blocked"); return
        }
        prunePool(interPool, freshnessMs)
        val entry = interPool.pollFirst()
        if (entry == null) {
            adCallback?.onAdFailedToShow(AdError(0, "No ad in pool", "InterAdManager"))
            return
        }
        showInternal(activity, entry.ad, adCallback)
    }
    // ======== Helpers ========
    private suspend fun loadOne(id: String, adCallback: AdCallback?) =
        withContext(Dispatchers.Main) {
            val req = AdRequest.Builder().build()
            try {
                suspendCancellableCoroutine { cont ->
                    InterstitialAd.load(app, id, req, object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            adCallback?.onAdLoaded()
                            if (cont.isActive) cont.resume(
                                Result.success(ad),
                                onCancellation = {})
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            adCallback?.onAdFailedToLoad(error)
                            if (cont.isActive) cont.resume(
                                Result.failure(Exception(error.message)),
                                onCancellation = {})
                        }
                    })
                }.getOrNull()
            } catch (t: Throwable) {
                null
            }
        }
    private fun showInternal(context: Context, ad: InterstitialAd, adCallback: AdCallback?) {
        val activity = context as? Activity
        if (activity == null) {
            adCallback?.onAdFailedToShow(AdError(0, "Context is not Activity", "InterAdManager"))
            adCallback?.onNextAction()
            return
        }

        if (isShowingAd) {
            logger.d("showInternal: already showing, skip")
            return
        }

        isShowingAd = true
        var nextActionFired = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {

            override fun onAdShowedFullScreenContent() {
                // Có thể dùng sự kiện này nếu bạn muốn thay vì impression
                // adCallback?.onInterstitialShow() đã được giữ lại để đồng bộ với API
                adCallback?.onInterstitialShow()
                fullScreenContentCallback?.onAdShowedFullScreenContent()
            }

            override fun onAdImpression() {
                // GỌI NGAY KHI CÓ IMPRESSION
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()
                }
                adCallback?.onAdImpression()
            }

            override fun onAdClicked() {
                adCallback?.onAdClicked()
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                logger.d("onAdFailedToShowFullScreenContent: ${error.message}")
                adCallback?.onAdFailedToShow(error)
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(error)

                // Fallback: chưa từng bắn nextAction thì bắn ngay để không kẹt flow
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()
                }

                isShowingAd = false
                // Warm-up lại cache
                preload(interIds, adCallback)
            }

            override fun onAdDismissedFullScreenContent() {
                adCallback?.onAdClosed()
                fullScreenContentCallback?.onAdDismissedFullScreenContent()

                isShowingAd = false
                // Sau khi đóng thì warm-up tiếp
                preload(interIds, adCallback)
            }
        }

        ad.show(activity)
    }


    private suspend fun preparePoolSuspend(
        ids: List<String>,
        number: Int,
        adCallback: AdCallback?
    ) =
        withContext(Dispatchers.Main.immediate) {
            var loaded = 0
            repeat(number) {
                val id = ids[it % ids.size]
                loadOne(id, adCallback)?.let {
                    interPool.addLast(InterEntry(it, id, System.currentTimeMillis()))
                    loaded++
                }
            }
            loaded
        }

    private fun prunePool(pool: ArrayDeque<InterEntry>, freshMs: Long) {
        val now = System.currentTimeMillis()
        pool.removeAll { now - it.loadedAt >= freshMs }
    }

    private fun InterstitialAd?.isValid(loadedAt: Long, freshMs: Long): Boolean {
        if (this == null) return false
        val age = System.currentTimeMillis() - loadedAt
        return age in 0 until freshMs
    }

    enum class NextActionTrigger { IMPRESSION, SHOWED }

    // 2) Overload loadFirstAvailable có timeout mỗi ID (nếu bạn muốn)
    private suspend fun loadFirstAvailable(
        ids: List<String>,
        adCallback: AdCallback?,
        timeoutPerIdMs: Long
    ): InterstitialAd? = withContext(Dispatchers.Main) {
        val req = AdRequest.Builder().build()
        ids.forEachIndexed { idx, id ->
            val result = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine<Result<InterstitialAd>> { cont ->
                        InterstitialAd.load(app, id, req, object : InterstitialAdLoadCallback() {
                            override fun onAdLoaded(ad: InterstitialAd) {
                                logger.d("load[$idx] SUCCESS id=$id")
                                adCallback?.onAdLoaded()
                                if (cont.isActive) cont.resume(Result.success(ad), onCancellation = {})
                            }
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                logger.d("load[$idx] FAIL id=$id code=${error.code} msg=${error.message}")
                                adCallback?.onAdFailedToLoad(error)
                                if (cont.isActive) cont.resume(
                                    Result.failure(Exception(error.message)),
                                    onCancellation = {}
                                )
                            }
                        })
                    }
                }
            } catch (t: TimeoutCancellationException) {
                logger.d("load[$idx] TIMEOUT id=$id after ${timeoutPerIdMs}ms")
                adCallback?.onAdFailedToLoad(null)
                Result.failure<InterstitialAd>(t)
            } catch (t: Throwable) {
                Result.failure<InterstitialAd>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }

    // 3) Hàm đơn phát: load + show
    fun loadAndShow(
        activity: Context,
        ids: List<String>,
        useCachedFirst: Boolean = true,
        timeoutPerIdMs: Long = 12_000L,
        reloadAfterShow: Boolean = true,
        trigger: NextActionTrigger = NextActionTrigger.IMPRESSION,
        adCallback: AdCallback? = null
    ) {
        if (!isEnabled) {
            logger.d("loadAndShow: disabled")
            adCallback?.onAdFailedToShow(AdError(0, "Disabled", "InterAdManager"))
            adCallback?.onNextAction()
            return
        }
        val act = activity as? Activity ?: run {
            adCallback?.onAdFailedToShow(AdError(0, "Context is not Activity", "InterAdManager"))
            adCallback?.onNextAction()
            return
        }
        if (isShowingAd) {
            logger.d("loadAndShow: already showing")
            // tuỳ bạn, thường không bắn nextAction ở đây để tránh double flow
            return
        }

        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            logger.d("loadAndShow: empty ids")
            adCallback?.onAdFailedToShow(AdError(0, "Empty ad units", "InterAdManager"))
            adCallback?.onNextAction()
            return
        }

        // Dùng cache nếu có
        if (useCachedFirst) {
            val cached = currentInterAd.takeIf { it.isValid(resumeLoadedAt, freshnessMs) }
            if (cached != null) {
                logger.d("loadAndShow: using cached")
                showInternalWithTrigger(act, cached, trigger, reloadAfterShow, adCallback)
                currentInterAd = null
                return
            }
        }

        // Không có cache → waterfall load theo danh sách rồi show
        scope.launch {
            val ad = async { loadFirstAvailable(clean, adCallback) } .await()
            if (ad != null) {
                // lưu lại nếu bạn muốn (không bắt buộc, vì show ngay)
                currentInterAd = ad
                resumeLoadedAt = System.currentTimeMillis()
                showInternalWithTrigger(act, ad, trigger, reloadAfterShow, adCallback)
                currentInterAd = null // consume
            } else {
                logger.d("loadAndShow: all ids failed")
                adCallback?.onAdFailedToShow(AdError(0, "All ids failed", "InterAdManager"))
                adCallback?.onNextAction()
            }
        }
    }

    // 4) Biến thể showInternal hỗ trợ trigger và reloadAfterShow
    private fun showInternalWithTrigger(
        activity: Activity,
        ad: InterstitialAd,
        trigger: NextActionTrigger,
        reloadAfterShow: Boolean,
        adCallback: AdCallback?
    ) {
        if (isShowingAd) {
            adCallback?.onNextAction()
            return
        }
        isShowingAd = true
        var nextActionFired = false
        adCallback?.onNextAction()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                adCallback?.onInterstitialShow()
                fullScreenContentCallback?.onAdShowedFullScreenContent()
                if (trigger == NextActionTrigger.SHOWED && !nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()
                }
            }

            override fun onAdImpression() {
                adCallback?.onAdImpression()
                if (trigger == NextActionTrigger.IMPRESSION && !nextActionFired) {
                    nextActionFired = true
                    adCallback?.onAdImpression()
                }
            }

            override fun onAdClicked() {
                adCallback?.onAdClicked()
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adCallback?.onAdFailedToShow(error)
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(error)
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction() // fallback để không kẹt flow
                }
                isShowingAd = false
                if (reloadAfterShow && interIds.isNotEmpty()) preload(interIds, adCallback)
            }

            override fun onAdDismissedFullScreenContent() {
                adCallback?.onAdClosed()

                fullScreenContentCallback?.onAdDismissedFullScreenContent()
                isShowingAd = false
                if (reloadAfterShow && interIds.isNotEmpty()) preload(interIds, adCallback)
            }
        }

        ad.show(activity)
    }

    fun load(
        ids: List<String>,
        force: Boolean = false,
        timeoutPerIdMs: Long = 12_000L,
        adCallback: AdCallback? = null,
        onLoaded: (Boolean) -> Unit = {}
    ) {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            logger.d("load(): empty ids")
            onLoaded(false)
            return
        }
        interIds = clean // lưu để dùng lại ở nơi khác

        // Dùng cache nếu còn fresh và không ép load lại
        if (!force && currentInterAd.isValid(resumeLoadedAt, freshnessMs)) {
            logger.d("load(): cache valid -> skip load")
            adCallback?.onInterstitialLoad(currentInterAd) // báo có sẵn
            adCallback?.onAdLoaded()
            onLoaded(true)
            return
        }

        // Tránh load chồng
        if (isLoading && loadJob?.isActive == true) {
            logger.d("load(): already loading")
            return
        }

        isLoading = true
        loadJob = scope.launch {
            val ad = loadFirstAvailableWithTimeout(interIds, adCallback, timeoutPerIdMs)
            isLoading = false
            if (ad != null) {
                currentInterAd = ad
                resumeLoadedAt = System.currentTimeMillis()
                // báo “inter loaded”
                adCallback?.onInterstitialLoad(ad)
                adCallback?.onAdLoaded()
                onLoaded(true)
                logger.d("load(): success -> cached")
            } else {
                onLoaded(false)
                logger.d("load(): all ids failed")
            }
        }
    }
    private suspend fun loadFirstAvailableWithTimeout(
        ids: List<String>,
        adCallback: AdCallback?,
        timeoutPerIdMs: Long
    ): InterstitialAd? = withContext(Dispatchers.Main) {
        val req = AdRequest.Builder().build()
        ids.forEachIndexed { idx, id ->
            val result = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine<Result<InterstitialAd>> { cont ->
                        InterstitialAd.load(app, id, req, object : InterstitialAdLoadCallback() {
                            override fun onAdLoaded(ad: InterstitialAd) {
                                logger.d("load[$idx] SUCCESS id=$id")
                                if (cont.isActive) cont.resume(Result.success(ad), onCancellation = {})
                            }
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                logger.d("load[$idx] FAIL id=$id code=${error.code} msg=${error.message}")
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
                Result.failure<InterstitialAd>(Exception("timeout"))
            } catch (t: Throwable) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<InterstitialAd>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }

    fun show(
        activity: Context,
        adCallback: AdCallback? = null,
        reloadAfterShow: Boolean = true   // tuỳ chọn: đóng xong preload lại
    ): Boolean {
        if (!isEnabled || isShowingAd) {
            logger.d("show(): blocked (enabled=$isEnabled, showing=$isShowingAd)")
            return false
        }
        val cached = currentInterAd.takeIf { it.isValid(resumeLoadedAt, freshnessMs) }
        if (cached == null) {
            logger.d("show(): no cached ad -> call load(...) first")
            adCallback?.onAdFailedToShow(AdError(0, "No cached interstitial", "InterAdManager"))
            // Nếu không muốn tự tiếp tục flow ở đây, bạn có thể bỏ dòng dưới:
            adCallback?.onNextAction()
            return false
        }
        showInternalImpressionNext(activity, cached, adCallback, reloadAfterShow)
        currentInterAd = null // consume
        return true
    }

    private fun showInternalImpressionNext(
        context: Context,
        ad: InterstitialAd,
        adCallback: AdCallback?,
        reloadAfterShow: Boolean
    ) {
        val activity = context as? Activity ?: run {
            adCallback?.onAdFailedToShow(AdError(0, "Context is not Activity", "InterAdManager"))
            adCallback?.onNextAction()
            return
        }

        if (isShowingAd) {
            logger.d("showInternal: already showing")
            return
        }

        isShowingAd = true
        var nextActionFired = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                adCallback?.onInterstitialShow()
                fullScreenContentCallback?.onAdShowedFullScreenContent()
            }

            override fun onAdImpression() {
                adCallback?.onAdImpression()
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()   // chuyển màn ngay khi impression
                }
            }

            override fun onAdClicked() {
                adCallback?.onAdClicked()
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adCallback?.onAdFailedToShow(error)
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(error)
                if (!nextActionFired) {
                    nextActionFired = true
                    adCallback?.onNextAction()   // fallback để không kẹt flow
                }
                isShowingAd = false
                if (reloadAfterShow && interIds.isNotEmpty()) {
                    // preload lại cho lần sau
                    load(interIds, force = false, adCallback = adCallback)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                adCallback?.onAdClosed()
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
                isShowingAd = false
                if (reloadAfterShow && interIds.isNotEmpty()) {
                    load(interIds, force = false, adCallback = adCallback)
                }
            }
        }

        ad.show(activity)
    }
}

