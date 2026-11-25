package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver

// The desktop half of SquareTheme (shared from the Android app): no system bars
// to tint, and the face is Inter unless Settings asks for the system's.

@Composable
internal actual fun PlatformThemeEffects(darkTheme: Boolean) = Unit

/** Set from the desktop's Settings (AppState.systemFont). */
val DesktopSystemFont = androidx.compose.runtime.mutableStateOf(false)

/**
 * Single process-wide FontFamily.Resolver shared across Main and MiniPlayer windows so each
 * window teardown/recreate does not re-parse `inter.ttf` or allocate duplicate Skia FontCollections.
 */
val SharedFontFamilyResolver: FontFamily.Resolver by lazy { createFontFamilyResolver() }

@Composable
internal actual fun rememberSystemFont(): Boolean = DesktopSystemFont.value
