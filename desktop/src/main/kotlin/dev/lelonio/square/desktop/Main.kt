package dev.lelonio.square.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** A mood or genre mix, keyed as SubsonicMixes keys it ("mood:Chill", "genre:Pop"). */
    data class Mix(val key: String, val name: String) : Screen
    data object Month : Screen
    data object Downloads : Screen
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

/**
 * Skiko's drawing backend. Auto is Direct3D on Windows and OpenGL on Linux; a
 * driver that draws the glass wrong can be swapped out here. Read once at start.
 */
enum class Renderer(val label: String, val api: String?) { Auto("Auto", null), OpenGL("OpenGL", "OPENGL"), Software("Software", "SOFTWARE") }

private val uiPrefs = Preferences.userRoot().node("square-yuma/ui")

class AppState {
    private val ui = uiPrefs
    val prefs = SubsonicPreferences()
    val client = SubsonicClient(Http.client, prefs)
    val downloads = DownloadStore()
    val player = DesktopPlayer { downloads.fileFor(it.id)?.path ?: client.buildStreamUrl(it.id) }.apply {
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

    /** A line at the foot of the window for a moment: "Removed from playlist", a failure. */
    var toast by mutableStateOf<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun say(text: String) {
        toast = text
        scope.launch { delay(3500); if (toast == text) toast = null }
    }

    val listening = ListeningStore()

    /** Grid or list for the Library and Playlists pages, as the phone's layout toggle. */
    var listMode by mutableStateOf(ui.getBoolean("listMode", false))
        private set
    fun toggleListMode() { listMode = !listMode; ui.putBoolean("listMode", listMode) }

    /** The last searches, newest first. */
    val searchHistory = mutableStateListOf<String>().apply { addAll(ui.get("searches", "").split('\n').filter { it.isNotBlank() }) }
    fun remember(query: String) {
        val q = query.trim().takeIf { it.length > 1 } ?: return
        searchHistory.remove(q); searchHistory.add(0, q)
        while (searchHistory.size > 20) searchHistory.removeLast()
        ui.put("searches", searchHistory.joinToString("\n"))
    }
    fun clearSearches() { searchHistory.clear(); ui.remove("searches") }

    /** Playlists pinned to the sidebar, as the phone pins them to the top of Library. */
    data class Pin(val id: String, val name: String, val cover: String?)
    val pins = mutableStateListOf<Pin>().apply {
        ui.get("pins", "").split('\n').mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { Pin(it[0], it[1], it.getOrNull(2)?.ifBlank { null }) } }.let(::addAll)
    }
    fun togglePin(id: String, name: String, cover: String?) {
        if (pins.removeAll { it.id == id }.not()) pins.add(Pin(id, name, cover))
        ui.put("pins", pins.joinToString("\n") { "${it.id}\t${it.name}\t${it.cover.orEmpty()}" })
    }

    fun download(songs: List<SubsonicChildSong>) = scope.launch {
        var failed = 0
        songs.filterNot { downloads.has(it.id) }.forEach { s -> downloads.download(s, client.buildDownloadUrl(s.id)).onFailure { failed++ } }
        say(if (failed == 0) "Downloaded ${songs.size} song${if (songs.size == 1) "" else "s"}" else "$failed download(s) failed")
    }

    /** The song's page in Navidrome's own web player, the link a listener can send. */
    fun linkFor(s: SubsonicChildSong): String {
        val base = prefs.serverUrl.value.trimEnd('/')
        return if (s.albumId != null) "$base/app/#/album/${s.albumId}/show" else "$base/app/#/song/${s.id}/show"
    }

    /** When the sleep timer pauses playback (epoch ms); "end of track" lives on the player. */
    var sleepEndsAt by mutableStateOf<Long?>(null)
        private set
    fun sleepIn(minutes: Int?) {
        sleepEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        player.stopAfterCurrent.value = false
    }
    fun sleepAtEndOfTrack() { sleepEndsAt = null; player.stopAfterCurrent.value = true }

    var eqGains by mutableStateOf(ui.get("eq", "").split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == Eq.BANDS.size }?.toFloatArray() ?: FloatArray(Eq.BANDS.size))
        private set
    fun setEq(gains: FloatArray, restart: Boolean = true) {
        eqGains = gains
        ui.put("eq", gains.joinToString(","))
        player.audioFilter = Eq.filter(gains)
        if (restart) player.reapply()
    }

    /** Moves the lyrics against the music: positive shows each line later. */
    var lyricsOffsetMs by mutableStateOf(ui.getLong("lyricsOffset", 0L))
        private set
    fun setLyricsOffset(ms: Long) { lyricsOffsetMs = ms; ui.putLong("lyricsOffset", ms) }

    init {
        player.audioFilter = Eq.filter(eqGains)
        // Once a second: the month's listening and the sleep timer.
        scope.launch {
            var songId: String? = null
            var heard = 0L
            var ticks = 0
            while (true) {
                delay(1000)
                val song = player.current.value
                if (song?.id != songId) { songId = song?.id; heard = 0 }
                if (song != null && player.isPlaying.value) {
                    heard += 1000
                    listening.add(song, java.time.YearMonth.now().toString(), 1000, play = heard == ListeningStore.PLAY_MS)
                    if (++ticks % 30 == 0) listening.save()
                }
                sleepEndsAt?.let { if (System.currentTimeMillis() >= it) { sleepEndsAt = null; player.pause() } }
            }
        }
    }

    fun open(screen: Screen) { navMove = NavMove.Push; stack.add(screen); nowPlaying = false }
    fun root(screen: Screen) { navMove = NavMove.Root; stack.clear(); stack.add(screen); nowPlaying = false }
    fun back() { if (nowPlaying) nowPlaying = false else if (stack.size > 1) { navMove = NavMove.Pop; stack.removeLast() } }

    fun cover(id: String?, size: Int = 300) = client.buildCoverArtUrl(id, size)

    var renderer by mutableStateOf(runCatching { Renderer.valueOf(ui.get("renderer", "Auto")) }.getOrDefault(Renderer.Auto))
        private set
    fun chooseRenderer(r: Renderer) { renderer = r; ui.put("renderer", r.name) }
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

fun main(args: Array<String>) {
    if ("--self-check" in args) { selfCheck(); println("self-check ok"); return }
    runCatching { Renderer.valueOf(uiPrefs.get("renderer", "Auto")) }.getOrNull()?.api?.let { System.setProperty("skiko.renderApi", it) }
    app()
}

private fun app() = application {
    val state = androidx.compose.runtime.remember { AppState() }
    Window(
        onCloseRequest = { state.listening.save(); exitApplication() },
        title = "Square-Yuma",
        icon = painterResource("square.png"),
        state = rememberWindowState(size = DpSize(1280.dp, 840.dp)),
        onKeyEvent = state::onKey,
    ) {
        window.minimumSize = Dimension(940, 620)
        App(state)
    }
}
