@file:Suppress("unused")

package com.dong.adsmodule.ads.ad

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dong.adsmodule.ads.event.AdCallback
import com.google.android.gms.ads.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class BannerAdManager private constructor(
    private val app: Context,
    private val logger: Logger = Logger("BannerAds")
) : DefaultLifecycleObserver {
    companion object {
        @Volatile
        private var instance: BannerAdManager? = null
        fun init(app: Context, logger: Logger = Logger("BannerAds")): BannerAdManager {
            return instance ?: synchronized(this) {
                instance ?: BannerAdManager(app, logger).also { instance = it }
            }
        }

        fun getInstance(): BannerAdManager =
            requireNotNull(instance) { "Call BannerAdManager.init(app) first." }
    }

    data class Logger(private val tag: String) {
        fun d(msg: String) = android.util.Log.d(tag, msg)
        fun e(msg: String, tr: Throwable? = null) = android.util.Log.e(tag, msg, tr)
    }
    // ===== State =====
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isEnabled = true
    private var isLoading = false
    private var loadJob: Job? = null
    private var currentAdView: AdView? = null
    private var loadedAt: Long = 0L
    private val freshnessMs = TimeUnit.HOURS.toMillis(4)
    // Lưu nhiều slot theo tag để resume có thể reload đúng
    private val slots = LinkedHashMap<String, BannerSlotConfig>()

    fun enable(enable: Boolean) {
        isEnabled = enable; logger.d("enable Banner=$enable")
    }

    fun destroy() {
        currentAdView?.destroy(); currentAdView = null
    }

    fun cancelLoading() {
        loadJob?.cancel(); isLoading = false
    }
    // ========= Lifecycle: auto-reload onResume =========
    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        logger.d("Lifecycle onResume")
        // Nếu chưa có banner sẵn -> request lại tất cả slot đã create()
        if (!currentAdView.isValid(loadedAt, freshnessMs)) {
            slots.values.forEach { slot ->
                requestAdInternal(
                    tag = slot.tag,
                    ids = slot.ids,
                    container = slot.container,
                    isCollapse = slot.isCollapse,
                    adCallback = slot.adCallback
                )
            }
        } else {
            currentAdView?.resume()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner); currentAdView?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner); destroy()
    }
    // ========= Fluent API =========
    data class BannerSlotConfig(
        val tag: String,
        val ids: List<String>,
        val container: ViewGroup,
        val isCollapse: Boolean,
        val adCallback: AdCallback? = null
    )

    inner class BannerSlotBuilder internal constructor(
        private val tag: String,
        private val ids: List<String>,
        private val container: ViewGroup,
        private val isCollapse: Boolean
    ) {
        private var callback: AdCallback? = null
        fun callback(cb: AdCallback) = apply { this.callback = cb }
        /** Đăng ký slot để lifecycle resume có thể tự reload */
        fun create(): BannerSlotBuilder {
            slots[tag] = BannerSlotConfig(tag, ids, container, isCollapse, callback)
            return this
        }
        /** Bắt đầu request ad cho slot này ngay */
        fun requestAd() {
            requestAdInternal(tag, ids, container, isCollapse, callback)
        }
    }
    /**
     * setupBanner(tag, listIds, container, isCollapse)
     *   .create()
     *   .requestAd()
     */
    fun setupBanner(
        tag: String,
        listIds: List<String>,
        container: ViewGroup,
        isCollapse: Boolean = true
    ): BannerSlotBuilder {
        val clean = listIds.filter { it.isNotBlank() }.distinct()
        require(clean.isNotEmpty()) { "setupBanner: listIds is empty" }
        return BannerSlotBuilder(tag, clean, container, isCollapse)
    }
    // ========= Public “classic” API (nếu bạn vẫn muốn) =========
    fun load(
        ids: List<String>,
        force: Boolean = false,
        timeoutPerIdMs: Long = 10_000L,
        adCallback: AdCallback? = null,
        onLoaded: (Boolean) -> Unit = {}
    ) {
        if (!isEnabled) {
            onLoaded(false); return
        }
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            onLoaded(false); return
        }

        if (!force && currentAdView.isValid(loadedAt, freshnessMs)) {
            adCallback?.onAdLoaded(); onLoaded(true); return
        }
        if (isLoading && loadJob?.isActive == true) return

        isLoading = true
        loadJob = scope.launch {
            val adView = loadFirstAvailableBanner(clean, adCallback, timeoutPerIdMs)
            isLoading = false
            if (adView != null) {
                currentAdView?.destroy()
                currentAdView = adView
                loadedAt = System.currentTimeMillis()
                adCallback?.onAdLoaded()
                onLoaded(true)
            } else {
                onLoaded(false)
            }
        }
    }

    fun show(activity: Context, container: ViewGroup, adCallback: AdCallback? = null): Boolean {
        if (!isEnabled) return false
        val act = activity as? Activity ?: return false
        val adView = currentAdView.takeIf { it.isValid(loadedAt, freshnessMs) } ?: return false
        (adView.parent as? ViewGroup)?.removeView(adView)
        container.removeAllViews()
        container.addView(
            adView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.visibility = View.VISIBLE
        return true
    }

    fun loadAndShow(
        activity: Context,
        container: ViewGroup,
        ids: List<String>,
        useCachedFirst: Boolean = true,
        timeoutPerIdMs: Long = 10_000L,
        adCallback: AdCallback? = null,
        isCollapse: Boolean = true
    ) {
        val act = activity as? Activity ?: return
        if (useCachedFirst && currentAdView.isValid(loadedAt, freshnessMs)) {
            if (!show(act, container, adCallback) && isCollapse) container.visibility = View.GONE
            return
        }
        load(ids, force = true, timeoutPerIdMs = timeoutPerIdMs, adCallback = adCallback) { ok ->
            val shown = if (ok) show(act, container, adCallback) else false
            if (!shown && isCollapse) container.visibility = View.GONE
        }
    }
    // ========= Internal =========
    private fun requestAdInternal(
        tag: String,
        ids: List<String>,
        container: ViewGroup,
        isCollapse: Boolean,
        adCallback: AdCallback?
    ) {
        logger.d("requestAdInternal: tag=$tag")
        loadAndShow(
            activity = container.context,
            container = container,
            ids = ids,
            useCachedFirst = true,
            timeoutPerIdMs = 10_000L,
            adCallback = adCallback,
            isCollapse = isCollapse
        )
    }

    private suspend fun loadFirstAvailableBanner(
        ids: List<String>,
        adCallback: AdCallback?,
        timeoutPerIdMs: Long
    ): AdView? = withContext(Dispatchers.Main) {
        ids.forEachIndexed { idx, id ->
            val result = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    suspendCancellableCoroutine<Result<AdView>> { cont ->
                        val adView = AdView(app).apply {
                            adUnitId = id
                            // Adaptive (anchored) theo bề ngang hiện tại
                            val density = resources.displayMetrics.density
                            val adWidth = (resources.displayMetrics.widthPixels / density).toInt()
                                .coerceAtLeast(320)
                            setAdSize(
                                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                                    context,
                                    adWidth
                                )
                            )
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    logger.d("banner.load[$idx] SUCCESS id=$id size=$adSize")
                                    if (cont.isActive) cont.resume(Result.success(this@apply), {})
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    logger.d("banner.load[$idx] FAIL id=$id msg=${error.message}")
                                    adCallback?.onAdFailedToLoad(error)
                                    destroy()
                                    if (cont.isActive) cont.resume(
                                        Result.failure(Exception(error.message)),
                                        {})
                                }

                                override fun onAdImpression() {
                                    adCallback?.onAdImpression()
                                }

                                override fun onAdClicked() {
                                    adCallback?.onAdClicked()
                                }
                            }
                        }
                        adView.loadAd(AdRequest.Builder().build())
                    }
                }
            } catch (_: TimeoutCancellationException) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<AdView>(Exception("timeout"))
            } catch (t: Throwable) {
                adCallback?.onAdFailedToLoad(null)
                Result.failure<AdView>(t)
            }
            if (result.isSuccess) return@withContext result.getOrNull()
        }
        null
    }

    private fun AdView?.isValid(loadedAt: Long, freshMs: Long): Boolean {
        if (this == null) return false
        val age = System.currentTimeMillis() - loadedAt
        return age in 0 until freshMs
    }
}
