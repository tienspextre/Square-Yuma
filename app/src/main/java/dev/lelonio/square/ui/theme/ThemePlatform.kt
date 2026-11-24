package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

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
