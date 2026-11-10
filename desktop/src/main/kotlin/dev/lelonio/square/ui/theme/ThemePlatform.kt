package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable

// The desktop half of SquareTheme (shared from the Android app): no system bars
// to tint, and the face is always Inter.

@Composable
internal fun PlatformThemeEffects(darkTheme: Boolean) = Unit

@Composable
internal fun rememberSystemFont(): Boolean = false
