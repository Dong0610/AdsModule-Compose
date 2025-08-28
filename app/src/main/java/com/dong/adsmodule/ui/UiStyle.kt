package com.dong.adsmodule.ui
// --- Add these to your native-ad modifier file ---
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.core.view.ViewCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Background brushes
sealed class BackgroundBrush {
    data class Solid(val color: Color) : BackgroundBrush()
    data class Linear(
        val colors: List<Color>,
        /** Angle in degrees, 0 = left→right, 90 = top→bottom */
        val angleDeg: Float = 0f
    ) : BackgroundBrush()

    data class Radial(
        val colors: List<Color>,
        val centerX: Float = .5f,
        val centerY: Float = .5f,
        val radius: Dp
    ) : BackgroundBrush()

    data class Sweep(
        val colors: List<Color>,
        val centerX: Float = .5f,
        val centerY: Float = .5f
    ) : BackgroundBrush()
}

data class BorderStyle(
    val width: Dp,
    val color: Color,
    val dashWidth: Dp? = null,
    val dashGap: Dp? = null
)

sealed class SizeSpec {
    data class Dp(val dp: androidx.compose.ui.unit.Dp) : SizeSpec()
    object Match : SizeSpec()                 // MATCH_PARENT
    object Wrap : SizeSpec()                  // WRAP_CONTENT
    data class Percent(val fraction: Float) : SizeSpec() // 0f..1f (best-effort)
}

data class CornerRadii(
    val topStart: Dp? = null,
    val topEnd: Dp? = null,
    val bottomEnd: Dp? = null,
    val bottomStart: Dp? = null
)

data class RippleStyle(
    val color: Color,
    val bounded: Boolean = true
)
// View & Text styles (extended)
data class ViewStyle(
    val paddingStart: Dp? = null, val paddingTop: Dp? = null,
    val paddingEnd: Dp? = null, val paddingBottom: Dp? = null,
    val width: Dp? = null, val height: Dp? = null,
    val widthSpec: SizeSpec? = null,
    val heightSpec: SizeSpec? = null,
    val marginStart: Dp? = null, val marginTop: Dp? = null,
    val marginEnd: Dp? = null, val marginBottom: Dp? = null,
    // backgrounds
    val backgroundBrush: BackgroundBrush? = null,
    val backgroundColor: Color? = null,      // legacy solid
    // corners
    val cornerRadius: Dp? = null,            // legacy uniform
    val corners: CornerRadii? = null,        // per-corner
    // border & ripple
    val border: BorderStyle? = null,
    val ripple: RippleStyle? = null,
    // elevation
    val elevation: Dp? = null,
)

data class TextViewStyle(
    val textStyle: TextStyle? = null,
    val color: Color? = null,
    val sizeSp: Float? = null
)
// --- Modifier elements & builders ---
private sealed interface NativeAdMod : Modifier.Element
private data class ViewMod(val s: ViewStyle) : NativeAdMod
private data class TextMod(val s: TextViewStyle) : NativeAdMod

fun Modifier.adPadding(
    start: Dp? = null, top: Dp? = null, end: Dp? = null, bottom: Dp? = null, all: Dp? = null
) = this.then(
    ViewMod(
        ViewStyle(
            paddingStart = all ?: start,
            paddingTop = all ?: top,
            paddingEnd = all ?: end,
            paddingBottom = all ?: bottom
        )
    )
)

fun Modifier.adMargin(
    start: Dp? = null, top: Dp? = null, end: Dp? = null, bottom: Dp? = null, all: Dp? = null
) = this.then(
    ViewMod(
        ViewStyle(
            marginStart = all ?: start,
            marginTop = all ?: top,
            marginEnd = all ?: end,
            marginBottom = all ?: bottom
        )
    )
)

fun Modifier.adFillMaxWidth() =
    this.then(ViewMod(ViewStyle(widthSpec = SizeSpec.Match)))

fun Modifier.adWrapContentWidth() =
    this.then(ViewMod(ViewStyle(widthSpec = SizeSpec.Wrap)))

fun Modifier.adWidth(width: Dp) =
    this.then(ViewMod(ViewStyle(widthSpec = SizeSpec.Dp(width))))

fun Modifier.adWidthPercent(fraction: Float) =
    this.then(ViewMod(ViewStyle(widthSpec = SizeSpec.Percent(fraction.coerceIn(0f, 1f)))))
// HEIGHT
fun Modifier.adFillMaxHeight() =
    this.then(ViewMod(ViewStyle(heightSpec = SizeSpec.Match)))

fun Modifier.adWrapContentHeight() =
    this.then(ViewMod(ViewStyle(heightSpec = SizeSpec.Wrap)))

fun Modifier.adHeight(height: Dp) =
    this.then(ViewMod(ViewStyle(heightSpec = SizeSpec.Dp(height))))

fun Modifier.adHeightPercent(fraction: Float) =
    this.then(ViewMod(ViewStyle(heightSpec = SizeSpec.Percent(fraction.coerceIn(0f, 1f)))))
// BOTH
fun Modifier.adMatchParent() =
    this.then(ViewMod(ViewStyle(widthSpec = SizeSpec.Match, heightSpec = SizeSpec.Match)))

fun Modifier.adSize(width: Dp? = null, height: Dp? = null) =
    this.then(ViewMod(ViewStyle(width = width, height = height)))

fun Modifier.adBackground(color: Color, radius: Dp? = null) =
    this.then(
        ViewMod(
            ViewStyle(
                backgroundBrush = BackgroundBrush.Solid(color),
                cornerRadius = radius
            )
        )
    )

fun Modifier.adGradientLinear(
    colors: List<Color>,
    angleDeg: Float = 0f,
    corners: CornerRadii? = null
) =
    this.then(
        ViewMod(
            ViewStyle(
                backgroundBrush = BackgroundBrush.Linear(colors, angleDeg),
                corners = corners
            )
        )
    )

fun Modifier.adGradientRadial(
    colors: List<Color>,
    radius: Dp,
    centerX: Float = .5f,
    centerY: Float = .5f,
    corners: CornerRadii? = null
) =
    this.then(
        ViewMod(
            ViewStyle(
                backgroundBrush = BackgroundBrush.Radial(
                    colors,
                    centerX,
                    centerY,
                    radius
                ), corners = corners
            )
        )
    )

fun Modifier.adGradientSweep(
    colors: List<Color>,
    centerX: Float = .5f,
    centerY: Float = .5f,
    corners: CornerRadii? = null
) =
    this.then(
        ViewMod(
            ViewStyle(
                backgroundBrush = BackgroundBrush.Sweep(colors, centerX, centerY),
                corners = corners
            )
        )
    )

fun Modifier.adCorners(corners: CornerRadii) = this.then(ViewMod(ViewStyle(corners = corners)))
fun Modifier.adCornerRadius(all: Dp) = this.then(ViewMod(ViewStyle(cornerRadius = all)))

fun Modifier.adBorder(width: Dp, color: Color, dashWidth: Dp? = null, dashGap: Dp? = null) =
    this.then(ViewMod(ViewStyle(border = BorderStyle(width, color, dashWidth, dashGap))))

fun Modifier.adRipple(color: Color, bounded: Boolean = true) =
    this.then(ViewMod(ViewStyle(ripple = RippleStyle(color, bounded))))

fun Modifier.adElevation(elevation: Dp) = this.then(ViewMod(ViewStyle(elevation = elevation)))

fun Modifier.adTextStyle(style: TextStyle? = null, color: Color? = null, sizeSp: Float? = null) =
    this.then(TextMod(TextViewStyle(textStyle = style, color = color, sizeSp = sizeSp)))
// merge & collect
data class Collected(
    val view: ViewStyle = ViewStyle(),
    val text: TextViewStyle? = null)

private fun ViewStyle.merge(o: ViewStyle) = ViewStyle(
    paddingStart = o.paddingStart ?: paddingStart,
    paddingTop = o.paddingTop ?: paddingTop,
    paddingEnd = o.paddingEnd ?: paddingEnd,
    paddingBottom = o.paddingBottom ?: paddingBottom,
    // legacy Dp sizes
    width = o.width ?: width,
    height = o.height ?: height,
    // ✅ correct: keep SizeSpec
    widthSpec = o.widthSpec ?: widthSpec,
    heightSpec = o.heightSpec ?: heightSpec,
    marginStart = o.marginStart ?: marginStart,
    marginTop = o.marginTop ?: marginTop,
    marginEnd = o.marginEnd ?: marginEnd,
    marginBottom = o.marginBottom ?: marginBottom,
    backgroundBrush = o.backgroundBrush ?: backgroundBrush,
    backgroundColor = o.backgroundColor ?: backgroundColor,
    cornerRadius = o.cornerRadius ?: cornerRadius,
    corners = o.corners ?: corners,
    border = o.border ?: border,
    ripple = o.ripple ?: ripple,
    elevation = o.elevation ?: elevation
)

private fun TextViewStyle.merge(o: TextViewStyle) = TextViewStyle(
    textStyle = o.textStyle ?: textStyle,
    color = o.color ?: color,
    sizeSp = o.sizeSp ?: sizeSp
)

internal fun Modifier.collectNativeStyles(): Collected =
    foldIn(Collected()) { acc, e ->
        when (e) {
            is ViewMod -> acc.copy(view = acc.view.merge(e.s))
            is TextMod -> acc.copy(text = acc.text?.merge(e.s) ?: e.s)
            else -> acc
        }
    }

fun View.applyViewStyle(s: ViewStyle, density: Density, button: TextViewStyle? = null) {
    with(density) {
        // size
        val lp = (layoutParams ?: ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fun resolve(spec: SizeSpec?, legacyDp: Dp?, current: Int, isWidth: Boolean): Int {
            return when (spec) {
                is SizeSpec.Dp -> spec.dp.toPx().roundToInt()
                SizeSpec.Match -> ViewGroup.LayoutParams.MATCH_PARENT
                SizeSpec.Wrap -> ViewGroup.LayoutParams.WRAP_CONTENT
                is SizeSpec.Percent -> {
                    // Best-effort percent: adjust after parent is laid out
                    val parentView = parent as? View
                    val base = (parentView?.let { if (isWidth) it.width else it.height }) ?: 0
                    if (base > 0) (base * spec.fraction).toInt() else current.also {
                        // Retry after layout pass
                        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                v: View?, l: Int, t: Int, r: Int, btm: Int,
                                ol: Int, ot: Int, orr: Int, ob: Int
                            ) {
                                val p = parent as? View ?: return
                                val newSize =
                                    ((if (isWidth) p.width else p.height) * spec.fraction).toInt()
                                val newLp = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                                    if (isWidth) width = newSize else height = newSize
                                }
                                layoutParams = newLp
                                removeOnLayoutChangeListener(this)
                            }
                        })
                    }
                }
                null -> legacyDp?.toPx()?.roundToInt() ?: current
            }
        }

        lp.width = resolve(s.widthSpec, s.width, lp.width, isWidth = true)
        lp.height = resolve(s.heightSpec, s.height, lp.height, isWidth = false)
        layoutParams = lp
        // ---- margins (unchanged) ----
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { mlp ->
            s.marginStart?.let { mlp.marginStart = it.toPx().roundToInt() }
            s.marginTop?.let { mlp.topMargin = it.toPx().roundToInt() }
            s.marginEnd?.let { mlp.marginEnd = it.toPx().roundToInt() }
            s.marginBottom?.let { mlp.bottomMargin = it.toPx().roundToInt() }
            layoutParams = mlp
        }
        // ---- padding (unchanged) ----
        val ps = s.paddingStart?.toPx()?.roundToInt() ?: paddingStart
        val pt = s.paddingTop?.toPx()?.roundToInt() ?: paddingTop
        val pe = s.paddingEnd?.toPx()?.roundToInt() ?: paddingEnd
        val pb = s.paddingBottom?.toPx()?.roundToInt() ?: paddingBottom
        setPaddingRelative(ps, pt, pe, pb)
        // background / gradient / corners / border
        val needBg = (s.backgroundBrush != null || s.backgroundColor != null
                || s.cornerRadius != null || s.corners != null || s.border != null)
        if (needBg) {
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // corners
                val arr = s.corners?.let {
                    floatArrayOf(
                        (it.topStart ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.topStart ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.topEnd ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.topEnd ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.bottomEnd ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.bottomEnd ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.bottomStart ?: s.cornerRadius ?: 0.dp).toPx(),
                        (it.bottomStart ?: s.cornerRadius ?: 0.dp).toPx()
                    )
                }
                if (arr != null) cornerRadii = arr else s.cornerRadius?.let {
                    cornerRadius = it.toPx()
                }

                when (val bg = s.backgroundBrush) {
                    is BackgroundBrush.Solid -> setColor(bg.color.toArgb())
                    is BackgroundBrush.Linear -> {
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        orientation = angleToOrientation(bg.angleDeg)
                        colors = bg.colors.map { it.toArgb() }.toIntArray()
                    }
                    is BackgroundBrush.Radial -> {
                        gradientType = GradientDrawable.RADIAL_GRADIENT
                        setGradientCenter(bg.centerX, bg.centerY)
                        gradientRadius = bg.radius.toPx()
                        colors = bg.colors.map { it.toArgb() }.toIntArray()
                    }
                    is BackgroundBrush.Sweep -> {
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        setGradientCenter(bg.centerX, bg.centerY)
                        colors = bg.colors.map { it.toArgb() }.toIntArray()
                    }
                    null -> s.backgroundColor?.let { setColor(it.toArgb()) }
                }

                s.border?.let { b ->
                    val w = b.width.toPx().roundToInt()
                    val dw = b.dashWidth?.toPx() ?: 0f
                    val dg = b.dashGap?.toPx() ?: 0f
                    setStroke(w, b.color.toArgb(), dw, dg)
                }
            }
            background = gd
            clipToOutline = true
        }

        s.elevation?.let { ViewCompat.setElevation(this@applyViewStyle, it.toPx()) }
    }
}


fun TextView.applyTextStyle(ts: TextViewStyle) {
    ts.color?.let { setTextColor(it.toArgb()) }
    ts.textStyle?.let { st ->
        if (ts.color == null && st.color.isSpecified) setTextColor(st.color.toArgb())
        if (st.fontSize != TextUnit.Unspecified) textSize = st.fontSize.value
        st.lineHeight.takeIf { it != TextUnit.Unspecified }?.let {
            val add = it.value - textSize
            if (add > 0f) setLineSpacing(add, 1f)
        }
    }
    ts.sizeSp?.let { textSize = it }
}
// small utils
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt()
)

private fun angleToOrientation(angle: Float): GradientDrawable.Orientation {
    // Map to 8-way orientation (closest)
    val a = ((angle % 360) + 360) % 360
    return when {
        a < 22.5f || a >= 337.5f -> GradientDrawable.Orientation.LEFT_RIGHT
        a < 67.5f -> GradientDrawable.Orientation.BL_TR
        a < 112.5f -> GradientDrawable.Orientation.BOTTOM_TOP
        a < 157.5f -> GradientDrawable.Orientation.BR_TL
        a < 202.5f -> GradientDrawable.Orientation.RIGHT_LEFT
        a < 247.5f -> GradientDrawable.Orientation.TR_BL
        a < 292.5f -> GradientDrawable.Orientation.TOP_BOTTOM
        else -> GradientDrawable.Orientation.TL_BR
    }
}

