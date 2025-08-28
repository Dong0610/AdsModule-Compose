package com.dong.adsmodule.ads.ad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dong.adsmodule.ads.cache.AdEntry
import com.dong.adsmodule.ads.cache.poolOf
import com.dong.adsmodule.ads.cache.prunePool
import com.dong.adsmodule.ads.core.Logger
import com.dong.adsmodule.ads.event.AdCallback
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class AppOpenManager private constructor(
    private val app: Application,
    private val logger: Logger = Logger("AppOpenAds")
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    companion object {
        @Volatile
        private var instance: AppOpenManager? = null
        /** Gọi một lần ở Application.onCreate() */
        internal fun init(app: Application, logger: Logger = Logger("AppOpenAds")): AppOpenManager {
            return instance ?: synchronized(this) {
                instance ?: AppOpenManager(app, logger).also { mgr ->
                    instance = mgr
                    app.registerActivityLifecycleCallbacks(mgr)
                    ProcessLifecycleOwner.get().lifecycle.addObserver(mgr)
                    logger.d("Initialized")
                }
            }
        }

        fun getInstance(): AppOpenManager =
            requireNotNull(instance) {
                throw IllegalStateException("Call AppOpenManager.init(app) first.")
            }
    }

    @Volatile
    private var adCallback: AdCallback? = null
    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Current activity (no leaks)
    private var currentActivityRef: WeakReference<Activity>? = null
    private fun currentActivity(): Activity? = currentActivityRef?.get()
    // Cached ads + timestamps
    private var resumeAd: AppOpenAd? = null
    private var resumeLoadedAt: Long = 0L
    private var splashAd: AppOpenAd? = null
    private var splashLoadedAt: Long = 0L
    // Waterfall IDs
    private var resumeAdUnits: List<String> = emptyList()
    private var splashAdUnits: List<String> = emptyList()
    private var splashTimeoutMs: Long = 8_000L
    // Flags
    private var isShowingAd = false
    private var isAppResumeEnabled = true
    private var isInterstitialShowing = false
    // Control
    private val disabledActivities = mutableSetOf<Class<out Activity>>().apply {
        add(AdActivity::class.java)
    }
    var fullScreenContentCallback: FullScreenContentCallback? = null
    private val freshnessMs = TimeUnit.HOURS.toMillis(4)
    // ========= Public API =========
    fun setResumeAdUnits(ids: List<String>, adCallback: AdCallback? = null) = apply {
        resumeAdUnits = ids.filter { it.isNotBlank() }
        logger.d("Set resume units: $resumeAdUnits")
        preloadResume(adCallback)
    }

    fun setSplashAdUnits(ids: List<String>, timeoutMs: Long = 8_000L) {
        splashAdUnits = ids.filter { it.isNotBlank() }
        splashTimeoutMs = timeoutMs
        logger.d("Set splash units: $splashAdUnits, timeoutMs=$splashTimeoutMs")
    }

    fun enableAppResume(enable: Boolean) {
        isAppResumeEnabled = enable; logger.d("enableAppResume=$enable")
    }

    fun setInterstitialShowing(showing: Boolean) {
        isInterstitialShowing = showing; logger.d("setInterstitialShowing=$showing")
    }

    fun disableResumeFor(vararg activity: Class<out Activity>) {
        disabledActivities.addAll(activity)
        logger.d("disableResumeFor=${activity.joinToString { it.simpleName }}")
    }

    fun enableResumeFor(vararg activity: Class<out Activity>) {
        disabledActivities.removeAll(activity.toSet())
        logger.d("enableResumeFor=${activity.joinToString { it.simpleName }}")
    }

    fun setAdCallback(callback: AdCallback?) = apply { adCallback = callback }
    /** Preload resume (waterfall) */
    fun preloadResume(adCallback: AdCallback? = null) {
        if (resumeAd.isValid(resumeLoadedAt, freshnessMs)) {
            logger.d("preloadResume: already valid in cache")
            return
        }
        if (resumeAdUnits.isEmpty()) {
            logger.d("preloadResume: no ad units")
            return
        }
        logger.d("preloadResume: start waterfall (${resumeAdUnits.size} ids)")
        scope.launch {
            loadFirstAvailable(
                resumeAdUnits,
                isSplash = false,
                adCallback = adCallback
            )
        }
    }
    /** Preload splash (waterfall) */
    fun preloadSplash(adCallback: AdCallback? = null) {
        if (splashAd.isValid(splashLoadedAt, freshnessMs)) {
            logger.d("preloadSplash: already valid in cache")
            return
        }
        if (splashAdUnits.isEmpty()) {
            logger.d("preloadSplash: no ad units")
            return
        }
        logger.d("preloadSplash: start waterfall (${splashAdUnits.size} ids), timeoutMs=$splashTimeoutMs")
        scope.launch {
            loadFirstAvailable(
                splashAdUnits,
                isSplash = true,
                timeoutMs = splashTimeoutMs,
                adCallback
            )
        }
    }
    /** Try to show splash immediately if ready */
    fun showSplashIfReady(adCallback: AdCallback? = null) {
        val act = currentActivity()
        if (act == null) {
            logger.d("showSplashIfReady: no current activity"); return
        }
        val ad = splashAd.takeIf { it.isValid(splashLoadedAt, freshnessMs) }
        if (ad == null) {
            logger.d("showSplashIfReady: ad not ready"); return
        }
        logger.d("showSplashIfReady: showing")
        showAdInternal(act, ad, isSplash = true, adCallback = adCallback)
    }
    // ========= Lifecycle =========
    override fun onStart(owner: LifecycleOwner) {
        logger.d("onStart: app to foreground")
        if (!isAppResumeEnabled) {
            logger.d("onStart: app-resume disabled"); return
        }
        if (isInterstitialShowing) {
            logger.d("onStart: interstitial showing → skip"); return
        }
        val act = currentActivity()
        if (act == null) {
            logger.d("onStart: no current activity"); return
        }
        if (act::class.java in disabledActivities) {
            logger.d("onStart: ${act::class.java.simpleName} is disabled"); return
        }
        val ad = resumeAd.takeIf { it.isValid(resumeLoadedAt, freshnessMs) }
        if (ad != null && !isShowingAd) {
            logger.d("onStart: show resume ad")
            showAdInternal(act, ad, isSplash = false, adCallback = adCallback)
        } else {
            logger.d("onStart: no valid resume ad → preload")
            preloadResume(adCallback)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef =
            WeakReference(activity); logger.d("onActivityResumed: ${activity::class.java.simpleName}")
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity() === activity) currentActivityRef = null
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    // ========= Internal loading/show =========
    private suspend fun loadFirstAvailable(
        ids: List<String>,
        isSplash: Boolean,
        timeoutMs: Long = 8_000L,
        adCallback: AdCallback? = null
    ) = withContext(Dispatchers.Main) {
        val request = AdRequest.Builder().build()
        var lastError: String? = null

        ids.forEachIndexed { index, id ->
            logger.d("load[$index/${ids.size}] start id=$id, timeoutMs=$timeoutMs")
            val res = try {
                withTimeout(timeoutMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine { cont ->
                        AppOpenAd.load(
                            app, id, request,
                            object : AppOpenAd.AppOpenAdLoadCallback() {
                                override fun onAdLoaded(ad: AppOpenAd) {
                                    logger.d("load[$index] SUCCESS id=$id")
                                    adCallback?.onAdLoaded()
                                    if (cont.isActive) cont.resume(Result.success(ad))
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    logger.d("load[$index] FAIL id=$id, code=${error.code}, msg=${error.message}")
                                    adCallback?.onAdFailedToLoad(error)
                                    if (cont.isActive) cont.resume(Result.failure(Exception(error.message)))
                                }
                            }
                        )
                    }
                }
            } catch (t: TimeoutCancellationException) {
                logger.d("load[$index] TIMEOUT id=$id after ${timeoutMs}ms")
                adCallback?.onAdFailedToLoad(null)
                Result.failure(t)
            } catch (t: Throwable) {
                logger.e("load[$index] EXCEPTION id=$id: ${t.message}", t)
                adCallback?.onAdFailedToLoad(null)
                Result.failure(t)
            }

            if (res.isSuccess) {
                val ad = res.getOrNull()!!
                if (isSplash) {
                    splashAd = ad
                    splashLoadedAt = System.currentTimeMillis()
                    adCallback?.onAdSplashReady()
                    logger.d("waterfall result: SPLASH cached (id=$id)")
                } else {
                    resumeAd = ad
                    resumeLoadedAt = System.currentTimeMillis()
                    logger.d("waterfall result: RESUME cached (id=$id)")
                }
                return@withContext
            } else {
                lastError = res.exceptionOrNull()?.message
            }
        }

        logger.d("waterfall result: ALL FAILED, lastError=$lastError")
    }

    private fun showAdInternal(
        activity: Activity,
        ad: AppOpenAd,
        isSplash: Boolean = false,
        adCallback: AdCallback? = null
    ) {
        if (isShowingAd) {
            logger.d("show: already showing → skip")
            return
        }
        isShowingAd = true
        logger.d("show: ${if (isSplash) "SPLASH" else "RESUME"} on ${activity::class.java.simpleName}")
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                logger.d("callback: onAdShowedFullScreenContent")
                // Treat as "interstitial show" for a full-screen AppOpen
                adCallback?.onInterstitialShow()
                fullScreenContentCallback?.onAdShowedFullScreenContent()
                if (isSplash) splashAd = null else resumeAd = null
            }

            override fun onAdImpression() {
                logger.d("callback: onAdImpression")
                adCallback?.onAdImpression()
            }

            override fun onAdClicked() {
                logger.d("callback: onAdClicked")
                adCallback?.onAdClicked()
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                logger.d("callback: onAdFailedToShow, code=${error.code}, msg=${error.message}")
                adCallback?.onAdFailedToShow(error)
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(error)
                isShowingAd = false
                ad.fullScreenContentCallback = null
                if (!isSplash) preloadResume(adCallback)
            }

            override fun onAdDismissedFullScreenContent() {
                logger.d("callback: onAdDismissedFullScreenContent")
                adCallback?.onAdClosed()
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
                isShowingAd = false
                ad.fullScreenContentCallback = null
                if (!isSplash) preloadResume(adCallback)
            }
        }

        ad.show(activity)
    }
    // ========= Utils =========
    fun AppOpenAd?.isValid(loadedAt: Long, freshMs: Long): Boolean {
        if (this == null) return false
        val age = System.currentTimeMillis() - loadedAt
        return age in 0 until freshMs
    }

    private suspend fun loadOne(
        id: String,
        isSplash: Boolean,
        timeoutMs: Long,
        adCallback: AdCallback? = null
    ): Boolean = withContext(Dispatchers.Main) {
        val request = AdRequest.Builder().build()
        val res = try {
            withTimeout(timeoutMs.coerceAtLeast(1L)) {
                suspendCancellableCoroutine<Result<AppOpenAd>> { cont ->
                    AppOpenAd.load(
                        app, id, request,
                        object : AppOpenAd.AppOpenAdLoadCallback() {
                            override fun onAdLoaded(ad: AppOpenAd) {
                                adCallback?.onAdLoaded()
                                if (cont.isActive) cont.resume(Result.success(ad))
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                logger.d("loadOne FAIL id=$id -> ${error.message}")
                                adCallback?.onAdFailedToLoad(error)
                                if (cont.isActive) cont.resume(Result.failure(Exception(error.message)))
                            }
                        })
                }
            }
        } catch (t: Throwable) {
            logger.e("loadOne exception: ${t.message}", t)
            // We don't have a LoadAdError here; signal as null
            adCallback?.onAdFailedToLoad(null)
            Result.failure(t)
        }

        if (res.isSuccess) {
            val ad = res.getOrNull()!!
            val entry = AdEntry(ad, id, System.currentTimeMillis())
            val pool = poolOf(isSplash)
            pool.addLast(entry)
            if (isSplash) adCallback?.onAdSplashReady()
            logger.d("loadOne: cached ${if (isSplash) "SPLASH" else "RESUME"} (id=$id), poolSize=${pool.size}")
            true
        } else false
    }
    /** Fire-and-forget: chuẩn bị số lượng AppOpen trong pool */
    fun prepareAppOpen(
        ids: List<String>,
        number: Int,
        isSplash: Boolean,
        isForce: Boolean = false,
        timeoutPerLoadMs: Long = 8_000L,
        concurrent: Int = 2, // bạn có thể set 1 nếu muốn tuần tự
        onComplete: ((loaded: Int, totalInCache: Int) -> Unit)? = null,
        adCallback: AdCallback? = null
    ) {
        scope.launch {
            val loaded =
                prepareAppOpenSuspend(
                    ids,
                    number,
                    isSplash,
                    isForce,
                    timeoutPerLoadMs,
                    concurrent,
                    adCallback
                )
            val total = poolOf(isSplash).size
            logger.d("prepareAppOpen done: loaded=$loaded, totalInPool=$total, isSplash=$isSplash")
            onComplete?.invoke(loaded, total)
        }
    }
    /** Suspend: đảm bảo pool có đủ (isForce=false) hoặc cộng thêm (isForce=true) */
    private suspend fun prepareAppOpenSuspend(
        ids: List<String>,
        number: Int,
        isSplash: Boolean,
        isForce: Boolean,
        timeoutPerLoadMs: Long,
        concurrent: Int, adCallback: AdCallback? = null
    ): Int = withContext(Dispatchers.Main.immediate) {
        require(number > 0) { "number must be > 0" }
        val idsClean = ids.filter { it.isNotBlank() }.distinct()
        if (idsClean.isEmpty()) {
            logger.d("prepareAppOpen: empty ids"); return@withContext 0
        }
        val pool = poolOf(isSplash)
        prunePool(pool, freshnessMs)
        // Đếm hiện có trong pool trùng các id truyền vào
        val have = pool.count { it.id in idsClean }
        val targetLoads = if (isForce) number else (number - have).coerceAtLeast(0)
        if (targetLoads == 0) return@withContext 0

        logger.d("prepareAppOpen: isForce=$isForce, have=$have, need=$targetLoads, ids=${idsClean.size}")
        var loaded = 0
        var cursor = 0

        suspend fun nextId(): String {
            val id = idsClean[cursor % idsClean.size]
            cursor++
            return id
        }

        if (concurrent <= 1) {
            // Tuần tự
            repeat(targetLoads) {
                if (loadOne(nextId(), isSplash, timeoutPerLoadMs, adCallback)) loaded++
            }
        } else {
            // Song song có giới hạn (đơn giản)
            while (loaded < targetLoads) {
                val batch = (0 until (targetLoads - loaded).coerceAtMost(concurrent))
                    .map {
                        async {
                            loadOne(
                                nextId(),
                                isSplash,
                                timeoutPerLoadMs,
                                adCallback = adCallback
                            )
                        }
                    }
                loaded += batch.awaitAll().count { it }
            }
        }
        loaded
    }

    private fun popFromPool(isSplash: Boolean, acceptedIds: Set<String>? = null): AdEntry? {
        val pool = poolOf(isSplash)
        prunePool(pool, freshnessMs)
        val it = pool.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (acceptedIds == null || e.id in acceptedIds) {
                it.remove()
                return e
            }
        }
        return null
    }

    fun showFromPoolIfReady(
        activity: Activity,
        isSplash: Boolean,
        acceptedIds: List<String>? = null,
        adCallback: AdCallback? = null
    ) {
        val entry = popFromPool(isSplash, acceptedIds?.toSet())
        if (entry == null) {
            logger.d("showFromPoolIfReady: pool empty or no accepted id")
            adCallback?.onAdFailedToShow(
                AdError(
                    0,
                    "No ad available",
                    "AppOpen Cache"
                )
            ) // or create a custom ‘no fill in pool’ path
            return
        }
        logger.d("showFromPoolIfReady: showing id=${entry.id} on ${activity::class.java.simpleName}")
        showAdInternal(activity, entry.ad, isSplash, adCallback)
    }
}
