package dev.lelonio.square.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.lelonio.square.ui.theme.scrimColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * The room the player and the pages are in, made out of the record that is
 * playing — the desktop's AmbientArtworkBackground, with the Android one's
 * numbers: a far copy blurred at 96 px filling the window, a near one at 192 px
 * over it at 30 %, a drift too slow to catch (12 s, reversing), and a 720 ms
 * swap on a track change that swells the leaving copy out and the arriving one in.
 * As there, the blur is done once per cover at decode, never as a per-frame effect.
 */
@Composable
fun AmbientBackground(url: String?, tone: Color, modifier: Modifier = Modifier, settleFrom: Float = 0.55f) {
    val far = remember { mutableStateOf<Pair<ImageBitmap?, ImageBitmap?>>(null to null) } // leaving, arriving
    val near = remember { mutableStateOf<Pair<ImageBitmap?, ImageBitmap?>>(null to null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(url) {
        val f = url?.let { blurred(it, FAR_PX, FAR_SIGMA) }
        val n = url?.let { blurred(it, NEAR_PX, NEAR_SIGMA) }
        far.value = far.value.second to f
        near.value = near.value.second to n
        swap.snapTo(0f)
        launch { swap.animateTo(1f, tween(SWAP_MS, easing = FastOutSlowInEasing)) }
    }
    val drift by rememberInfiniteTransition().animateFloat(
        -1f, 1f, infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse),
    )
    val toneNow by animateColorAsState(tone, tween(TONE_MS))
    val scrim = scrimColor()

    Box(modifier.fillMaxSize().background(toneNow)) {
        Copy(far.value.first, { (1f - swap.value) }, { 1f + LEAVE_SWELL * swap.value }, { drift }, 1.25f, 0.02f)
        Copy(far.value.second, { swap.value }, { 1f + ARRIVE_SWELL * (1f - swap.value) }, { drift }, 1.25f, 0.02f)
        Copy(near.value.first, { (1f - swap.value) * NEAR_ALPHA }, { NEAR_SCALE }, { -drift }, NEAR_SCALE, NEAR_TRAVEL)
        Copy(near.value.second, { swap.value * NEAR_ALPHA }, { NEAR_SCALE }, { -drift }, NEAR_SCALE, NEAR_TRAVEL)
        // The field settles onto the record's own tone towards the foot, then a scrim for the ink.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    settleFrom to toneNow.copy(alpha = 0.35f),
                    (settleFrom + (1f - settleFrom) * 0.42f) to toneNow.copy(alpha = 0.72f),
                    1f to toneNow,
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to scrim.copy(alpha = 0.16f), 1f to scrim.copy(alpha = 0.34f))))
    }
}

@Composable
private fun Copy(img: ImageBitmap?, alpha: () -> Float, swell: () -> Float, drift: () -> Float, baseScale: Float, travel: Float) {
    if (img == null) return
    Image(
        img, null,
        Modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha()
            val s = baseScale * swell()
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0f)
            translationY = size.height * travel * drift()
        },
        contentScale = ContentScale.Crop,
    )
}

private val blurCache = HashMap<String, ImageBitmap>()

private suspend fun blurred(url: String, px: Int, sigma: Float): ImageBitmap? {
    val key = "$px:" + url.substringAfter("id=").substringBefore('&')
    blurCache[key]?.let { return it }
    val src = loadArtwork(url) ?: return null
    return withContext(Dispatchers.Default) {
        val image = Image.makeFromBitmap(src.asSkiaBitmap())
        val surface = Surface.makeRasterN32Premul(px, px)
        val paint = Paint().apply { imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP) }
        surface.canvas.drawImageRect(image, Rect.makeWH(px.toFloat(), px.toFloat()), paint)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.also { blurCache[key] = it }
}

private const val FAR_PX = 96
private const val NEAR_PX = 192
// BlurTransformation(radius 16, 3 passes) and (10, 2) on Android: box passes ≈ a gaussian of this sigma.
private const val FAR_SIGMA = 16f
private const val NEAR_SIGMA = 8f
private const val NEAR_SCALE = 1.06f
private const val NEAR_ALPHA = 0.30f
private const val NEAR_TRAVEL = 0.014f
private const val DRIFT_MS = 12_000
private const val SWAP_MS = 720
private const val TONE_MS = 900
private const val LEAVE_SWELL = 0.05f
private const val ARRIVE_SWELL = 0.06f
