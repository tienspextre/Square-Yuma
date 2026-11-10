package dev.lelonio.square.ui.glass

import androidx.compose.runtime.Composable

// The desktop half of GlassEffect.kt: Skia runs the whole blur/lens chain on any GPU this app targets.

fun isGlassSupported(): Boolean = true

@Composable
fun isLowRamDevice(): Boolean = false

@Composable
fun isGlassAllowed(): Boolean = true
