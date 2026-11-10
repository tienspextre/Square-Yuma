package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// The Android half of SquareTheme; the desktop app has its own file of the same name.

@Composable
internal fun PlatformThemeEffects(darkTheme: Boolean) {
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
internal fun rememberSystemFont(): Boolean {
    // The listener's choice of face; see PreferencesStore.systemFont.
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemFont by remember(context) {
        (context.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.systemFont
            ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }.collectAsState()
    return systemFont
}

/**
 * The listener's light or dark, over the phone's; see PreferencesStore.themeMode.
 *
 * Put in as the configuration's own night mode rather than handed to the
 * theme: `isSystemInDarkTheme` is read in the theme and in the glass
 * components on their own, and every one of them reads it from here. Nothing
 * else in the configuration is touched, and on "system" it is the phone's,
 * untouched.
 */
@Composable
fun ChosenTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by remember(context) {
        (context.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.themeMode
            ?: kotlinx.coroutines.flow.MutableStateFlow(dev.lelonio.square.data.PreferencesStore.ThemeMode.SYSTEM)
    }.collectAsState()
    val base = androidx.compose.ui.platform.LocalConfiguration.current
    val night = when (mode) {
        dev.lelonio.square.data.PreferencesStore.ThemeMode.SYSTEM -> null
        dev.lelonio.square.data.PreferencesStore.ThemeMode.LIGHT -> android.content.res.Configuration.UI_MODE_NIGHT_NO
        dev.lelonio.square.data.PreferencesStore.ThemeMode.DARK -> android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val configuration = remember(base, night) {
        if (night == null) {
            base
        } else {
            android.content.res.Configuration(base).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
        }
    }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalConfiguration provides configuration,
        content = content,
    )
}
