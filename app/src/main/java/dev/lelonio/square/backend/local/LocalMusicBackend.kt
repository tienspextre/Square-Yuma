package dev.lelonio.square.backend.local

import android.content.Context
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.HomeFeed
import dev.lelonio.square.backend.HomeRow
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.LocalLibrary
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.local.EmbeddedLyricsExtractor
import dev.lelonio.square.data.local.FlacMetadataParser
import dev.lelonio.square.data.local.FlacTrackInfo
import dev.lelonio.square.backend.lyrics.LyricsLibrary
import dev.lelonio.square.backend.lyrics.LyricsQuery
import dev.lelonio.square.backend.lyrics.Ttml
import androidx.media3.common.Player
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.lyrics.LrcLib
import dev.lelonio.square.playback.LocalPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LocalMusicBackend(
    private val context: Context,
) : MusicBackend {

    override val id: BackendId = BackendId.LOCAL

    private val _authState = MutableStateFlow<BackendAuthState>(
        BackendAuthState.LoggedIn(displayName = "Local Lossless Collection"),
    )
    override val authState: StateFlow<BackendAuthState> = _authState.asStateFlow()

    override val isReady: Boolean = true

    private val lyricsExtractor = EmbeddedLyricsExtractor(context.contentResolver)
    private val flacInfoCache = ConcurrentHashMap<String, FlacTrackInfo>()

    override fun owns(uri: String): Boolean =
        uri.startsWith("file://") || uri.startsWith("content://") || uri.startsWith("local:")

    override fun createPlayer(host: PlaybackHost): Player =
        LocalPlayerFactory.create(host)

    override suspend fun refreshAuth() {
        _authState.value = BackendAuthState.LoggedIn(displayName = "Local Lossless Collection")
    }

    fun albumCoverOf(track: CatalogTrack): String? = track.artworkUrl

    override suspend fun logOut() {}

    override suspend fun homeFeed(cursor: String?, params: String?): HomeFeed = withContext(Dispatchers.IO) {
        val tracks = LocalLibrary.tracks(context)
        if (tracks.isEmpty()) {
            return@withContext HomeFeed(
                rows = listOf(
                    HomeRow(
                        title = "No Local Music Found",
                        strapline = "SCANNER",
                        tracks = emptyList(),
                    ),
                ),
            )
        }

        val hiResTracks = tracks.filter { track ->
            val info = getOrParseFlacInfo(track)
            info?.isHiRes == true || track.localFile?.endsWith(".flac", ignoreCase = true) == true
        }

        val albums = tracks.groupBy { it.album.ifBlank { "Unknown Album" } }
            .map { (albumName, albumTracks) ->
                val first = albumTracks.first()
                CatalogPlaylist(
                    uri = "local:album:$albumName",
                    name = albumName,
                    artworkUrl = first.artworkUrl,
                    subtitle = "${albumTracks.size} tracks • ${first.artist.ifBlank { "Various Artists" }}",
                    mine = false,
                )
            }

        val artists = tracks.groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .map { (artistName, artistTracks) ->
                val first = artistTracks.first()
                CatalogPlaylist(
                    uri = "local:artist:$artistName",
                    name = artistName,
                    artworkUrl = first.artworkUrl,
                    subtitle = "${artistTracks.size} tracks",
                    isArtist = true,
                    mine = false,
                )
            }

        val folders = tracks.groupBy {
            val path = it.localFile ?: ""
            File(path).parentFile?.name ?: "Music"
        }.map { (folderName, folderTracks) ->
            val first = folderTracks.first()
            CatalogPlaylist(
                uri = "local:folder:$folderName",
                name = folderName,
                artworkUrl = first.artworkUrl,
                subtitle = "${folderTracks.size} tracks",
                mine = false,
            )
        }

        val rows = mutableListOf<HomeRow>()
        if (hiResTracks.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Hi-Res Lossless Collection",
                    strapline = "AUDIOPHILE",
                    tracks = hiResTracks.take(40),
                ),
            )
        }
        if (albums.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Albums",
                    strapline = "LIBRARY",
                    items = albums.take(40),
                ),
            )
        }
        rows.add(
            HomeRow(
                title = "Recently Added",
                strapline = "OFFLINE",
                tracks = tracks.take(40),
            ),
        )
        if (artists.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Artists",
                    strapline = "COLLECTION",
                    items = artists.take(40),
                ),
            )
        }
        if (folders.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Folders",
                    strapline = "STORAGE",
                    items = folders.take(40),
                ),
            )
        }

        HomeFeed(rows = rows)
    }

    override suspend fun playlists(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        savedAlbums()
    }

    override suspend fun savedAlbums(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val tracks = LocalLibrary.tracks(context)
        tracks.groupBy { it.album.ifBlank { "Unknown Album" } }
            .map { (albumName, albumTracks) ->
                val first = albumTracks.first()
                CatalogPlaylist(
                    uri = "local:album:$albumName",
                    name = albumName,
                    artworkUrl = first.artworkUrl,
                    subtitle = "${albumTracks.size} tracks • ${first.artist.ifBlank { "Various Artists" }}",
                    mine = false,
                )
            }
    }

    override suspend fun followedArtists(): List<SearchItem> = withContext(Dispatchers.IO) {
        val tracks = LocalLibrary.tracks(context)
        tracks.groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .map { (artistName, artistTracks) ->
                val first = artistTracks.first()
                SearchItem(
                    uri = "local:artist:$artistName",
                    title = artistName,
                    subtitle = "${artistTracks.size} tracks",
                    artworkUrl = first.artworkUrl,
                )
            }
    }

    override suspend fun tracksOf(uri: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val all = LocalLibrary.tracks(context)
        when {
            uri == LocalLibrary.CONTEXT_URI -> all
            uri.startsWith("local:album:") -> {
                val albumName = uri.removePrefix("local:album:")
                all.filter { it.album.equals(albumName, ignoreCase = true) }
            }
            uri.startsWith("local:artist:") -> {
                val artistName = uri.removePrefix("local:artist:")
                all.filter { it.artist.equals(artistName, ignoreCase = true) }
            }
            uri.startsWith("local:folder:") -> {
                val folderName = uri.removePrefix("local:folder:")
                all.filter {
                    val p = it.localFile ?: ""
                    File(p).parentFile?.name.equals(folderName, ignoreCase = true)
                }
            }
            else -> all
        }
    }

    override suspend fun search(query: String, labels: SearchLabels, offset: Int): SearchResults = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isBlank()) return@withContext SearchResults()

        val all = LocalLibrary.tracks(context)
        val matchedTracks = all.filter {
            it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }

        val matchedAlbums = all.groupBy { it.album }
            .filter { it.key.lowercase().contains(q) }
            .map { (albumName, albumTracks) ->
                val first = albumTracks.first()
                SearchItem(
                    uri = "local:album:$albumName",
                    title = albumName,
                    subtitle = "${albumTracks.size} tracks • ${first.artist}",
                    artworkUrl = first.artworkUrl,
                )
            }

        val matchedArtists = all.groupBy { it.artist }
            .filter { it.key.lowercase().contains(q) }
            .map { (artistName, artistTracks) ->
                val first = artistTracks.first()
                SearchItem(
                    uri = "local:artist:$artistName",
                    title = artistName,
                    subtitle = "${artistTracks.size} tracks",
                    artworkUrl = first.artworkUrl,
                )
            }

        SearchResults(
            tracks = matchedTracks,
            albums = matchedAlbums,
            artists = matchedArtists,
        )
    }

    override suspend fun lyrics(
        uri: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Lyrics? = withContext(Dispatchers.IO) {
        // 1. Try local embedded or sidecar lyrics
        val contentUri = LocalLibrary.contentUri(uri)
        val all = LocalLibrary.tracks(context)
        val track = all.firstOrNull { it.uri == uri }
        val filePath = track?.localFile

        val rawLyrics = lyricsExtractor.extract(
            contentUri = contentUri,
            displayName = track?.name,
            mimeType = "audio/flac",
            filePath = filePath,
        )

        if (!rawLyrics.isNullOrBlank()) {
            val parsed = if (rawLyrics.startsWith("<?xml") || rawLyrics.contains("<tt")) {
                Ttml.parse(rawLyrics)
            } else {
                LrcLib.parseLrc(rawLyrics)
            }
            if (parsed != null && parsed.lines.isNotEmpty()) {
                return@withContext parsed.copy(source = "Embedded")
            }
        }

        // 2. Fall back to online multi-source lookup (BetterLyrics, Apple Music, LRCLIB, KuGou)
        LyricsLibrary.lyrics(
            LyricsQuery(
                uri = uri,
                title = title,
                artist = artist,
                durationMs = durationMs,
            ),
        )
    }

    private fun getOrParseFlacInfo(track: CatalogTrack): FlacTrackInfo? {
        val file = track.localFile?.let { File(it) } ?: return null
        if (!file.exists() || !file.name.endsWith(".flac", ignoreCase = true)) return null
        return flacInfoCache.getOrPut(file.absolutePath) {
            FlacMetadataParser.parse(file) ?: FlacTrackInfo()
        }
    }
}
