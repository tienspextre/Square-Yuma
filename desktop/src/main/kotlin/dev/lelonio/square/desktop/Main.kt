package dev.lelonio.square.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lelonio.square.backend.subsonic.SubsonicClient
import dev.lelonio.square.backend.subsonic.SubsonicPreferences
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import java.awt.Dimension
import java.util.prefs.Preferences

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Playlists : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Album(val id: String, val name: String) : Screen
    data class Artist(val id: String, val name: String) : Screen
    data class Playlist(val id: String, val name: String) : Screen
}

enum class ThemeMode { DARK, LIGHT }

enum class NavMove { Push, Pop, Root }

/** The phone's three glass profiles, with the same materials. */
enum class GlassProfile {
    Performance, Balanced, Quality;

    fun config(): GlassEffectConfig = when (this) {
        Performance -> GlassEffectConfig(style = GlassStyle.TRANSPARENT, surfaceOpacity = 0.55f)
        Balanced -> GlassEffectConfig()
        Quality -> GlassEffectConfig(vibrancy = 1.4f, blurRadius = 4f, lensHeight = 0.5f, lensAmount = 0.8f, chromaticAberration = true)
    }
}

class AppState {
    private val ui = Preferences.userRoot().node("square-yuma/ui")
    val prefs = SubsonicPreferences()
    val client = SubsonicClient(Http.client, prefs)
    val player = DesktopPlayer { client.buildStreamUrl(it.id) }.apply {
        volume.value = ui.getFloat("volume", 0.8f)
        crossfadeSeconds = ui.getInt("crossfade", 6)
    }

    val stack = mutableStateListOf<Screen>(if (prefs.isConfigured()) Screen.Home else Screen.Settings)
    var nowPlaying by mutableStateOf(false)
    var typing by mutableStateOf(false)
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(ui.get("theme", "DARK")) }.getOrDefault(ThemeMode.DARK))
        private set
    var glassProfile by mutableStateOf(runCatching { GlassProfile.valueOf(ui.get("glass", "Balanced")) }.getOrDefault(GlassProfile.Balanced))
        private set
    /** 0 = the bar, 1 = the full player; dragged and sprung like the phone's sheet. */
    val expand = Animatable(0f)
    var navMove by mutableStateOf(NavMove.Root)
    var miniArt by mutableStateOf<Rect?>(null)
    var bigArt by mutableStateOf<Rect?>(null)
    var showQueue by mutableStateOf(false)
    /** Bumped after a server change so every screen reloads. */
    var generation by mutableStateOf(0)

    fun open(screen: Screen) { navMove = NavMove.Push; stack.add(screen); nowPlaying = false }
    fun root(screen: Screen) { navMove = NavMove.Root; stack.clear(); stack.add(screen); nowPlaying = false }
    fun back() { if (nowPlaying) nowPlaying = false else if (stack.size > 1) { navMove = NavMove.Pop; stack.removeLast() } }

    fun cover(id: String?, size: Int = 300) = client.buildCoverArtUrl(id, size)

    fun setGlass(p: GlassProfile) { glassProfile = p; ui.put("glass", p.name) }
    fun setTheme(mode: ThemeMode) { themeMode = mode; ui.put("theme", mode.name) }
    fun setCrossfade(seconds: Int) { player.crossfadeSeconds = seconds; ui.putInt("crossfade", seconds) }
    fun setVolume(v: Float) { player.volume.value = v.coerceIn(0f, 1f); ui.putFloat("volume", player.volume.value) }

    /** Space, arrows and friends, unless a text field has the keyboard. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val mod = e.isCtrlPressed || e.isMetaPressed
        if (mod && e.key == Key.F) { root(Screen.Search); return true }
        if (e.key == Key.Escape) { back(); return true }
        if (typing) return false
        when (e.key) {
            Key.Spacebar -> player.toggle()
            Key.DirectionRight -> if (mod) player.next() else player.seek(player.positionMs.value + 10_000)
            Key.DirectionLeft -> if (mod) player.previous() else player.seek(player.positionMs.value - 10_000)
            Key.DirectionUp -> setVolume(player.volume.value + 0.05f)
            Key.DirectionDown -> setVolume(player.volume.value - 0.05f)
            Key.L -> nowPlaying = !nowPlaying
            else -> return false
        }
        return true
    }
}

fun main() = application {
    val state = androidx.compose.runtime.remember { AppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Square-Yuma",
        icon = painterResource("square.png"),
        state = rememberWindowState(size = DpSize(1280.dp, 840.dp)),
        onKeyEvent = state::onKey,
    ) {
        window.minimumSize = Dimension(940, 620)
        App(state)
    }
}
