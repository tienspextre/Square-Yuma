package dev.lelonio.square.ui.glass.floatingtabbar

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import dev.lelonio.square.ui.glass.backdrop.internal.ColorFilterEffect
import dev.lelonio.square.ui.glass.backdrop.isRenderEffectSupported

private const val ThresholdSlope = 10f
private const val ThresholdMidpoint255 = 0.5f * 255f

private val ThresholdColorFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, ThresholdSlope, -ThresholdSlope * ThresholdMidpoint255
        )
    )
)

private val gooeyEffectCache = HashMap<Int, RenderEffect>()

private fun gooeyRenderEffect(blurRadiusPx: Float): RenderEffect? {
    val key = blurRadiusPx.toInt()
    if (key <= 0) return null
    gooeyEffectCache[key]?.let { return it }
    return try {
        val blur = BlurEffect(null, key.toFloat(), key.toFloat(), TileMode.Decal)
        ColorFilterEffect(blur, ThresholdColorFilter).also { gooeyEffectCache[key] = it }
    } catch (_: Exception) {
        null
    }
}

fun Modifier.gooey(blurRadiusPx: () -> Float): Modifier {
    if (!isRenderEffectSupported()) return this
    return this.graphicsLayer {
        val effect = gooeyRenderEffect(blurRadiusPx())
        if (effect != null) {
            compositingStrategy = CompositingStrategy.Offscreen
            renderEffect = effect
        } else {
            compositingStrategy = CompositingStrategy.Auto
            renderEffect = null
        }
    }
}
