@file:Suppress("unused")

package com.dong.adsmodule.ads.ad

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dong.adsmodule.ads.ad.NativeAdCore.Logger
import com.dong.adsmodule.ads.ad.NativeAdCore.Task
import com.dong.adsmodule.ads.ad.NativeAdCore.appScope
import com.dong.adsmodule.ads.event.AdCallback
import com.google.android.gms.ads.*
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/* ------------------------------- STATE ------------------------------- */

sealed class NativeAdState {
    data object Idle : NativeAdState()
    data class Loading(val index: Int, val adUnitId: String) : NativeAdState()
    data class Success(val ad: NativeAd, val adUnitId: String, val responseInfo: ResponseInfo?) :
        NativeAdState()

    data class Timeout(val adUnitId: String) : NativeAdState()
    data class Error(val adUnitId: String, val error: LoadAdError) : NativeAdState()
    data class Exhausted(val lastError: LoadAdError?) : NativeAdState()
    data object Cancelled : NativeAdState()
}
/* --------------------------- BINDER (populate) --------------------------- */

/** Bộ mapping để bạn bind NativeAd vào NativeAdView. */
data class NativePopulateSpec(
    val headline: Int? = null,
    val body: Int? = null,
    val icon: Int? = null,
    val callToAction: Int? = null,
    val advertiser: Int? = null,
    val store: Int? = null,
    val price: Int? = null,
    val starRating: Int? = null,
    val mediaView: Int? = null,
)
/** Hàm populate mặc định: map theo id các view đã có trong layout NativeAdView. */
fun populateNativeAd(
    ad: NativeAd,
    adView: NativeAdView,
    spec: NativePopulateSpec = NativePopulateSpec()
) {
    // Headline (required)
    spec.headline?.let { id ->
        (adView.findViewById<TextView>(id)).also {
            adView.headlineView = it
            it?.text = ad.headline
        }
    }
    // Body (optional)
    spec.body?.let { id ->
        adView.bodyView = adView.findViewById<TextView>(id)?.apply {
            text = ad.body ?: run { visibility = View.GONE; null }
        }
    }
    // Call-To-Action
    spec.callToAction?.let { id ->
        adView.callToActionView = adView.findViewById<View>(id)?.apply {
            if (ad.callToAction == null) visibility = View.GONE
            else (this as? TextView)?.text = ad.callToAction
        }
    }
    // Icon
    spec.icon?.let { id ->
        adView.iconView = adView.findViewById<ImageView>(id)?.apply {
            val icon = ad.icon
            if (icon == null) visibility = View.GONE else setImageDrawable(icon.drawable)
        }
    }
    // Advertiser
    spec.advertiser?.let { id ->
        adView.advertiserView = adView.findViewById<TextView>(id)?.apply {
            if (ad.advertiser == null) visibility = View.GONE else text = ad.advertiser
        }
    }
    // Store / Price
    spec.store?.let { id ->
        adView.storeView = adView.findViewById<TextView>(id)?.apply {
            if (ad.store == null) visibility = View.GONE else text = ad.store
        }
    }
    spec.price?.let { id ->
        adView.priceView = adView.findViewById<TextView>(id)?.apply {
            if (ad.price == null) visibility = View.GONE else text = ad.price
        }
    }
    // Star rating
    spec.starRating?.let { id ->
        adView.starRatingView = adView.findViewById<RatingBar>(id)?.apply {
            val rating = ad.starRating?.toFloat()
            if (rating == null) visibility = View.GONE else this.rating = rating
        }
    }
    // MediaView (optional)
    spec.mediaView?.let { id ->
        (adView.findViewById<View>(id) as? com.google.android.gms.ads.nativead.MediaView)?.let { mv ->
            adView.mediaView = mv
        }
    }
    // Trigger final
    adView.setNativeAd(ad)
}

private data class Entry(
    val ad: NativeAd,
    val adUnitId: String,
    val responseInfo: ResponseInfo?,
    val loadedAt: Long
)
// 2) Optional DTO you can expose when popping detailed info
data class PreloadedAd(
    val ad: NativeAd,
    val adUnitId: String,
    val responseInfo: ResponseInfo?,
    val loadedAt: Long
)
/* ---------------------------- LOADER (request) ---------------------------- */
sealed class NativePreloadState {
    data object Idle : NativePreloadState()
    /** Overall progress (how many we asked vs how many we loaded so far). */
    data class Progress(val requested: Int, val loaded: Int) : NativePreloadState()
    /** One item just loaded successfully (and the cumulative counts). */
    data class ItemSuccess(
        val adUnitId: String,
        val loaded: Int,
        val requested: Int
    ) : NativePreloadState()
    /** Finished attempting this batch. `available` = items currently in buffer. */
    data class Finished(
        val requested: Int,
        val loaded: Int,
        val available: Int
    ) : NativePreloadState()
    /** Preload job for this tag was cancelled. */
    data object Cancelled : NativePreloadState()
}

class CallbackFanOut : AdCallback() {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<AdCallback>()
    fun add(l: AdCallback?) {
        if (l != null) listeners += l
    }

    override fun onUnifiedNativeAdLoaded(ad: NativeAd) {
        listeners.forEach { it.onUnifiedNativeAdLoaded(ad) }
    }

    override fun onAdFailedToLoad(error: LoadAdError?) {
        listeners.forEach { it.onAdFailedToLoad(error) }
    }

    override fun onAdImpression() {
        listeners.forEach { it.onAdImpression() }
    }

    override fun onAdClicked() {
        listeners.forEach { it.onAdClicked() }
    }

    override fun onAdClosed() {
        listeners.forEach { it.onAdClosed() }
    }
}

object NativeAdCore {
    data class Logger(private val tag: String = "NativeAdCore") {
        fun d(msg: String) = android.util.Log.d(tag, msg)
        fun e(msg: String, tr: Throwable? = null) = android.util.Log.e(tag, msg, tr)
    }
    /** Giới hạn số load đồng thời toàn app */
    private val globalSemaphore = Semaphore(permits = 3)
    /** Một lần request: quan sát state + có thể cancel */
    class Task internal constructor(
        private var job: Job,
        private val _state: MutableStateFlow<NativeAdState>,
        private val restartBlock: () -> Job
    ) {
        val state: StateFlow<NativeAdState> = _state.asStateFlow()

        fun cancel() {
            if (job.isActive) job.cancel()
            _state.value = NativeAdState.Cancelled
        }

        /** Re-run the same request (fresh waterfall) using the original params/scope. */
        fun requestAd() {
            if (job.isActive) job.cancel()
            _state.value = NativeAdState.Idle
            job = restartBlock()     // launch again
        }
    }
    /**
     * Request một Native theo waterfall, trả state. Không đụng tới UI.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // ... keep the rest of your types ...
    fun request(
        appContext: Context,
        adUnitIds: List<String>,
        timeoutPerIdMs: Long = 10_000L,
        options: NativeAdOptions = NativeAdOptions.Builder().build(),
        scope: CoroutineScope = appScope,
        adCallback: AdCallback? = null,
        logger: Logger = Logger()
    ): Task {
        val ids = adUnitIds.filter { it.isNotBlank() }.distinct()
        val state = MutableStateFlow<NativeAdState>(NativeAdState.Idle)

        // Define how to run the request once
        val launchOnce: () -> Job = {
            scope.launch(Dispatchers.Main.immediate) {
                runOneRequest(
                    ids = ids,
                    state = state,
                    appContext = appContext,
                    timeoutPerIdMs = timeoutPerIdMs,
                    options = options,
                    adCallback = adCallback,
                    logger = logger
                )
            }
        }

        // Start immediately
        val job = launchOnce()

        // Return a Task that knows how to restart itself
        return Task(job, state, restartBlock = launchOnce)
    }


    private suspend fun runOneRequest(
        ids: List<String>,
        state: MutableStateFlow<NativeAdState>,
        appContext: Context,
        timeoutPerIdMs: Long,
        options: NativeAdOptions,
        adCallback: AdCallback?,
        logger: Logger
    ) {
        if (ids.isEmpty()) {
            state.value = NativeAdState.Exhausted(null)
            return
        }
        var lastErr: LoadAdError? = null

        for ((i, id) in ids.withIndex()) {
            coroutineContext.ensureActive()
            state.value = NativeAdState.Loading(i, id)

            val res = try {
                withTimeout(timeoutPerIdMs.coerceAtLeast(1L)) {
                    globalSemaphore.withPermit {
                        loadOne(appContext.applicationContext, id, options, adCallback)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                state.value = NativeAdState.Timeout(id)
                null
            } catch (t: Throwable) {
                logger.e("Unexpected error while loading id=$id", t)
                state.value = NativeAdState.Exhausted(lastErr)
                return
            }

            when (res) {
                is LoadOk -> {
                    val ad = res.ad
                    state.value = NativeAdState.Success(ad, id, ad.responseInfo)
                    adCallback?.onUnifiedNativeAdLoaded(ad)
                    return
                }
                is LoadFail -> {
                    lastErr = res.err
                    state.value = NativeAdState.Error(id, res.err)
                    adCallback?.onAdFailedToLoad(res.err)
                }
                null -> {
                    // timeout → try next id
                }
            }
        }
        state.value = NativeAdState.Exhausted(lastErr)
    }

    /* -------------------------- PRELOAD BUFFER API -------------------------- */
    private data class Slot(
        val ids: List<String>,
        val buffer: ArrayDeque<Entry> = ArrayDeque(),
        var job: Job? = null,
        var ttlMs: Long = TimeUnit.HOURS.toMillis(4),
        val state: MutableStateFlow<NativePreloadState> = MutableStateFlow(NativePreloadState.Idle),
        val fanOut: CallbackFanOut = CallbackFanOut()
    )

    private val slots: ConcurrentHashMap<String, Slot> = ConcurrentHashMap()
    /** Preload N ad vào buffer theo tag. */
    fun getPreloadState(tag: String): StateFlow<NativePreloadState> =
        (slots[tag]?.state ?: MutableStateFlow(NativePreloadState.Idle)).asStateFlow()
    private val warmJobs = ConcurrentHashMap<String, Job>()

    /**
     * Keep a buffer of `capacity` preloaded ads for this tag, refilling in background.
     * Call once (e.g., in Application or splash) and forget.
     */
    fun warmPool(
        tag: String,
        appContext: Context,
        adUnitIds: List<String>,
        capacity: Int = 2,
        options: NativeAdOptions = NativeAdOptions.Builder().build(),
        ttlMs: Long = TimeUnit.HOURS.toMillis(4),
        refillCheckMs: Long = 5_000L, // how often we check/refill
        adCallback: AdCallback? = null
    ) {
        // If already warming, do nothing
        if (warmJobs[tag]?.isActive == true) return

        // Ensure the slot exists and has ids/ttl; let preloadAd do the heavy lifting
        warmJobs[tag] = appScope.launch {
            // initial top-up fast
            preloadAd(
                tag = tag,
                appContext = appContext,
                adUnitIds = adUnitIds,
                count = maxOf(0, capacity - available(tag)),
                options = options,
                ttlMs = ttlMs,
                scope = this,              // use this background job
                adCallback = adCallback
            )

            // keep topping up in background
            while (isActive) {
                val need = capacity - available(tag)
                if (need > 0) {
                    preloadAd(
                        tag = tag,
                        appContext = appContext,
                        adUnitIds = adUnitIds,
                        count = need,
                        options = options,
                        ttlMs = ttlMs,
                        scope = this,
                        adCallback = adCallback
                    )
                }
                delay(refillCheckMs)
            }
        }
    }

    /** Stop keeping the pool warm (optional). */
    fun stopWarming(tag: String) {
        warmJobs.remove(tag)?.cancel()
    }
    fun preloadAd(
        tag: String,
        appContext: Context,
        adUnitIds: List<String>,
        count: Int = 1,
        options: NativeAdOptions = NativeAdOptions.Builder().build(),
        ttlMs: Long = TimeUnit.HOURS.toMillis(4),
        scope: CoroutineScope = appScope,          // background by default
        logger: Logger = Logger(),
        adCallback: AdCallback? = null
    ) {
        val ids = adUnitIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty() || count <= 0) return
        val slot = slots.getOrPut(tag) { Slot(ids = ids) }.apply { this.ttlMs = ttlMs }
        // If the caller provided a callback for preload, add it to fan-out
        slot.fanOut.add(adCallback)

        if (slot.job?.isActive == true) return

        slot.job = scope.launch(Dispatchers.Main.immediate) {
            prune(tag)
            var loaded = 0
            while (isActive && loaded < count) {
                val pair = try {
                    globalSemaphore.withPermit {
                        // pass the SAME fan-out instance to the loader
                        waterfall(
                            appContext.applicationContext,
                            slot.ids,
                            options,
                            forward = slot.fanOut
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    null
                }

                if (pair != null) {
                    slot.buffer.addLast(
                        Entry(
                            ad = pair.first,
                            adUnitId = pair.second,
                            responseInfo = pair.first.responseInfo,
                            loadedAt = System.currentTimeMillis()
                        )
                    )
                    loaded++
                    slot.state.value = NativePreloadState.ItemSuccess(pair.second, loaded, count)
                    slot.state.value = NativePreloadState.Progress(count, loaded)
                } else break
            }
            slot.state.value = NativePreloadState.Finished(count, loaded, slot.buffer.size)
        }
    }

    fun cancelRequest(tag: String) {
        slots[tag]?.let { s ->
            s.job?.cancel()
            s.state.value = NativePreloadState.Cancelled
        }
    }
    /** Lấy 1 quảng cáo đã preload (pop). */
    fun popPreloaded(tag: String): NativeAd? {
        prune(tag)
        val ad = slots[tag]?.buffer?.pollFirst()?.ad
        return ad
    }

    fun popPreloadedDetailed(tag: String): PreloadedAd? {
        prune(tag)
        val e = slots[tag]?.buffer?.pollFirst() ?: return null
        return PreloadedAd(e.ad, e.adUnitId, e.responseInfo, e.loadedAt)
    }
    /** Preview (no pop) a preloaded ad WITH its adUnitId/responseInfo. */
    fun peekPreloadedDetailed(tag: String): PreloadedAd? {
        prune(tag)
        val e = slots[tag]?.buffer?.peekFirst() ?: return null
        return PreloadedAd(e.ad, e.adUnitId, e.responseInfo, e.loadedAt)
    }
    /** alias theo yêu cầu “poop” */
    fun poopPreloaded(tag: String): NativeAd? = popPreloaded(tag)
    /** Xem trước (không pop). */
    fun peekPreloaded(tag: String): NativeAd? {
        prune(tag)
        return slots[tag]?.buffer?.peekFirst()?.ad
    }
    /** Số lượng ad trong buffer. */
    fun available(tag: String): Int {
        prune(tag)
        return slots[tag]?.buffer?.size ?: 0
    }
    /** Huỷ tất cả. */
    fun cancelAll() {
        slots.values.forEach { it.job?.cancel() }
    }
    /** Xoá buffer (và destroy ad) cho tag. */
    fun clear(tag: String) {
        slots.remove(tag)?.let { s -> s.buffer.forEach { it.ad.destroy() } }
    }
    /** Xoá tất cả buffer. */
    fun clearAll() {
        slots.values.forEach { s -> s.buffer.forEach { it.ad.destroy() } }
        slots.clear()
    }
    /* unchanged aside from default scope */
    fun requestAdPreload(
        tag: String,
        appContext: Context,
        adUnitIds: List<String> = listOf(""),
        timeoutPerIdMs: Long = 10_000L,
        options: NativeAdOptions = NativeAdOptions.Builder().build(),
        scope: CoroutineScope = appScope,          // background by default
        logger: Logger = Logger(),
        adCallback: AdCallback? = null,
    ): Task {
        // Make sure future impressions/clicks from warm-pool deliveries are forwarded
        slots[tag]?.fanOut?.add(adCallback)

        val state = MutableStateFlow<NativeAdState>(NativeAdState.Idle)

        // How to run this request once (buffer-first, then cold fallback)
        val launchOnce: () -> Job = {
            scope.launch(Dispatchers.Main.immediate) {
                // 1) Try preloaded buffer first
                val pre = popPreloadedDetailed(tag)
                if (pre != null) {
                    // Guard: ad may have been destroyed by TTL pruning concurrently
                    runCatching {
                        adCallback?.onUnifiedNativeAdLoaded(pre.ad)
                        state.value = NativeAdState.Success(
                            ad = pre.ad,
                            adUnitId = pre.adUnitId,
                            responseInfo = pre.responseInfo
                        )
                    }.onFailure { t ->
                        // If this ad is no longer usable, fallback to cold path
                        logger.e("Preloaded ad unusable, fallback to cold request", t)
                        // 2) Cold path using the same parameters
                        val cold = request(
                            appContext = appContext,
                            adUnitIds = adUnitIds,
                            timeoutPerIdMs = timeoutPerIdMs,
                            options = options,
                            scope = scope,
                            adCallback = adCallback,
                            logger = logger
                        )
                        // Mirror the child task’s state into ours
                        cold.state.collect { s -> state.value = s }
                    }
                    return@launch
                }

                // 2) If no preloaded ad -> run cold waterfall and mirror its state
                val cold = request(
                    appContext = appContext,
                    adUnitIds = adUnitIds,
                    timeoutPerIdMs = timeoutPerIdMs,
                    options = options,
                    scope = scope,
                    adCallback = adCallback,
                    logger = logger
                )
                cold.state.collect { s -> state.value = s }
            }
        }

        val job = launchOnce()
        return Task(job, state, restartBlock = launchOnce)
    }

    /* ------------------------------- INTERNAL ------------------------------- */
    private sealed class LoadRes
    private data class LoadOk(val ad: NativeAd) : LoadRes()
    private data class LoadFail(val err: LoadAdError) : LoadRes()

    private suspend fun loadOne(
        ctx: Context,
        adUnitId: String,
        options: NativeAdOptions,
        forward: AdCallback? // <—
    ): LoadRes = suspendCancellableCoroutine { cont ->
        val loader = AdLoader.Builder(ctx, adUnitId)
            .forNativeAd { ad ->
                forward?.onUnifiedNativeAdLoaded(ad)
                if (cont.isActive) cont.resume(LoadOk(ad))
            }
            .withNativeAdOptions(options)
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    forward?.onAdFailedToLoad(error)
                    if (cont.isActive) cont.resume(LoadFail(error))
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                    forward?.onAdClosed()
                }

                override fun onAdImpression() {
                    forward?.onAdImpression()
                }

                override fun onAdClicked() {
                    forward?.onAdClicked()
                }
            })
            .build()

        loader.loadAd(AdRequest.Builder().build())
        cont.invokeOnCancellation { /* no-op */ }
    }
    /** Thử tuần tự từng id với timeout mỗi id; trả về Pair(ad, id) thành công đầu tiên. */
    private suspend fun waterfall(
        ctx: Context,
        ids: List<String>,
        options: NativeAdOptions,
        forward: AdCallback? // <—
    ): Pair<NativeAd, String>? = withContext(Dispatchers.Main) {
        for (id in ids) {
            when (val r = try {
                loadOne(ctx, id, options, forward)
            } catch (_: TimeoutCancellationException) {
                null
            }) {
                is LoadOk -> return@withContext (r.ad to id)
                is LoadFail, null -> continue
            }
        }
        null
    }
    /** Hủy ad quá TTL & gom rác buffer. */
    private fun prune(tag: String) {
        val slot = slots[tag] ?: return
        val now = System.currentTimeMillis()
        val it = slot.buffer.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.loadedAt >= slot.ttlMs) {
                e.ad.destroy()
                it.remove()
            }
        }
    }
    suspend fun NativeAdCore.awaitPreloadAvailable(
        tag: String,
        min: Int = 1,
        timeoutMs: Long = 12_000
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        if (available(tag) >= min) return@withTimeoutOrNull true
        getPreloadState(tag).collect { s ->
            when (s) {
                is NativePreloadState.ItemSuccess,
                is NativePreloadState.Progress -> {
                    if (available(tag) >= min) return@collect
                }
                is NativePreloadState.Finished -> {
                    if (available(tag) >= min) return@collect
                }
                else -> Unit
            }
        }
        true
    } ?: false
}

sealed class ReloadMode {
    /** Preload N ad vào buffer mỗi lần onResume */
    data class Preload(val count: Int = 1) : ReloadMode()
    /** Thực hiện request() mỗi lần onResume (trả về StateFlow state) */
    data object Request : ReloadMode()
}
/** Handle để hủy đăng ký khi không cần nữa */
class AutoReloadHandle internal constructor(
    private val owner: LifecycleOwner,
    private val observer: DefaultLifecycleObserver,
    /** Nếu mode = Request thì expose state để bạn observe UI; Preload thì null */
    val state: StateFlow<NativeAdState>? = null
) {
    fun cancel() = owner.lifecycle.removeObserver(observer)
}

fun NativeAdCore.bindAutoReloadOnResume(
    owner: LifecycleOwner,
    appContext: Context,
    tag: String,
    adUnitIds: List<String>,
    mode: ReloadMode = ReloadMode.Preload(count = 1),
    options: NativeAdOptions = NativeAdOptions.Builder().build(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    adCallback: AdCallback? = null,
    logger: NativeAdCore.Logger = NativeAdCore.Logger()
): AutoReloadHandle {
    require(adUnitIds.isNotEmpty()) { "adUnitIds is empty" }
    // Nếu dùng Request mode thì cần giữ Task + expose state
    var requestTask: NativeAdCore.Task? = null
    val requestState = MutableStateFlow<NativeAdState>(NativeAdState.Idle)
    val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            when (mode) {
                is ReloadMode.Preload -> {
                    // Nạp sẵn vào buffer (không phát state UI)
                    NativeAdCore.preloadAd(
                        tag = tag,
                        appContext = appContext,
                        adUnitIds = adUnitIds,
                        count = mode.count,
                        options = options,
                        scope = scope,
                        logger = logger
                    )
                }
                ReloadMode.Request -> {
                    // Hủy request cũ (nếu còn), request mới và forward state
                    requestTask?.cancel()
                    requestTask = NativeAdCore.request(
                        appContext = appContext,
                        adUnitIds = adUnitIds,
                        options = options,
                        scope = scope,
                        adCallback = adCallback,
                        logger = logger
                    )
                    scope.launch {
                        requestTask!!.state.collect { requestState.value = it }
                    }
                }
            }
        }

        override fun onPause(owner: LifecycleOwner) {
            // Không bắt buộc làm gì; giữ buffer để dùng lại nhanh
        }

        override fun onDestroy(owner: LifecycleOwner) {
            // Hủy mọi thứ liên quan tới tag này
            requestTask?.cancel()
            NativeAdCore.cancelRequest(tag)
            // Không clear buffer ở đây để bạn có thể dùng lại nếu cần (tuỳ chọn):
            // NativeAdCore.clear(tag)
        }
    }

    owner.lifecycle.addObserver(observer)
    return AutoReloadHandle(
        owner = owner,
        observer = observer,
        state = if (mode is ReloadMode.Request) requestState.asStateFlow() else null
    )
}
