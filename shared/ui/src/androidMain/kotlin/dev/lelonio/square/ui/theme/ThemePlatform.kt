package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal actual fun PlatformThemeEffects(darkTheme: Boolean) {
    // System bar icons have to flip with the theme; left alone they are drawn
    // for the system's own theme and disappear against ours.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            // Dark icons over a light page and light ones over a dark page:
            // the bars sit over the artwork's own colour, which is taken in
            // whichever direction the system asked for.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}

@Composable
internal actual fun rememberSystemFont(): Boolean {
    // The listener's choice of face; the app installs it from PreferencesStore.systemFont.
    val systemFont by AndroidThemeHooks.systemFont.collectAsState()
    return systemFont
}

/** What the phone's theme reads from the app's settings; SquareApplication sets it at start. */
object AndroidThemeHooks {
    @Volatile var systemFont: kotlinx.coroutines.flow.StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
}
