package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable

// The desktop half of SquareTheme (shared from the Android app): no system bars
// to tint, and the face is Inter unless Settings asks for the system's.

@Composable
internal actual fun PlatformThemeEffects(darkTheme: Boolean) = Unit

/** Set from the desktop's Settings (AppState.systemFont). */
val DesktopSystemFont = androidx.compose.runtime.mutableStateOf(false)

@Composable
internal actual fun rememberSystemFont(): Boolean = DesktopSystemFont.value
