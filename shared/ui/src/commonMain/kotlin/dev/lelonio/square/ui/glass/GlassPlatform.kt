package dev.lelonio.square.ui.glass

import androidx.compose.runtime.Composable

// Per-platform halves of GlassEffect.kt: Android gates on API level and low-RAM devices, the desktop's Skia always can.

expect fun isGlassSupported(): Boolean

@Composable
expect fun isLowRamDevice(): Boolean

/** Combines the capability checks; use this to gate glass. */
@Composable
expect fun isGlassAllowed(): Boolean

/** The scale the backdrop is recorded at, given what the platform wants (the desktop keeps detail per dp at 1x). */
internal expect fun platformBackdropScale(scale: Float, density: Float): Float

/** Suspends until the next frame is drawn. */
expect suspend fun awaitFrame()
