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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.subsonic.SubsonicCatalog
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.backend.subsonic.SubsonicClient
import dev.lelonio.square.backend.subsonic.SubsonicPreferences
import dev.lelonio.square.desktop.ui.components.Http
import dev.lelonio.square.desktop.ui.components.loadArtworkBytes
import dev.lelonio.square.desktop.ui.lyrics.LyricSource
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
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

enum class ThemeMode { DARK, LIGHT, SYSTEM }

enum class NavMove { Push, Pop, Root }

enum class InspectorTab { LYRICS, QUEUE, AUDIO }

enum class LibraryTab { SONGS, ALBUMS, ARTISTS, PLAYLISTS }

/** Glass profiles matching Android GlassStore semantics. */
enum class GlassProfile {
    Performance, Balanced, Quality;

    fun config(): GlassEffectConfig = when (this) {
        Performance -> GlassEffectConfig(style = GlassStyle.TRANSPARENT, surfaceOpacity = 0.55f)
        Balanced -> GlassEffectConfig()
        Quality -> GlassEffectConfig(
            vibrancy = 1.4f,
            blurRadius = 4f,
            lensHeight = 0.5f,
            lensAmount = 0.8f,
            chromaticAberration = true,
        )
    }
}

/**
 * Skiko's drawing backend. Auto is Direct3D on Windows and OpenGL on Linux; a
 * driver that draws the glass wrong can be swapped out here. Read once at start.
 */
enum class Renderer(val label: String, val api: String?) {
    Auto("Auto", null),
    OpenGL("OpenGL", "OPENGL"),
    Software("Software", "SOFTWARE"),
}

internal val uiPrefs: Preferences = Preferences.userRoot().node("square-yuma/ui")

class AppState(
    initEngine: Boolean = true,
    startMediaKeysService: Boolean = true,
    installShutdownHook: Boolean = true,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
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
        initEngine = initEngine,
    ).apply {
        volume.value = ui.getFloat("volume", 0.8f)
        crossfadeSeconds = ui.getInt("crossfade", 6)
        outputMode = ui.get("outputMode", "shared")
        outputDeviceId = ui.get("outputDeviceId", null)
        loudnessResolver = { song, onMeasured ->
            loudness.gainDbFor(song) ?: null.also { loudness.request(song, onMeasured) }
        }
    }

    private val initialScreen: Screen =
        if (prefs.isConfigured() || local.folders.value.isNotEmpty() || downloads.kept.isNotEmpty()) Screen.Home else Screen.Settings
    val history = NavigationHistory(initialScreen)
    var currentScreen by mutableStateOf(initialScreen)
        private set
    var activeRootScreen by mutableStateOf(initialScreen)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    private fun syncHistoryState() {
        currentScreen = history.current
        activeRootScreen = history.activeRootScreen()
        canGoBack = history.canGoBack
        canGoForward = history.canGoForward
    }

    var nowPlaying by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var typing by mutableStateOf(false)
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(ui.get("theme", "DARK")) }.getOrDefault(ThemeMode.DARK))
        private set
    var glassProfile by mutableStateOf(runCatching { GlassProfile.valueOf(ui.get("glass", "Balanced")) }.getOrDefault(GlassProfile.Balanced))
        private set
    var closeToTray by mutableStateOf(ui.getBoolean("closeToTray", true))
        private set
    var trayNoticeShown by mutableStateOf(ui.getBoolean("trayNoticeShown", false))
        private set
    var traySupported by mutableStateOf(false)
    var pendingDropFolders by mutableStateOf<List<Path>>(emptyList())
    var dropZoneHighlight by mutableStateOf<DropZone?>(null)
    var queueDropBounds by mutableStateOf<Rect?>(null)
    var playlistDropBounds by mutableStateOf<Rect?>(null)

    /** 0 = bar, 1 = full NowPlaying sheet. */
    val expand = Animatable(0f)
    var navMove by mutableStateOf(NavMove.Root)
    var miniArt by mutableStateOf<Rect?>(null)
    var bigArt by mutableStateOf<Rect?>(null)
    var showQueue by mutableStateOf(false)

    // Phase 4 adaptive 3-pane shell & inspector state
    var inspectorOpen by mutableStateOf(ui.getBoolean("inspectorOpen", true))
        private set
    var inspectorTab by mutableStateOf(
        runCatching { InspectorTab.valueOf(ui.get("inspectorTab", "LYRICS")) }.getOrDefault(InspectorTab.LYRICS),
    )
        private set
    var sidebarCollapsedOverride by mutableStateOf<Boolean?>(null)
    var libraryTab by mutableStateOf(
        runCatching { LibraryTab.valueOf(ui.get("libraryTab", "ALBUMS")) }.getOrDefault(LibraryTab.ALBUMS),
    )
        private set
    var searchQuery by mutableStateOf("")
    var quickSearchFocusTick by mutableStateOf(0)
        private set

    /** Callback for Phase 5's always-on-top Mini-Player window. */
    var onMiniPlayerToggle: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var onRequestExit: (() -> Unit)? = null
    fun requestMiniPlayerToggle() {
        onMiniPlayerToggle?.invoke()
    }
    fun toggleFullscreen() {
        val next = !fullscreen
        fullscreen = next
        if (next) nowPlaying = true
    }
    fun exitFullscreen() {
        fullscreen = false
    }
    fun useCloseToTray(on: Boolean) {
        closeToTray = on
        ui.putBoolean("closeToTray", on)
    }
    fun markTrayNoticeShown() {
        trayNoticeShown = true
        ui.putBoolean("trayNoticeShown", true)
    }

    fun selectLibraryTab(tab: LibraryTab) {
        libraryTab = tab
        ui.put("libraryTab", tab.name)
    }

    fun openInspector(tab: InspectorTab? = null) {
        if (tab != null) {
            inspectorTab = tab
            ui.put("inspectorTab", tab.name)
        }
        inspectorOpen = true
        ui.putBoolean("inspectorOpen", true)
    }

    fun toggleInspector(tab: InspectorTab? = null) {
        if (tab != null && (!inspectorOpen || inspectorTab != tab)) {
            openInspector(tab)
            return
        }
        inspectorOpen = !inspectorOpen
        ui.putBoolean("inspectorOpen", inspectorOpen)
    }

    fun toggleSidebar(currentlyCollapsed: Boolean) {
        sidebarCollapsedOverride = !currentlyCollapsed
    }

    fun focusQuickSearch() {
        quickSearchFocusTick++
        if (currentScreen != Screen.Search) root(Screen.Search)
    }

    /** Bumped after a server or library edit so screens reload. */
    var generation by mutableStateOf(0)

    var toast by mutableStateOf<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var toastJob: Job? = null
    fun say(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch { delay(3500); if (toast == text) toast = null }
    }
    fun activeChildJobCount(): Int =
        scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 0

    val listening = ListeningStore()

    var listMode by mutableStateOf(ui.getBoolean("listMode", false))
        private set

    fun toggleListMode() {
        listMode = !listMode
        ui.putBoolean("listMode", listMode)
    }

    /** Recent search queries, newest first. */
    val searchHistory = mutableStateListOf<String>().apply {
        addAll(ui.get("searches", "").split('\n').filter { it.isNotBlank() })
    }

    fun rememberSearch(query: String) {
        val q = query.trim().takeIf { it.length > 1 } ?: return
        searchHistory.remove(q)
        searchHistory.add(0, q)
        while (searchHistory.size > 20) searchHistory.removeLast()
        ui.put("searches", searchHistory.joinToString("\n"))
    }

    fun removeSearchHistory(query: String) {
        searchHistory.remove(query)
        ui.put("searches", searchHistory.joinToString("\n"))
    }

    fun clearSearches() {
        searchHistory.clear()
        ui.remove("searches")
    }

    data class Pin(val id: String, val name: String, val cover: String?)

    val pins = mutableStateListOf<Pin>().apply {
        ui.get("pins", "").split('\n')
            .mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { Pin(it[0], it[1], it.getOrNull(2)?.ifBlank { null }) } }
            .let(::addAll)
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

    fun linkFor(s: SubsonicChildSong): String {
        val base = prefs.serverUrl.value.trimEnd('/')
        return if (s.albumId != null) "$base/app/#/album/${s.albumId}/show" else "$base/app/#/song/${s.id}/show"
    }

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

    var eqGains by mutableStateOf(
        ui.get("eq", "").split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == Eq.BANDS.size }?.toFloatArray()
            ?: FloatArray(Eq.BANDS.size),
    )
        private set

    fun setEq(gains: FloatArray, @Suppress("UNUSED_PARAMETER") restart: Boolean = true) {
        eqGains = gains
        ui.put("eq", gains.joinToString(","))
        player.applyDsp(fx, gains)
    }

    var fx by mutableStateOf(
        Fx(ui.getFloat("speed", 1f), ui.getFloat("pitch", 1f), ui.getFloat("reverb", 0f), ui.getFloat("karaoke", 0f), ui.getBoolean("loudness", false)),
    )
        private set

    fun setFx(new: Fx, @Suppress("UNUSED_PARAMETER") restart: Boolean = true) {
        fx = new
        ui.putFloat("speed", new.speed)
        ui.putFloat("pitch", new.pitch)
        ui.putFloat("reverb", new.reverb)
        ui.putFloat("karaoke", new.karaoke)
        ui.putBoolean("loudness", new.loudness)
        player.applyDsp(new, eqGains)
    }

    val analyses = Analyses(::sourceFor, local::durationMsOf)
    var smartAutomix by mutableStateOf(ui.getBoolean("smartAutomix", true))
        private set
    var bassSwap by mutableStateOf(ui.getBoolean("bassSwap", true))
        private set
    var loopMix by mutableStateOf(ui.getBoolean("loopMix", false))
        private set

    fun useAutomix(smart: Boolean = smartAutomix, bass: Boolean = bassSwap, loop: Boolean = loopMix) {
        smartAutomix = smart
        bassSwap = bass
        loopMix = loop
        ui.putBoolean("smartAutomix", smart)
        ui.putBoolean("bassSwap", bass)
        ui.putBoolean("loopMix", loop)
        player.planner = if (!smart) null else { out, inc ->
            analyses.plan(out, inc, player.queue.value, player.shuffle.value, maxOf(player.crossfadeSeconds * 1000L, 3_000L), bassSwap, loopMix)
        }
    }

    var trimSilence by mutableStateOf(ui.getBoolean("trimSilence", true))
        private set

    fun useTrimSilence(on: Boolean) {
        trimSilence = on; ui.putBoolean("trimSilence", on); player.trimSilence = on
    }

    var skipFade by mutableStateOf(ui.getBoolean("skipFade", true))
        private set

    fun useSkipFade(on: Boolean) {
        skipFade = on; ui.putBoolean("skipFade", on); player.skipFade = on
    }

    var autoplayAtEnd by mutableStateOf(ui.getBoolean("autoplay", false))
        private set

    fun useAutoplay(on: Boolean) {
        autoplayAtEnd = on
        ui.putBoolean("autoplay", on)
        player.autoplayAtEnd = on
    }

    var language by mutableStateOf(Language.fromCode(ui.get("lang", Locale.getDefault().language)))
        private set

    fun chooseLanguage(l: Language) {
        language = l
        ui.put("lang", l.code)
        Strings.current = l
    }

    var systemFont by mutableStateOf(ui.getBoolean("systemFont", false))
        private set

    fun useSystemFont(on: Boolean) {
        systemFont = on
        ui.putBoolean("systemFont", on)
        dev.lelonio.square.ui.theme.DesktopSystemFont.value = on
    }

    private val picks = Preferences.userRoot().node("square-yuma/lyrics-pick")
    fun lyricsPick(songId: String): LyricSource? = picks.get(songId, null)?.let { runCatching { LyricSource.valueOf(it) }.getOrNull() }
    fun setLyricsPick(songId: String, source: LyricSource?) {
        if (source == null) picks.remove(songId) else picks.put(songId, source.name)
    }

    var classicLyrics by mutableStateOf(ui.getBoolean("classicLyrics", false))
        private set

    fun useClassicLyrics(on: Boolean) {
        classicLyrics = on; ui.putBoolean("classicLyrics", on)
    }

    var translateLyrics by mutableStateOf(ui.getBoolean("translateLyrics", false))
        private set

    fun useTranslation(on: Boolean) {
        translateLyrics = on; ui.putBoolean("translateLyrics", on)
    }

    var translateTo by mutableStateOf(ui.get("translateTo", "").ifBlank { language.code })
        private set

    fun chooseTranslateTarget(code: String) {
        translateTo = code
        ui.put("translateTo", code)
    }

    var lyricsOffsetMs by mutableStateOf(ui.getLong("lyricsOffset", 0L))
        private set

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetMs = ms; ui.putLong("lyricsOffset", ms)
    }

    var betaChannel by mutableStateOf(ui.getBoolean("betaChannel", false))
        private set

    fun useBetaChannel(on: Boolean) {
        betaChannel = on
        ui.putBoolean("betaChannel", on)
    }

    var update by mutableStateOf<Update?>(null)
    var updateNoticeVisible by mutableStateOf(false)
    var updateChecking by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateDownloading by mutableStateOf(false)
    var updateError by mutableStateOf<String?>(null)
    var lastReportPath by mutableStateOf<String?>(null)

    suspend fun findUpdate(force: Boolean = false): Update? {
        val now = System.currentTimeMillis()
        if (!force && !ReleaseSelector.shouldAutoCheck(ui.getLong("lastUpdateCheckMs", 0L), now)) {
            return null
        }
        ui.putLong("lastUpdateCheckMs", now)
        updateChecking = true
        updateError = null
        return try {
            val found = checkForUpdate(includeBeta = betaChannel)
            update = found
            if (found != null) {
                val skipped = ui.get("skippedUpdateVersion", null)
                if (force || found.version != skipped) {
                    updateNoticeVisible = true
                }
            }
            found
        } catch (t: Throwable) {
            if (force) updateError = t.message ?: "Network error"
            null
        } finally {
            updateChecking = false
        }
    }

    fun dismissUpdateNotice() {
        updateNoticeVisible = false
        update?.version?.let { ui.put("skippedUpdateVersion", it) }
    }

    fun applyUpdate() {
        val u = update ?: return
        if (!u.canInstallInApp) {
            runCatching { openReleasePage(u.page) }
                .onFailure { say(str("desktop_update_failed", it.message.orEmpty())) }
            updateNoticeVisible = false
            return
        }
        if (updateDownloading) return
        scope.launch {
            updateDownloading = true
            updateProgress = 0f
            updateError = null
            runCatching {
                val file = downloadAndVerifyUpdate(u) { p ->
                    scope.launch { updateProgress = p }
                }
                launchWindowsInstallerAndExit(file) {
                    onRequestExit?.invoke() ?: run {
                        shutdown()
                        kotlin.system.exitProcess(0)
                    }
                }
            }.onFailure { t ->
                val msg = t.message.orEmpty().ifBlank { "Error" }
                updateError = msg
                say(str("desktop_update_failed", msg))
            }
            updateDownloading = false
            updateProgress = null
        }
    }

    fun startRadio(seed: SubsonicChildSong) {
        scope.launch(Dispatchers.IO) {
            val similar = similarTracksFor(seed, 24)
            if (similar.isEmpty()) {
                launch(Dispatchers.Main) { say(str("radio_empty")) }
                return@launch
            }
            val radioQueue = listOf(seed) + similar.filter { it.id != seed.id }.distinctBy { it.id }
            launch(Dispatchers.Main) {
                player.playQueue(radioQueue, start = 0)
                say(str("radio_of", seed.title))
            }
        }
    }

    private suspend fun similarTracksFor(seed: SubsonicChildSong, count: Int): List<SubsonicChildSong> {
        val isLocal = seed.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)
        if (!isLocal && !offlineMode && prefs.isConfigured()) {
            val sim = client.getSimilarSongs(seed.id, count).getOrNull().orEmpty()
            if (sim.isNotEmpty()) return sim
            val rand = client.getRandomSongs(count).getOrNull().orEmpty()
            if (rand.isNotEmpty()) return rand
        }
        val localPool = local.desktopSongs()
        val cachedPool = downloads.cachedSongs()
        return localSimilarSongs(seed, (localPool + cachedPool).distinctBy { it.id }, count)
    }

    fun setAudioOutput(mode: String, deviceId: String? = null): Boolean {
        val ok = player.setOutput(mode, deviceId)
        ui.put("outputMode", mode)
        if (deviceId == null) ui.remove("outputDeviceId") else ui.put("outputDeviceId", deviceId)
        player.signalChain.value = AudioSignalChain.fromJson(player.statusJson())
        return ok
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
        if (startMediaKeysService) {
            Thread({ startMediaKeys(this) }, "media-keys").apply { isDaemon = true }.start()
            scope.launch { findUpdate(force = false) }
        }
        dev.lelonio.square.ui.theme.DesktopSystemFont.value = systemFont
        Strings.current = language
        if (player.outputMode != "shared" || player.outputDeviceId != null) {
            player.setOutput(player.outputMode, player.outputDeviceId)
        }
        player.applyDsp(fx, eqGains)
        useAutomix()
        player.autoplayAtEnd = autoplayAtEnd
        player.onAutoplayNeeded = { last -> similarTracksFor(last, 15) }

        queuePersistence.load()?.let { saved ->
            player.restoreQueueState(saved) { item ->
                when (item.backend) {
                    BackendId.LOCAL -> local.findById(item.trackId)?.toDesktopSong() ?: item.toDesktopSong()
                    else -> item.toDesktopSong(downloads.fileFor(item.trackId)?.absolutePath)
                }
            }
        }
        player.onQueueStateChanged = { scheduleQueueSave(250L) }
        player.onPositionChanged = { scheduleQueueSave(1500L) }

        if (installShutdownHook) {
            Runtime.getRuntime().addShutdownHook(Thread {
                saveQueueNow()
                listening.save()
                local.close()
                loudness.shutdown()
            })
        }

        if (local.folders.value.isNotEmpty()) {
            scope.launch { local.scan() }
        }

        scope.launch {
            combine(player.current, player.queue) { s, q -> s to q }.collect { (s, q) ->
                if (!smartAutomix || s == null) return@collect
                analyses.request(s)
                q.getOrNull(q.indexOf(s) + 1)?.let { analyses.request(it) }
            }
        }
        player.trimSilence = trimSilence
        player.skipFade = skipFade
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

    fun open(screen: Screen) {
        navMove = NavMove.Push
        if (history.push(screen)) syncHistoryState()
        nowPlaying = false
    }

    fun root(screen: Screen) {
        navMove = NavMove.Root
        if (history.push(screen)) syncHistoryState()
        nowPlaying = false
    }

    fun back() {
        if (fullscreen) {
            fullscreen = false
            return
        }
        if (nowPlaying) {
            nowPlaying = false
            return
        }
        if (history.back() != null) {
            navMove = NavMove.Pop
            syncHistoryState()
        }
    }

    fun forward() {
        if (history.forward() != null) {
            navMove = NavMove.Push
            syncHistoryState()
            nowPlaying = false
        }
    }

    fun cover(id: String?, size: Int = 300): String? = when {
        id.isNullOrBlank() -> null
        id.startsWith(LocalCatalog.LOCAL_ART_PREFIX) || id.startsWith("file:") ||
            id.startsWith("http://") || id.startsWith("https://") -> id
        else -> client.buildCoverArtUrl(id, size)
    }

    var renderer by mutableStateOf(runCatching { Renderer.valueOf(ui.get("renderer", "Auto")) }.getOrDefault(Renderer.Auto))
        private set

    fun chooseRenderer(r: Renderer) {
        renderer = r; ui.put("renderer", r.name)
    }

    fun setGlass(p: GlassProfile) {
        glassProfile = p; ui.put("glass", p.name)
    }

    fun setTheme(mode: ThemeMode) {
        themeMode = mode; ui.put("theme", mode.name)
    }

    fun setCrossfade(seconds: Int) {
        player.crossfadeSeconds = seconds; ui.putInt("crossfade", seconds)
    }

    fun setVolume(v: Float) {
        player.setVolumeLevel(v); ui.putFloat("volume", player.volume.value)
    }

    fun shutdown() {
        queueSaveJob?.cancel()
        toastJob?.cancel()
        saveQueueNow()
        listening.save()
        local.close()
        loudness.shutdown()
        analyses.shutdown()
        player.shutdown()
        scope.cancel()
    }

    fun onKey(e: KeyEvent): Boolean {
        val action = ShortcutDispatch.resolveEvent(e, typing) ?: return false
        return ShortcutDispatch.dispatch(this, action)
    }

    fun onPostKey(e: KeyEvent): Boolean {
        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
            back()
            return true
        }
        return false
    }
}
