package com.dong.adsmodule.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

fun Modifier.shimmer(
    visible: Boolean,
    alpha: Float = 1f,
    shape: Shape = RectangleShape,
    baseColor: Color = Color.LightGray.copy(alpha = 0.5f),
    highlightColor: Color = Color.LightGray.copy(alpha = 0.15f),
    durationMillis: Int = 1000,
    angleDegrees: Float = 20f,
    bandWidthFraction: Float = 0.25f
): Modifier = composed {
    if (!visible) return@composed this

    val transition = rememberInfiniteTransition(label = "shimmer")
    val anim by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )
    val (dx, dy) = remember(angleDegrees) {
        val r = Math.toRadians(angleDegrees.toDouble())
        kotlin.math.cos(r).toFloat() to kotlin.math.sin(r).toFloat()
    }

    drawWithCache {
        if (size.minDimension <= 0f || alpha <= 0f) {
            onDrawWithContent { drawContent() }
        } else {
            val w = size.width
            val band = (bandWidthFraction.coerceIn(0.1f, 0.6f)) * w
            val outline = shape.createOutline(size, layoutDirection, this)
            val cachedPath: androidx.compose.ui.graphics.Path? =
                when (outline) {
                    is Outline.Generic -> outline.path
                    is Outline.Rounded -> androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(outline.roundRect)
                    }
                    is Outline.Rectangle -> null
                }
            val baseA = baseColor.copy(alpha = baseColor.alpha * alpha)
            val hiA   = highlightColor.copy(alpha = highlightColor.alpha * alpha)
            val colors = listOf(baseA, hiA, baseA)

            onDrawWithContent {
                drawContent()
                val startX = -band + anim * (w + band * 2)
                val start = Offset(startX * dx, startX * dy)
                val end   = Offset(start.x + band * dx, start.y + band * dy)
                val brush = Brush.linearGradient(colors = colors, start = start, end = end)
                when {
                    cachedPath != null -> {
                        drawPath(path = cachedPath, color = baseA)
                        drawPath(path = cachedPath, brush = brush)
                    }
                    outline is Outline.Rectangle -> {
                        drawRect(color = baseA)
                        drawRect(brush = brush)
                    }
                    else -> {
                        drawRect(color = baseA)
                        drawRect(brush = brush)
                    }
                }
            }
        }
    }
}
