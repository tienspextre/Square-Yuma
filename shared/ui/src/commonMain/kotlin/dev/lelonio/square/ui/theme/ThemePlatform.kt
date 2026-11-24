package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

// Per-platform halves of Theme.kt: Android tints the system bars and reads the listener's font choice; the desktop has neither.

@Composable
internal expect fun PlatformThemeEffects(darkTheme: Boolean)

/** True when the listener asked for the system's own typeface instead of [Inter]. */
@Composable
internal expect fun rememberSystemFont(): Boolean

/** The app's own face, from one variable file. */
expect val Inter: FontFamily
