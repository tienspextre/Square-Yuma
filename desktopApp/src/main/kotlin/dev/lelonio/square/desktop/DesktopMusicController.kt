package dev.lelonio.square.desktop

import dev.lelonio.square.backend.local.DesktopLocalScanner
import dev.lelonio.square.backend.lyrics.DesktopLyricsRepository
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.backend.subsonic.SubsonicClient
import dev.lelonio.square.backend.subsonic.SubsonicPreferences
import dev.lelonio.square.backend.subsonic.SubsonicServerInfo
import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.playback.DesktopDualDeckPlayer
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class DesktopMusicSource(val title: String, val badge: String) {
    SUBSONIC_NAS("NAS Subsonic", "Hi-Res FLAC"),
    LOCAL_FILES("Local Lossless", "Direct Disk"),
}

enum class DesktopNavTab(val label: String) {
    HOME("Home"),
    EXPLORE("Albums & Shelves"),
    LIBRARY("Library & Playlists"),
    AUTOMIX_LAB("Automix & DSP Studio"),
    SETTINGS("Settings & Glass"),
}

enum class DesktopLayoutMode {
    STUDIO_3PANE,
    IMMERSION_STAGE,
    MINI_CUBE,
}

enum class ImmersionRightTab(val label: String, val shortTag: String) {
    LYRICS("Lyrics", "Karaoke"),
    QUEUE("Queue", "Automix"),
    DSP("Audio DSP", "Studio"),
    INSPECT("Lossless Info", "FLAC"),
}

class DesktopMusicController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val subsonicPrefs = SubsonicPreferences()
    val subsonicClient = SubsonicClient(httpClient, subsonicPrefs)

    val player = DesktopDualDeckPlayer(
        scope = scope,
        streamUrlResolver = ::resolvePlaybackSource,
    )

    private val _activeSource = MutableStateFlow(DesktopMusicSource.SUBSONIC_NAS)
    val activeSource: StateFlow<DesktopMusicSource> = _activeSource.asStateFlow()

    private val _activeNavTab = MutableStateFlow(DesktopNavTab.HOME)
    val activeNavTab: StateFlow<DesktopNavTab> = _activeNavTab.asStateFlow()

    private val _layoutMode = MutableStateFlow(DesktopLayoutMode.STUDIO_3PANE)
    val layoutMode: StateFlow<DesktopLayoutMode> = _layoutMode.asStateFlow()

    private val _immersionTab = MutableStateFlow(ImmersionRightTab.LYRICS)
    val immersionTab: StateFlow<ImmersionRightTab> = _immersionTab.asStateFlow()

    private val _showRightSidecar = MutableStateFlow(true)
    val showRightSidecar: StateFlow<Boolean> = _showRightSidecar.asStateFlow()

    private val _glassConfig = MutableStateFlow(
        GlassEffectConfig(
            globalEnabled = true,
            vibrancy = 1.35f,
            blurRadius = 14f,
            lensHeight = 0.45f,
            lensAmount = 0.68f,
            chromaticAberration = true,
            depthEffect = true,
            surfaceOpacity = 0.38f,
            style = GlassStyle.LIQUID,
        )
    )
    val glassConfig: StateFlow<GlassEffectConfig> = _glassConfig.asStateFlow()

    private val _serverStatus = MutableStateFlow<SubsonicServerInfo?>(null)
    val serverStatus: StateFlow<SubsonicServerInfo?> = _serverStatus.asStateFlow()

    private val _connectionMessage = MutableStateFlow("Connecting to NAS Subsonic…")
    val connectionMessage: StateFlow<String> = _connectionMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playlists = MutableStateFlow<List<CatalogPlaylist>>(emptyList())
    val playlists: StateFlow<List<CatalogPlaylist>> = _playlists.asStateFlow()

    private val _albums = MutableStateFlow<List<CatalogPlaylist>>(emptyList())
    val albums: StateFlow<List<CatalogPlaylist>> = _albums.asStateFlow()

    private val _featuredTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val featuredTracks: StateFlow<List<CatalogTrack>> = _featuredTracks.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<CatalogPlaylist?>(null)
    val selectedPlaylist: StateFlow<CatalogPlaylist?> = _selectedPlaylist.asStateFlow()

    private val _selectedPlaylistTracks = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val selectedPlaylistTracks: StateFlow<List<CatalogTrack>> = _selectedPlaylistTracks.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val searchResults: StateFlow<List<CatalogTrack>> = _searchResults.asStateFlow()

    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading.asStateFlow()

    private var localPlaylistMap: Map<String, List<CatalogTrack>> = emptyMap()
    private var localAllTracks: List<CatalogTrack> = emptyList()

    init {
        // Restore shuffle & repeat preferences
        if (subsonicPrefs.shuffleEnabled.value) {
            player.setShuffleEnabled(true)
        }
        val restoredRepeat = runCatching { dev.lelonio.square.playback.DesktopRepeatMode.valueOf(subsonicPrefs.repeatMode.value) }.getOrDefault(dev.lelonio.square.playback.DesktopRepeatMode.OFF)
        player.setRepeatMode(restoredRepeat)

        scope.launch {
            player.shuffleEnabled.collect {
                subsonicPrefs.setShuffleEnabled(it)
            }
        }
        scope.launch {
            player.repeatMode.collect {
                subsonicPrefs.setRepeatMode(it.name)
            }
        }

        refreshLibrary()

        // Automatically fetch word-synced / LRC lyrics whenever currentTrack changes
        scope.launch {
            player.currentTrack.collectLatest { track ->
                if (track == null) {
                    _currentLyrics.value = null
                    _isLyricsLoading.value = false
                    return@collectLatest
                }
                _isLyricsLoading.value = true
                val fetched = DesktopLyricsRepository.fetchLyrics(
                    trackUri = track.uri,
                    title = track.name,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    localFilePath = track.localFile,
                )
                _currentLyrics.value = fetched
                _isLyricsLoading.value = false
            }
        }
    }

    fun setSource(source: DesktopMusicSource) {
        if (_activeSource.value == source) return
        _activeSource.value = source
        refreshLibrary()
    }

    fun setNavTab(tab: DesktopNavTab) {
        _activeNavTab.value = tab
    }

    fun setLayoutMode(mode: DesktopLayoutMode) {
        _layoutMode.value = mode
    }

    fun toggleImmersionMode() {
        _layoutMode.value = when (_layoutMode.value) {
            DesktopLayoutMode.IMMERSION_STAGE -> DesktopLayoutMode.STUDIO_3PANE
            else -> DesktopLayoutMode.IMMERSION_STAGE
        }
    }

    fun toggleMiniCubeMode() {
        _layoutMode.value = when (_layoutMode.value) {
            DesktopLayoutMode.MINI_CUBE -> DesktopLayoutMode.STUDIO_3PANE
            else -> DesktopLayoutMode.MINI_CUBE
        }
    }

    fun setImmersionTab(tab: ImmersionRightTab) {
        _immersionTab.value = tab
    }

    fun toggleRightSidecar() {
        _showRightSidecar.value = !_showRightSidecar.value
    }

    fun updateGlassConfig(transform: (GlassEffectConfig) -> GlassEffectConfig) {
        _glassConfig.value = transform(_glassConfig.value)
    }

    fun refreshLibrary() {
        scope.launch {
            _isLoading.value = true
            when (_activeSource.value) {
                DesktopMusicSource.SUBSONIC_NAS -> loadSubsonicLibrary()
                DesktopMusicSource.LOCAL_FILES -> loadLocalLibrary()
            }
            _isLoading.value = false
        }
    }

    private suspend fun loadSubsonicLibrary() {
        val pingResult = subsonicClient.ping()
        pingResult.onSuccess { info ->
            _serverStatus.value = info
            _connectionMessage.value = "Connected • ${info.type.replaceFirstChar { it.uppercase() }} ${info.serverVersion}"
        }.onFailure { err ->
            _serverStatus.value = null
            _connectionMessage.value = "Offline / Unreachable: ${err.message ?: "Check URL & credentials"}"
        }

        val rawPlaylists = subsonicClient.getPlaylists().getOrDefault(emptyList())
        val mappedPlaylists = rawPlaylists.map { pl ->
            CatalogPlaylist(
                uri = "subsonic:playlist:${pl.id}",
                name = pl.name,
                artworkUrl = subsonicClient.buildCoverArtUrl(pl.coverArt ?: pl.id, 600),
                subtitle = "${pl.songCount} tracks • ${pl.owner ?: "NAS"}",
                songCount = pl.songCount,
            )
        }
        _playlists.value = mappedPlaylists

        val rawAlbums = subsonicClient.getAlbumList(type = "newest", size = 60).getOrDefault(emptyList())
        val mappedAlbums = rawAlbums.map { al ->
            CatalogPlaylist(
                uri = "subsonic:album:${al.id}",
                name = al.name.ifBlank { al.title ?: "Unknown Album" },
                artworkUrl = subsonicClient.buildCoverArtUrl(al.coverArt ?: al.id, 600),
                subtitle = "${al.artist ?: "Unknown Artist"} • ${al.songCount} tracks",
                songCount = al.songCount,
            )
        }
        _albums.value = mappedAlbums

        // Fetch featured tracks from randomSongs + first playlist if available
        val randomSongs = subsonicClient.getRandomSongs(40).getOrDefault(emptyList())
        val tracks = if (randomSongs.isNotEmpty()) {
            randomSongs.map(::mapSubsonicSong)
        } else if (rawPlaylists.isNotEmpty()) {
            subsonicClient.getPlaylist(rawPlaylists.first().id).getOrDefault(emptyList()).map(::mapSubsonicSong)
        } else if (rawAlbums.isNotEmpty()) {
            subsonicClient.getAlbum(rawAlbums.first().id).getOrDefault(emptyList()).map(::mapSubsonicSong)
        } else {
            emptyList()
        }
        _featuredTracks.value = tracks

        val firstSelection = mappedPlaylists.firstOrNull() ?: mappedAlbums.firstOrNull()
        if (firstSelection != null) {
            selectPlaylistOrAlbum(firstSelection)
        } else {
            _selectedPlaylist.value = null
            _selectedPlaylistTracks.value = tracks
            player.cueTrackList(tracks, 0)
        }
    }

    private suspend fun loadLocalLibrary() {
        val folder = subsonicPrefs.localMusicFolder.value
        val scan = DesktopLocalScanner.scanFolder(folder)
        localAllTracks = scan.tracks
        localPlaylistMap = scan.playlistTracks
        _playlists.value = scan.playlists.filter { it.uri.startsWith("local:playlist:") }
        _albums.value = scan.playlists.filter { it.uri.startsWith("local:album:") }
        _featuredTracks.value = scan.tracks
        _connectionMessage.value = "Scanned ${scan.tracks.size} lossless files in $folder"

        val first = scan.playlists.firstOrNull()
        if (first != null) {
            selectPlaylistOrAlbum(first)
        } else {
            _selectedPlaylist.value = null
            _selectedPlaylistTracks.value = scan.tracks
            player.cueTrackList(scan.tracks, 0)
        }
    }

    fun selectPlaylistOrAlbum(playlist: CatalogPlaylist) {
        _selectedPlaylist.value = playlist
        scope.launch {
            val uri = playlist.uri
            val tracks = when {
                uri.startsWith("subsonic:playlist:") -> {
                    val id = uri.removePrefix("subsonic:playlist:")
                    subsonicClient.getPlaylist(id).getOrDefault(emptyList()).map(::mapSubsonicSong)
                }
                uri.startsWith("subsonic:album:") -> {
                    val id = uri.removePrefix("subsonic:album:")
                    subsonicClient.getAlbum(id).getOrDefault(emptyList()).map(::mapSubsonicSong)
                }
                else -> {
                    localPlaylistMap[uri] ?: localAllTracks
                }
            }
            _selectedPlaylistTracks.value = tracks
            player.cueTrackList(tracks, 0)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        scope.launch {
            val q = query.trim()
            val localMatches = (_featuredTracks.value + _selectedPlaylistTracks.value)
                .distinctBy { it.uri }
                .filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                }
            val subsonicMatches = if (_activeSource.value == DesktopMusicSource.SUBSONIC_NAS) {
                subsonicClient.search(q)
                    .getOrNull()
                    ?.song
                    ?.map(::mapSubsonicSong)
                    .orEmpty()
            } else {
                emptyList()
            }
            _searchResults.value = (subsonicMatches + localMatches).distinctBy { it.uri }
        }
    }

    fun resolvePlaybackSource(track: CatalogTrack): String {
        track.localFile?.takeIf { it.isNotBlank() }?.let { return it }
        if (track.uri.startsWith("subsonic:track:")) {
            val id = track.uri.removePrefix("subsonic:track:")
            return subsonicClient.buildStreamUrl(id)
        }
        return track.uri
    }

    fun resolveInspectionSource(track: CatalogTrack?): String? {
        if (track == null) return null
        track.localFile?.takeIf { it.isNotBlank() }?.let { return it }
        if (track.uri.startsWith("subsonic:track:")) {
            val id = track.uri.removePrefix("subsonic:track:")
            return subsonicClient.buildDownloadUrl(id).ifBlank { subsonicClient.buildStreamUrl(id) }
        }
        return null
    }

    private fun mapSubsonicSong(song: SubsonicChildSong): CatalogTrack {
        val artistName = song.artist ?: "Unknown Artist"
        val coverId = song.coverArt ?: song.albumId ?: song.id
        val localCandidate = song.path?.takeIf { it.isNotBlank() }?.let { rel ->
            val cleanRel = rel.removePrefix("/music/").removePrefix("/")
            val f = java.io.File(subsonicPrefs.localMusicFolder.value, cleanRel)
            if (f.exists() && f.isFile) f.absolutePath else null
        }
        return CatalogTrack(
            uri = "subsonic:track:${song.id}",
            name = song.title,
            artist = artistName,
            artistUri = song.artistId?.let { "subsonic:artist:$it" },
            artists = listOf(CatalogArtist(artistName, song.artistId?.let { "subsonic:artist:$it" })),
            album = song.album ?: "",
            albumUri = song.albumId?.let { "subsonic:album:$it" },
            year = song.year?.toString() ?: "",
            durationMs = song.duration * 1000L,
            artworkUrl = subsonicClient.buildCoverArtUrl(coverId, 600),
            localFile = localCandidate,
            suffix = song.suffix ?: "flac",
            bitRateKbps = song.bitRate ?: 0,
            sampleRateHz = song.samplingRate ?: 44100,
            bitDepth = song.bitDepth ?: 16,
        )
    }
}
