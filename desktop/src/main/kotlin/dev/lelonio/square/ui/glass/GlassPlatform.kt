package dev.lelonio.square.ui.glass

import androidx.compose.runtime.Composable

// The desktop half of GlassEffect.kt: Skia runs the whole blur/lens chain on any GPU this app targets.

fun isGlassSupported(): Boolean = true

@Composable
fun isLowRamDevice(): Boolean = false

@Composable
fun isGlassAllowed(): Boolean = true

/**
 * The scales the glass asks for (0.4 for a button, 0.3 under a heavy blur) were
 * tuned on a ~2.75x phone screen. At a monitor's 1x they leave a few pixels per
 * dp, and the lens magnifies that into blocks; so the same detail per dp is kept.
 */
internal fun platformBackdropScale(scale: Float, density: Float): Float = (scale * PHONE_DENSITY / density).coerceAtMost(1f)

private const val PHONE_DENSITY = 2.75f
