package dev.lelonio.square.desktop

import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.HomeFeed
import dev.lelonio.square.backend.HomeRow
import dev.lelonio.square.backend.MusicCatalog
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.backend.lyrics.LrcLib
import dev.lelonio.square.backend.lyrics.LyricsLibrary
import dev.lelonio.square.backend.lyrics.LyricsQuery
import dev.lelonio.square.backend.lyrics.Ttml
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.local.AudioMetadataParser
import dev.lelonio.square.data.local.EmbeddedLyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Desktop Local music backend implementing [MusicCatalog] (`BackendId.LOCAL`).
 * Supports multiple root folders, parallel incremental recursive scan persisted in
 * `~/.square-yuma/local_index.json`, live recursive `WatchService` updates,
 * `.m3u8` playlists, embedded/folder artwork, and embedded/sidecar `.lrc` lyrics.
 */
class LocalCatalog(
    private val dataDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma"),
    val playlistStore: LocalPlaylistStore = LocalPlaylistStore(dataDir.resolve("playlists")),
    private val enableWatcher: Boolean = true,
) : MusicCatalog, Closeable {

    private val foldersFile = dataDir.resolve("local_folders.json")
    private val indexFile = dataDir.resolve("local_index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scanMutex = Mutex()
    private val indexLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val embeddedLyrics = EmbeddedLyricsParser { path ->
        runCatching { File(path).inputStream() }.getOrNull()
    }

    private val _folders = MutableStateFlow<List<Path>>(emptyList())
    val folders: StateFlow<List<Path>> = _folders.asStateFlow()

    private val _tracks = MutableStateFlow<List<IndexedLocalTrack>>(emptyList())
    val tracks: StateFlow<List<IndexedLocalTrack>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val indexByPath = ConcurrentHashMap<String, IndexedLocalTrack>()

    private val watcher: LocalCatalogWatcher? = if (enableWatcher) {
        LocalCatalogWatcher(
            scope = scope,
            foldersProvider = { _folders.value },
            indexByPath = indexByPath,
            parseTrack = ::parseTrack,
            onOverflowRescan = { scan() },
            onBatchChanged = {
                synchronized(indexLock) {
                    publishSortedTracksLocked()
                    saveIndexLocked()
                }
            },
        )
    } else null

    override val id: BackendId = BackendId.LOCAL
    override val authState: StateFlow<BackendAuthState> =
        MutableStateFlow(BackendAuthState.LoggedIn("Local Folders"))
    override val isReady: Boolean = true

    override fun owns(uri: String): Boolean = uri.startsWith("local:") || uri.startsWith("file:")

    init {
        loadPersistedState()
        watcher?.start()
        watcher?.syncWatchers()
    }

    private fun loadPersistedState() {
        runCatching {
            if (Files.isRegularFile(foldersFile)) {
                val raw = Files.readString(foldersFile, StandardCharsets.UTF_8)
                val list = json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), raw)
                _folders.value = list.map { Paths.get(it).toAbsolutePath().normalize() }.distinct()
            }
        }
        runCatching {
            if (Files.isRegularFile(indexFile)) {
                val raw = Files.readString(indexFile, StandardCharsets.UTF_8)
                val list = json.decodeFromString(ListSerializer(IndexedLocalTrack.serializer()), raw)
                synchronized(indexLock) {
                    indexByPath.clear()
                    list.forEach { indexByPath[it.path] = it }
                    publishSortedTracksLocked()
                }
            }
        }
    }

    private fun saveFolders() {
        runCatching {
            Files.createDirectories(dataDir)
            val data = json.encodeToString(
                ListSerializer(kotlinx.serialization.serializer<String>()),
                _folders.value.map { it.toString() },
            )
            LocalCatalogIo.atomicWriteText(foldersFile, data)
        }
    }

    private fun saveIndexLocked() {
        runCatching {
            Files.createDirectories(dataDir)
            val data = json.encodeToString(ListSerializer(IndexedLocalTrack.serializer()), indexByPath.values.toList())
            LocalCatalogIo.atomicWriteText(indexFile, data)
        }
    }

    private fun publishSortedTracksLocked() {
        _tracks.value = indexByPath.values.sortedWith(
            compareBy<IndexedLocalTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.discNumber ?: 0 }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    suspend fun addFolder(folder: Path): Boolean {
        val norm = folder.toAbsolutePath().normalize()
        if (!Files.isDirectory(norm)) return false
        val added = synchronized(indexLock) {
            if (norm in _folders.value) return@synchronized false
            _folders.value = _folders.value + listOf(norm)
            saveFolders()
            true
        }
        if (added) {
            watcher?.registerTree(norm)
            scan()
        }
        return added
    }

    suspend fun removeFolder(folder: Path) {
        val norm = folder.toAbsolutePath().normalize()
        synchronized(indexLock) {
            _folders.value = _folders.value.filterNot { it == norm }
            saveFolders()
            val roots = _folders.value
            indexByPath.keys.toList().forEach { pathStr ->
                val p = Paths.get(pathStr)
                if (roots.none { p.startsWith(it) }) indexByPath.remove(pathStr)
            }
            publishSortedTracksLocked()
            saveIndexLocked()
        }
        watcher?.syncWatchers()
    }

    suspend fun scan(): ScanSummary = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            _scanning.value = true
            try {
                val roots = _folders.value.filter { Files.isDirectory(it) }
                val discovered = mutableListOf<DiscoveredFile>()
                val folderArtCache = ConcurrentHashMap<Path, String?>()

                for (root in roots) {
                    Files.walkFileTree(
                        root,
                        object : SimpleFileVisitor<Path>() {
                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (attrs.isRegularFile && AudioMetadataParser.isSupportedAudioFile(file)) {
                                    discovered += DiscoveredFile(
                                        path = file.toAbsolutePath().normalize(),
                                        size = attrs.size(),
                                        mtimeMs = attrs.lastModifiedTime().toMillis(),
                                    )
                                }
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
                        },
                    )
                }

                val discoveredSet = HashSet<String>(discovered.size)
                val toParse = mutableListOf<DiscoveredFile>()
                var skipped = 0

                for (item in discovered) {
                    val key = item.path.toString()
                    discoveredSet.add(key)
                    val existing = indexByPath[key]
                    if (existing != null && existing.size == item.size && existing.mtimeMs == item.mtimeMs) {
                        val art = LocalCatalogIo.findFolderArtwork(item.path.parent, folderArtCache)
                        if (art != existing.folderArtworkPath) {
                            indexByPath[key] = existing.copy(folderArtworkPath = art)
                        }
                        skipped++
                    } else {
                        toParse += item
                    }
                }

                val pruned = indexByPath.keys.count { it !in discoveredSet }.also {
                    indexByPath.keys.removeIf { k -> k !in discoveredSet }
                }

                if (toParse.isNotEmpty()) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    val pool = Executors.newFixedThreadPool(threads)
                    try {
                        val futures: List<Future<IndexedLocalTrack?>> = toParse.map { item ->
                            pool.submit<IndexedLocalTrack?> {
                                parseTrack(item.path, item.size, item.mtimeMs, folderArtCache)
                            }
                        }
                        for (f in futures) {
                            val parsed = runCatching { f.get() }.onFailure { it.printStackTrace() }.getOrNull() ?: continue
                            indexByPath[parsed.path] = parsed
                        }
                    } finally {
                        pool.shutdown()
                    }
                }

                synchronized(indexLock) {
                    publishSortedTracksLocked()
                    saveIndexLocked()
                }
                watcher?.syncWatchers()

                ScanSummary(
                    total = indexByPath.size,
                    parsed = toParse.size,
                    skippedUnchanged = skipped,
                    pruned = pruned,
                )
            } finally {
                _scanning.value = false
            }
        }
    }

    data class ScanSummary(val total: Int, val parsed: Int, val skippedUnchanged: Int, val pruned: Int)

    private data class DiscoveredFile(val path: Path, val size: Long, val mtimeMs: Long)

    private fun parseTrack(
        path: Path,
        size: Long,
        mtimeMs: Long,
        folderArtCache: ConcurrentHashMap<Path, String?>,
    ): IndexedLocalTrack? {
        val meta = AudioMetadataParser.parse(path, includeArtwork = false)
        val fileNameStem = path.fileName?.toString()?.substringBeforeLast('.') ?: "Unknown"
        val parentFolderName = path.parent?.fileName?.toString() ?: "Unknown Album"
        val folderArt = LocalCatalogIo.findFolderArtwork(path.parent, folderArtCache)

        val title = meta?.title?.takeIf { it.isNotBlank() } ?: fileNameStem
        val artist = meta?.artist?.takeIf { it.isNotBlank() }
            ?: meta?.albumArtist?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"
        val album = meta?.album?.takeIf { it.isNotBlank() } ?: parentFolderName
        val albumArtist = meta?.albumArtist?.takeIf { it.isNotBlank() }.orEmpty()

        return IndexedLocalTrack(
            id = LocalCatalogIo.trackIdForPath(path),
            path = path.toString(),
            size = size,
            mtimeMs = mtimeMs,
            addedAtMs = System.currentTimeMillis(),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = meta?.trackNumber,
            discNumber = meta?.discNumber,
            year = meta?.year.orEmpty(),
            genre = meta?.genre.orEmpty(),
            durationMs = meta?.durationMs ?: 0L,
            sampleRate = meta?.sampleRate ?: 0,
            bitDepth = meta?.bitDepth ?: 0,
            channels = meta?.channels ?: 0,
            bitrateKbps = meta?.bitrateKbps ?: 0,
            hasEmbeddedArtwork = meta?.hasEmbeddedArtwork ?: false,
            folderArtworkPath = folderArt,
        )
    }

    override fun close() {
        watcher?.close()
        scope.cancel()
    }

    fun findById(idOrUri: String): IndexedLocalTrack? {
        if (idOrUri.startsWith(LOCAL_TRACK_PREFIX)) {
            return _tracks.value.firstOrNull { it.id == idOrUri }
        }
        val norm = runCatching { Paths.get(idOrUri).toAbsolutePath().normalize().toString() }.getOrNull()
        return norm?.let { indexByPath[it] }
    }

    fun desktopSongs(): List<SubsonicChildSong> = _tracks.value.map { it.toDesktopSong() }

    fun durationMsOf(song: SubsonicChildSong): Long {
        if (song.id.startsWith(LOCAL_TRACK_PREFIX)) {
            findById(song.id)?.let { if (it.durationMs > 0L) return it.durationMs }
        }
        return song.duration * 1000L
    }

    override suspend fun refreshAuth() {}
    override suspend fun logOut() {}

    override suspend fun homeFeed(cursor: String?, params: String?): HomeFeed {
        val all = _tracks.value
        if (all.isEmpty()) {
            return HomeFeed(rows = listOf(HomeRow(title = "Add a local music folder in Local Folders", strapline = "LOCAL LIBRARY")))
        }
        val rows = mutableListOf<HomeRow>()
        val recent = all.sortedByDescending { it.addedAtMs }.take(30).map { it.toCatalogTrack() }
        if (recent.isNotEmpty()) rows += HomeRow(title = "Recently Added", strapline = "LOCAL", tracks = recent)
        val albums = savedAlbums()
        if (albums.isNotEmpty()) rows += HomeRow(title = "Albums", strapline = "LOCAL", items = albums.take(30))
        val playlists = playlists()
        if (playlists.isNotEmpty()) rows += HomeRow(title = "Local Playlists", strapline = "M3U8", items = playlists)
        return HomeFeed(rows = rows)
    }

    override suspend fun allSongs(): List<CatalogTrack> = _tracks.value.map { it.toCatalogTrack() }

    override suspend fun savedAlbums(): List<CatalogPlaylist> =
        _tracks.value.groupBy { albumKey(it) }
            .map { (key, list) ->
                val first = list.first()
                CatalogPlaylist(
                    uri = LOCAL_ALBUM_PREFIX + key,
                    name = first.album,
                    artworkUrl = list.firstNotNullOfOrNull { it.artworkUrl },
                    subtitle = "${list.size} tracks • ${first.albumArtist.ifBlank { first.artist }}",
                    mine = false,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    override suspend fun followedArtists(): List<SearchItem> =
        _tracks.value.groupBy { it.artist }
            .map { (name, list) ->
                SearchItem(
                    uri = LOCAL_ARTIST_PREFIX + name,
                    title = name,
                    subtitle = "${list.size} tracks",
                    artworkUrl = list.firstNotNullOfOrNull { it.artworkUrl },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    override suspend fun playlists(): List<CatalogPlaylist> =
        playlistStore.list().map { pl ->
            val firstArt = pl.entries.firstNotNullOfOrNull { e -> indexByPath[e.path]?.artworkUrl }
            CatalogPlaylist(
                uri = LOCAL_PLAYLIST_PREFIX + pl.id,
                name = pl.name,
                artworkUrl = firstArt,
                subtitle = "${pl.entries.size} songs • .m3u8",
                mine = true,
            )
        }

    override suspend fun tracksOf(uri: String): List<CatalogTrack> = when {
        uri == LOCAL_ALL_URI -> allSongs()
        uri.startsWith(LOCAL_ALBUM_PREFIX) -> {
            val key = uri.removePrefix(LOCAL_ALBUM_PREFIX)
            _tracks.value.filter { albumKey(it) == key }.map { it.toCatalogTrack() }
        }
        uri.startsWith(LOCAL_ARTIST_PREFIX) -> {
            val artist = uri.removePrefix(LOCAL_ARTIST_PREFIX)
            _tracks.value.filter { it.artist.equals(artist, ignoreCase = true) }.map { it.toCatalogTrack() }
        }
        uri.startsWith(LOCAL_PLAYLIST_PREFIX) -> {
            val pl = playlistStore.read(uri.removePrefix(LOCAL_PLAYLIST_PREFIX)) ?: return emptyList()
            pl.entries.map { entry ->
                indexByPath[entry.path]?.toCatalogTrack() ?: CatalogTrack(
                    uri = LocalCatalogIo.trackIdForPath(Paths.get(entry.path)),
                    name = entry.title ?: Paths.get(entry.path).fileName?.toString()?.substringBeforeLast('.') ?: "Unknown",
                    artist = entry.artist ?: "Unknown Artist",
                    durationMs = if (entry.durationSec > 0) entry.durationSec * 1000L else 0L,
                    localFile = entry.path,
                )
            }
        }
        else -> emptyList()
    }

    fun desktopTracksOf(uri: String): List<SubsonicChildSong> = when {
        uri == LOCAL_ALL_URI -> desktopSongs()
        uri.startsWith(LOCAL_ALBUM_PREFIX) -> {
            val key = uri.removePrefix(LOCAL_ALBUM_PREFIX)
            _tracks.value.filter { albumKey(it) == key }.map { it.toDesktopSong() }
        }
        uri.startsWith(LOCAL_ARTIST_PREFIX) -> {
            val artist = uri.removePrefix(LOCAL_ARTIST_PREFIX)
            _tracks.value.filter { it.artist.equals(artist, ignoreCase = true) }.map { it.toDesktopSong() }
        }
        uri.startsWith(LOCAL_PLAYLIST_PREFIX) -> {
            val pl = playlistStore.read(uri.removePrefix(LOCAL_PLAYLIST_PREFIX)) ?: return emptyList()
            pl.entries.map { entry ->
                indexByPath[entry.path]?.toDesktopSong() ?: SubsonicChildSong(
                    id = LocalCatalogIo.trackIdForPath(Paths.get(entry.path)),
                    title = entry.title ?: Paths.get(entry.path).fileName?.toString()?.substringBeforeLast('.') ?: "Unknown",
                    artist = entry.artist,
                    duration = entry.durationSec.coerceAtLeast(0L),
                    path = entry.path,
                )
            }
        }
        else -> emptyList()
    }

    override suspend fun search(query: String, labels: SearchLabels, offset: Int): SearchResults {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return SearchResults()
        val all = _tracks.value
        val matchedTracks = all.filter {
            it.title.lowercase(Locale.ROOT).contains(q) ||
                it.artist.lowercase(Locale.ROOT).contains(q) ||
                it.album.lowercase(Locale.ROOT).contains(q)
        }.map { it.toCatalogTrack() }
        val matchedAlbums = savedAlbums().filter {
            it.name.lowercase(Locale.ROOT).contains(q) || (it.subtitle?.lowercase(Locale.ROOT)?.contains(q) == true)
        }.map { SearchItem(it.uri, it.name, it.subtitle ?: labels.album, it.artworkUrl) }
        val matchedArtists = followedArtists().filter { it.title.lowercase(Locale.ROOT).contains(q) }
        val matchedPlaylists = playlists().filter { it.name.lowercase(Locale.ROOT).contains(q) }
            .map { SearchItem(it.uri, it.name, it.subtitle ?: labels.playlist, it.artworkUrl) }
        return SearchResults(tracks = matchedTracks, albums = matchedAlbums, artists = matchedArtists, playlists = matchedPlaylists)
    }

    override suspend fun lyrics(uri: String, title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val localPath = findById(uri)?.path ?: uri.removePrefix("file://").takeIf { File(it).isFile }
            if (localPath != null) {
                val file = File(localPath)
                val raw = embeddedLyrics.extract(contentUri = localPath, displayName = file.name, filePath = localPath)
                if (!raw.isNullOrBlank()) {
                    val parsed = if (raw.trimStart().startsWith("<?xml") || raw.contains("<tt")) Ttml.parse(raw) else LrcLib.parseLrc(raw)
                    if (parsed != null && parsed.lines.isNotEmpty()) return@withContext parsed.copy(source = "Embedded")
                }
            }
            LyricsLibrary.lyrics(LyricsQuery(uri = uri, title = title, artist = artist, durationMs = durationMs))
        }

    override val canEditPlaylists: Boolean = true
    override fun canWriteTo(playlistUri: String): Boolean = playlistUri.startsWith(LOCAL_PLAYLIST_PREFIX)
    override suspend fun writablePlaylists(): List<CatalogPlaylist> = playlists()

    override suspend fun createPlaylist(name: String, description: String, public: Boolean): CatalogPlaylist {
        val pl = playlistStore.create(name)
        return CatalogPlaylist(uri = LOCAL_PLAYLIST_PREFIX + pl.id, name = pl.name, subtitle = "0 songs • .m3u8", mine = true)
    }

    override suspend fun renamePlaylist(uri: String, name: String) {
        playlistStore.rename(uri.removePrefix(LOCAL_PLAYLIST_PREFIX), name)
    }

    override suspend fun deletePlaylist(uri: String) {
        playlistStore.delete(uri.removePrefix(LOCAL_PLAYLIST_PREFIX))
    }

    override suspend fun addToPlaylist(playlistUri: String, trackUri: String) = addAllToPlaylist(playlistUri, listOf(trackUri))

    override suspend fun addAllToPlaylist(playlistUri: String, trackUris: List<String>) {
        val entries = trackUris.mapNotNull { u ->
            val t = findById(u) ?: return@mapNotNull null
            LocalPlaylistEntry(path = t.path, title = t.title, artist = t.artist, durationSec = t.durationMs / 1000L)
        }
        if (entries.isNotEmpty()) playlistStore.addTracks(playlistUri.removePrefix(LOCAL_PLAYLIST_PREFIX), entries)
    }

    override suspend fun removeFromPlaylist(playlistUri: String, trackUri: String) {
        val t = findById(trackUri)
        if (t != null) playlistStore.removeByPath(playlistUri.removePrefix(LOCAL_PLAYLIST_PREFIX), t.path)
    }

    fun reorderPlaylist(playlistUri: String, fromIndex: Int, toIndex: Int) {
        playlistStore.reorder(playlistUri.removePrefix(LOCAL_PLAYLIST_PREFIX), fromIndex, toIndex)
    }

    companion object {
        const val LOCAL_TRACK_PREFIX = "local:track:"
        const val LOCAL_ALBUM_PREFIX = "local:album:"
        const val LOCAL_ARTIST_PREFIX = "local:artist:"
        const val LOCAL_PLAYLIST_PREFIX = "local:playlist:"
        const val LOCAL_ALL_URI = "local:files"
        const val LOCAL_ART_PREFIX = "localart:"

        fun albumKey(t: IndexedLocalTrack): String = "${t.album}\n${t.albumArtist.ifBlank { t.artist }}"
    }
}
