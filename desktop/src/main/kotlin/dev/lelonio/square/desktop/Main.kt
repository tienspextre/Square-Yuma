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
import dev.lelonio.square.backend.subsonic.SubsonicCatalog
import dev.lelonio.square.backend.subsonic.SubsonicClient
import dev.lelonio.square.backend.subsonic.SubsonicPreferences
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import java.awt.Dimension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object LocalFolders : Screen
    data object Playlists : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Album(val id: String, val name: String) : Screen
    data class Artist(val id: String, val name: String) : Screen
    data class LocalAlbum(val uri: String, val name: String) : Screen
    data class LocalArtist(val uri: String, val name: String) : Screen
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
    val dataDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma")
    val prefs = SubsonicPreferences()
    val client = SubsonicClient(Http.client, prefs)
    val downloads = DownloadStore(
        indexPath = dataDir.resolve("downloads.json"),
        rootPath = Paths.get(System.getProperty("user.home"), "Music", "Square-Yuma"),
    )
    val subsonicCatalog = SubsonicCatalog(client, downloads)
    val local = LocalCatalog(dataDir)
    val queuePersistence = QueuePersistence(dataDir.resolve("queue.json"))

    var offlineMode by mutableStateOf(ui.getBoolean("offlineMode", false))
        private set
    fun useOfflineMode(on: Boolean) {
        offlineMode = on
        downloads.offlineMode = on
        ui.putBoolean("offlineMode", on)
        generation++
    }

    var cacheLimitGb by mutableStateOf(ui.getInt("cacheLimitGb", 0))
        private set
    fun setCacheLimit(gb: Int) {
        val clamped = gb.coerceAtLeast(0)
        cacheLimitGb = clamped
        ui.putInt("cacheLimitGb", clamped)
        downloads.setCacheLimitBytes(clamped.toLong() * 1_073_741_824L)
    }

    fun sourceFor(song: SubsonicChildSong): String =
        if (song.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)) {
            local.findById(song.id)?.path ?: song.path.orEmpty()
        } else {
            downloads.fileFor(song.id)?.absolutePath ?: client.buildStreamUrl(song.id)
        }

    val loudness = LoudnessCache(dataDir.resolve("analysis"), sourceResolver = ::sourceFor)

    val player = DesktopPlayer(
        streamUrl = ::sourceFor,
        coverBytes = { s ->
            val u = cover(s.coverArt, 300) ?: return@DesktopPlayer null
            loadArtworkBytes(u)
        },
        durationMsOf = local::durationMsOf,
    ).apply {
        volume.value = ui.getFloat("volume", 0.8f)
        crossfadeSeconds = ui.getInt("crossfade", 6)
        loudnessResolver = { song, onMeasured ->
            loudness.gainDbFor(song) ?: null.also { loudness.request(song, onMeasured) }
        }
    }

    val stack = mutableStateListOf<Screen>(
        if (prefs.isConfigured() || local.folders.value.isNotEmpty() || downloads.kept.isNotEmpty()) Screen.Home else Screen.Settings,
    )
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
        val eligible = songs.filter { !it.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX) && !downloads.has(it.id) }
        var failed = 0
        eligible.forEach { s ->
            downloads.download(s, client.buildDownloadUrl(s.id), client.buildStreamUrl(s.id)).onFailure { failed++ }
        }
        say(if (failed == 0) "Downloaded ${eligible.size} song${if (eligible.size == 1) "" else "s"}" else "$failed download(s) failed")
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
        player.setSleepTimer(minutes?.let { it * 60_000L } ?: -1L, 5_000L, false)
    }
    fun sleepAtEndOfTrack() {
        sleepEndsAt = null
        player.stopAfterCurrent.value = true
        player.setSleepTimer(-1L, 0L, true)
    }

    var eqGains by mutableStateOf(ui.get("eq", "").split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == Eq.BANDS.size }?.toFloatArray() ?: FloatArray(Eq.BANDS.size))
        private set
    fun setEq(gains: FloatArray, @Suppress("UNUSED_PARAMETER") restart: Boolean = true) {
        eqGains = gains
        ui.put("eq", gains.joinToString(","))
        player.applyDsp(fx, gains)
    }

    var fx by mutableStateOf(
        Fx(ui.getFloat("speed", 1f), ui.getFloat("pitch", 1f), ui.getFloat("reverb", 0f), ui.getFloat("karaoke", 0f), ui.getBoolean("loudness", false))
    )
        private set
    fun setFx(new: Fx, @Suppress("UNUSED_PARAMETER") restart: Boolean = true) {
        fx = new
        ui.putFloat("speed", new.speed); ui.putFloat("pitch", new.pitch); ui.putFloat("reverb", new.reverb)
        ui.putFloat("karaoke", new.karaoke); ui.putBoolean("loudness", new.loudness)
        player.applyDsp(new, eqGains)
    }

    /** Smart Automix, its bass swap and Loop Mix, as the phone's switches (all three on the phone's defaults). */
    val analyses = Analyses(::sourceFor, local::durationMsOf)
    var smartAutomix by mutableStateOf(ui.getBoolean("smartAutomix", true))
        private set
    var bassSwap by mutableStateOf(ui.getBoolean("bassSwap", true))
        private set
    var loopMix by mutableStateOf(ui.getBoolean("loopMix", false))
        private set
    fun useAutomix(smart: Boolean = smartAutomix, bass: Boolean = bassSwap, loop: Boolean = loopMix) {
        smartAutomix = smart; bassSwap = bass; loopMix = loop
        ui.putBoolean("smartAutomix", smart); ui.putBoolean("bassSwap", bass); ui.putBoolean("loopMix", loop)
        player.planner = if (!smart) null else { out, inc ->
            analyses.plan(out, inc, player.queue.value, player.shuffle.value, maxOf(player.crossfadeSeconds * 1000L, 3_000L), bassSwap, loopMix)
        }
    }

    var trimSilence by mutableStateOf(ui.getBoolean("trimSilence", true))
        private set
    fun useTrimSilence(on: Boolean) { trimSilence = on; ui.putBoolean("trimSilence", on); player.trimSilence = on }
    var skipFade by mutableStateOf(ui.getBoolean("skipFade", true))
        private set
    fun useSkipFade(on: Boolean) { skipFade = on; ui.putBoolean("skipFade", on); player.skipFade = on }

    var language by mutableStateOf(Language.entries.firstOrNull { it.code == ui.get("lang", java.util.Locale.getDefault().language) } ?: Language.English)
        private set
    fun chooseLanguage(l: Language) { language = l; ui.put("lang", l.code); Strings.current = l }

    /** The phone's font choice: Inter, the app's own, or the system's. */
    var systemFont by mutableStateOf(ui.getBoolean("systemFont", false))
        private set
    fun useSystemFont(on: Boolean) { systemFont = on; ui.putBoolean("systemFont", on); dev.lelonio.square.ui.theme.DesktopSystemFont.value = on }

    private val picks = Preferences.userRoot().node("square-yuma/lyrics-pick")
    /** The source the listener chose for one song in the picker, kept for next time. */
    fun lyricsPick(songId: String): LyricSource? = picks.get(songId, null)?.let { runCatching { LyricSource.valueOf(it) }.getOrNull() }
    fun setLyricsPick(songId: String, source: LyricSource?) { if (source == null) picks.remove(songId) else picks.put(songId, source.name) }

    /** Animated (Accompanist's karaoke) or Classic, the phone's two lyric styles. */
    var classicLyrics by mutableStateOf(ui.getBoolean("classicLyrics", false))
        private set
    fun useClassicLyrics(on: Boolean) { classicLyrics = on; ui.putBoolean("classicLyrics", on) }

    var translateLyrics by mutableStateOf(ui.getBoolean("translateLyrics", false))
        private set
    fun useTranslation(on: Boolean) { translateLyrics = on; ui.putBoolean("translateLyrics", on) }
    /** The language lyrics are translated into: the app's language, as on the phone. */
    val translateTo: String get() = language.code

    /** Moves the lyrics against the music: positive shows each line later. */
    var lyricsOffsetMs by mutableStateOf(ui.getLong("lyricsOffset", 0L))
        private set
    fun setLyricsOffset(ms: Long) { lyricsOffsetMs = ms; ui.putLong("lyricsOffset", ms) }

    /** A newer PC release, found at start or from Settings. */
    var update by mutableStateOf<Update?>(null)
    var updateProgress by mutableStateOf<Float?>(null)
    suspend fun findUpdate(): Update? = runCatching { checkForUpdate() }.getOrNull().also { update = it }
    fun installUpdate() {
        val u = update ?: return
        scope.launch {
            updateProgress = 0f
            runCatching { installUpdate(u) { p -> scope.launch { updateProgress = p } } }
                .onSuccess { say(tr("Installer opened: ") + it.name) }.onFailure { say(tr("Update failed: ") + it.message) }
            updateProgress = null
        }
    }

    private var queueSaveJob: Job? = null

    private fun scheduleQueueSave(delayMs: Long) {
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            saveQueueNow()
        }
    }

    fun saveQueueNow() {
        val snap = player.snapshotQueueState()
        if (snap != null) queuePersistence.save(snap)
    }

    init {
        downloads.offlineMode = offlineMode
        downloads.setCacheLimitBytes(cacheLimitGb.toLong() * 1_073_741_824L)
        Thread({ startMediaKeys(this) }, "media-keys").apply { isDaemon = true }.start()
        scope.launch { findUpdate()?.let { say(tr("Square-Yuma PC ") + it.version + tr(" is available — Settings → Updates")) } }
        dev.lelonio.square.ui.theme.DesktopSystemFont.value = systemFont
        Strings.current = language
        player.applyDsp(fx, eqGains)
        useAutomix()

        // Restore persisted queue paused at saved position (both Local & Subsonic / offline-only).
        queuePersistence.load()?.let { saved ->
            player.restoreQueueState(saved) { item ->
                when (item.backend) {
                    dev.lelonio.square.backend.BackendId.LOCAL ->
                        local.findById(item.trackId)?.toDesktopSong() ?: item.toDesktopSong()
                    else -> {
                        val cached = downloads.fileFor(item.trackId)?.absolutePath
                        item.toDesktopSong(cached)
                    }
                }
            }
        }
        player.onQueueStateChanged = { scheduleQueueSave(250L) }
        player.onPositionChanged = { scheduleQueueSave(1500L) }

        Runtime.getRuntime().addShutdownHook(Thread {
            saveQueueNow()
            listening.save()
            local.close()
            loudness.shutdown()
        })

        // Initial incremental scan of configured local folders.
        if (local.folders.value.isNotEmpty()) {
            scope.launch { local.scan() }
        }

        // Analyse what plays and what comes next, so the plan is ready by the last 45 s.
        scope.launch {
            kotlinx.coroutines.flow.combine(player.current, player.queue) { s, q -> s to q }.collect { (s, q) ->
                if (!smartAutomix || s == null) return@collect
                analyses.request(s)
                q.getOrNull(q.indexOf(s) + 1)?.let { analyses.request(it) }
            }
        }
        player.trimSilence = trimSilence
        player.skipFade = skipFade
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

    fun cover(id: String?, size: Int = 300): String? = when {
        id.isNullOrBlank() -> null
        id.startsWith(LocalCatalog.LOCAL_ART_PREFIX) || id.startsWith("file:") ||
            id.startsWith("http://") || id.startsWith("https://") -> id
        else -> client.buildCoverArtUrl(id, size)
    }

    var renderer by mutableStateOf(runCatching { Renderer.valueOf(ui.get("renderer", "Auto")) }.getOrDefault(Renderer.Auto))
        private set
    fun chooseRenderer(r: Renderer) { renderer = r; ui.put("renderer", r.name) }
    fun setGlass(p: GlassProfile) { glassProfile = p; ui.put("glass", p.name) }
    fun setTheme(mode: ThemeMode) { themeMode = mode; ui.put("theme", mode.name) }
    fun setCrossfade(seconds: Int) { player.crossfadeSeconds = seconds; ui.putInt("crossfade", seconds) }
    fun setVolume(v: Float) { player.setVolumeLevel(v); ui.putFloat("volume", player.volume.value) }

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
    if ("--self-check" in args) {
        // Also written to a file: the Windows launcher has no console to print to.
        val report = File(System.getProperty("user.home"), ".square-yuma/self-check.txt").apply { parentFile.mkdirs() }
        val result = runCatching {
            selfCheck()
            versionCheck()
            automixCheck()
            nativeCheck()
            val nativeReport = org.json.JSONObject(dev.lelonio.square.desktop.nativeaudio.NativeEngine.selfCheckRender())
            check(nativeReport.optBoolean("ok")) { nativeReport.optString("summary", "native self-check failed") }
            nativeReport
        }
        val text = result.fold(
            { nr -> "self-check ok: ${nr.optString("summary")} (duration=${nr.optDouble("durationSec")}s lufs=${"%.1f".format(nr.optDouble("lufs"))})\n" },
            { "self-check failed: $it\n" },
        )
        report.writeText(text)
        print(text)
        kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
    }
    LogTail.install()
    runCatching { Renderer.valueOf(uiPrefs.get("renderer", "Auto")) }.getOrNull()?.api?.let { System.setProperty("skiko.renderApi", it) }
    app()
}

private fun app() = application {
    val state = androidx.compose.runtime.remember { AppState() }
    Window(
        onCloseRequest = {
            state.saveQueueNow()
            state.listening.save()
            state.local.close()
            state.loudness.shutdown()
            exitApplication()
        },
        title = "Square-Yuma",
        icon = painterResource("square.png"),
        state = rememberWindowState(size = DpSize(1280.dp, 840.dp)),
        onKeyEvent = state::onKey,
    ) {
        window.minimumSize = Dimension(940, 620)
        App(state)
    }
}
