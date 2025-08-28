package com.dong.adsmodule.ui

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.dong.adsmodule.ads.ad.NativeAdState
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd

/** Holder tham chiếu các view bên trong NativeAdView để các slot truy cập */
class NativeAdRefs {
    var adView: com.google.android.gms.ads.nativead.NativeAdView? by mutableStateOf(null)
    var mediaView: com.google.android.gms.ads.nativead.MediaView? by mutableStateOf(null)
    var iconView: ImageView? by mutableStateOf(null)
    var headlineView: TextView? by mutableStateOf(null)
    var bodyView: TextView? by mutableStateOf(null)
    var advertiserView: TextView? by mutableStateOf(null)
    var priceView: TextView? by mutableStateOf(null)
    var storeView: TextView? by mutableStateOf(null)
    var starRatingView: RatingBar? by mutableStateOf(null)
    var ctaView: Button? by mutableStateOf(null)
}

val LocalNativeAdRefs = compositionLocalOf { NativeAdRefs() }
/** Notifies host to (re)map asset views & bind. */
val LocalOnAssetsReady = compositionLocalOf<() -> Unit> { {} }

private fun mapAssetViews(
    adView: com.google.android.gms.ads.nativead.NativeAdView,
    refs: NativeAdRefs
) {
    refs.mediaView?.let { adView.mediaView = it }
    adView.iconView = refs.iconView
    adView.headlineView = refs.headlineView
    adView.bodyView = refs.bodyView
    adView.advertiserView = refs.advertiserView
    adView.priceView = refs.priceView
    adView.storeView = refs.storeView
    adView.starRatingView = refs.starRatingView
    adView.callToActionView = refs.ctaView
}

private fun bindNative(
    adView: com.google.android.gms.ads.nativead.NativeAdView,
    ad: NativeAd
) {
    (adView.headlineView as? TextView)?.text = ad.headline
    (adView.bodyView as? TextView)?.apply {
        val v = ad.body
        if (v.isNullOrBlank()) visibility = View.GONE else {
            visibility = View.VISIBLE; text = v
        }
    }
    (adView.advertiserView as? TextView)?.apply {
        val v = ad.advertiser
        if (v.isNullOrBlank()) visibility = View.GONE else {
            visibility = View.VISIBLE; text = v
        }
    }
    (adView.priceView as? TextView)?.apply {
        val v = ad.price
        if (v.isNullOrBlank()) visibility = View.GONE else {
            visibility = View.VISIBLE; text = v
        }
    }
    (adView.storeView as? TextView)?.apply {
        val v = ad.store
        if (v.isNullOrBlank()) visibility = View.GONE else {
            visibility = View.VISIBLE; text = v
        }
    }
    (adView.iconView as? ImageView)?.apply {
        val ic = ad.icon
        if (ic == null) visibility = View.GONE else {
            visibility = View.VISIBLE; setImageDrawable(ic.drawable)
        }
    }
    (adView.starRatingView as? RatingBar)?.apply {
        val r = ad.starRating?.toFloat()
        if (r == null || r <= 0f) visibility = View.GONE else {
            visibility = View.VISIBLE; rating = r
        }
    }
    (adView.callToActionView as? Button)?.apply {
        val v = ad.callToAction
        if (v.isNullOrBlank()) visibility = View.GONE else {
            visibility = View.VISIBLE; text = v
        }
    }

    adView.mediaView?.mediaContent = ad.mediaContent
    adView.setNativeAd(ad)
}

val LocalNativeAd = compositionLocalOf<NativeAd?> { null }
@Composable
fun NativeAdHost(
    ad: NativeAd,
    modifier: Modifier = Modifier,
    destroyOnDispose: Boolean = true,
    content: @Composable () -> Unit
) {
    val refs = remember { NativeAdRefs() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val adView =
                com.google.android.gms.ads.nativead.NativeAdView(ctx).also { refs.adView = it }
            val composeView = ComposeView(ctx).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    CompositionLocalProvider(
                        LocalNativeAd provides ad,
                        LocalNativeAdRefs provides refs,
                        LocalOnAssetsReady provides {
                            mapAssetViews(adView = adView, refs = refs)
                            bindNative(adView, ad)
                        }
                    ) { content() }
                }
            }
            adView.addView(
                composeView,
                ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )
            mapAssetViews(adView, refs)
            bindNative(adView, ad)
            adView
        },
        update = { adView ->
            mapAssetViews(adView, refs)
            bindNative(adView, ad)
        }
    )

    if (destroyOnDispose) {
        DisposableEffect(ad) { onDispose { ad.destroy() } }
    }
}
@Composable
fun NativeAdCard(
    state: NativeAdState,
    nativeView: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { /* shimmer/placeholder */ },
    timeout: @Composable (String) -> Unit = { _ -> /* timeout UI */ },
    error: @Composable (LoadAdError) -> Unit = { /* error UI */ },
    exhausted: @Composable (LoadAdError?) -> Unit = { /* no fill UI */ }
) {
    when (state) {
        is NativeAdState.Loading -> {
            loading()
        }
        is NativeAdState.Timeout ->
            timeout(state.adUnitId)
        is NativeAdState.Error -> {
            error(state.error)
        }
        is NativeAdState.Exhausted -> {
            exhausted(state.lastError)
        }
        is NativeAdState.Success -> {
            NativeAdHost(
                state.ad, modifier, true, nativeView
            )
        }
        NativeAdState.Idle,
        NativeAdState.Cancelled -> {
        }
    }
}
@Composable
fun HeadlineText(
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current
    ad?.headline?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).also { tv ->
                    refs.headlineView = tv
                    onReady()
                }
            },
            update = { tv ->
                tv.applyViewStyle(styles.view, density)
                styles.text?.let { tv.applyTextStyle(it) }
                tv.isSingleLine = maxLines == 1
                if (maxLines > 1) tv.maxLines = maxLines
                tv.ellipsize = ellipsize
            }
        )
    }
}
@Composable
fun AdvertiserText(
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current

    val txt = ad?.advertiser?.takeIf { it.isNotBlank() }
    if (txt != null) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).also { tv ->
                    refs.advertiserView = tv
                    onReady()
                }
            },
            update = { tv ->
                tv.applyViewStyle(styles.view, density)
                styles.text?.let { tv.applyTextStyle(it) }
                tv.isSingleLine = (maxLines == 1)
                if (maxLines > 1) tv.maxLines = maxLines
                tv.ellipsize = ellipsize
                tv.text = txt
            }
        )
    }
}

@Composable
fun PriceText(
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current

    val txt = ad?.price?.takeIf { it.isNotBlank() }
    if (txt != null) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).also { tv ->
                    refs.priceView = tv
                    onReady()
                }
            },
            update = { tv ->
                tv.applyViewStyle(styles.view, density)
                styles.text?.let { tv.applyTextStyle(it) }
                tv.isSingleLine = (maxLines == 1)
                if (maxLines > 1) tv.maxLines = maxLines
                tv.ellipsize = ellipsize
                tv.text = txt
            }
        )
    }
}

@Composable
fun StoreText(
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current

    val txt = ad?.store?.takeIf { it.isNotBlank() }
    if (txt != null) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).also { tv ->
                    refs.storeView = tv
                    onReady()
                }
            },
            update = { tv ->
                tv.applyViewStyle(styles.view, density)
                styles.text?.let { tv.applyTextStyle(it) }
                tv.isSingleLine = (maxLines == 1)
                if (maxLines > 1) tv.maxLines = maxLines
                tv.ellipsize = ellipsize
                tv.text = txt
            }
        )
    }
}
@Composable
fun BodyText(
    modifier: Modifier = Modifier,
    maxLines: Int? = null,
    ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.END
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current
    ad?.body?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).also { tv ->
                    refs.bodyView = tv
                    onReady()
                }
            },
            update = { tv ->
                tv.applyViewStyle(styles.view, density)
                styles.text?.let { tv.applyTextStyle(it) }
                maxLines?.let { tv.maxLines = it; tv.ellipsize = ellipsize }
            }
        )
    }
}
@Composable
fun IconView(
    modifier: Modifier = Modifier,
    scaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current
    ad?.icon?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ImageView(ctx).also { iv ->
                    refs.iconView = iv
                    onReady()
                }
            },
            update = { iv ->
                iv.applyViewStyle(styles.view, density)
                iv.scaleType = scaleType
            }
        )
    }
}
@Composable
fun MediaView(modifier: Modifier = Modifier) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current
    ad?.mediaContent?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                com.google.android.gms.ads.nativead.MediaView(ctx).also { mv ->
                    refs.mediaView = mv
                    onReady()
                }
            },
            update = { mv ->
                mv.applyViewStyle(styles.view, density)
            }
        )
    }
}
@Composable
fun StarRating(
    modifier: Modifier = Modifier,
    maxStars: Int = 5,
    stepSize: Float = 0.5f
) {
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = modifier.collectNativeStyles()
    val density = LocalDensity.current
    val ad = LocalNativeAd.current
    ad?.starRating?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                RatingBar(ctx, null, android.R.attr.ratingBarStyleSmall).also { rb ->
                    rb.numStars = maxStars; rb.stepSize = stepSize
                    refs.starRatingView = rb
                    onReady()
                }
            },
            update = { rb -> rb.applyViewStyle(styles.view, density) }
        )
    }
}
@Composable
fun ButtonCta(
    modifier: Modifier = Modifier,
    ctaStyle: Modifier = Modifier,
) {
    val ad = LocalNativeAd.current
    val refs = LocalNativeAdRefs.current
    val onReady = LocalOnAssetsReady.current
    val styles = ctaStyle.collectNativeStyles()
    val density = LocalDensity.current
    ad?.callToAction?.let {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                Button(ctx).apply {
                    refs.ctaView = this
                    onReady()
                }
            },
            update = { button ->
                button.applyViewStyle(styles.view, density)
                styles.text?.let { button.applyTextStyle(it) }
                button.text = ad?.callToAction ?: "Install"
            }
        )
    }
}




















