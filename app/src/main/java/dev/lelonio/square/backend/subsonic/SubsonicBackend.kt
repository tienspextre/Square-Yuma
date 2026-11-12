package dev.lelonio.square.backend.subsonic

import androidx.media3.common.Player
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.HomeChip
import dev.lelonio.square.backend.HomeFeed
import dev.lelonio.square.backend.HomeRow
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.backend.lyrics.LyricsLibrary
import dev.lelonio.square.backend.lyrics.LyricsQuery
import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.playback.LocalPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SubsonicBackend(
    val client: SubsonicClient,
    val offlineStore: OfflineTrackStore,
    val downloadManager: SubsonicDownloadManager,
) : MusicBackend {

    override val id: BackendId = BackendId.SUBSONIC

    private val _authState = MutableStateFlow<BackendAuthState>(
        if (client.preferences.isConfigured()) {
            BackendAuthState.LoggedIn(displayName = "NAS / Navidrome (${client.preferences.username.value})")
        } else {
            BackendAuthState.LoggedOut
        },
    )
    override val authState: StateFlow<BackendAuthState> = _authState.asStateFlow()

    override val isReady: Boolean
        get() = client.preferences.isConfigured()

    override fun owns(uri: String): Boolean =
        uri.startsWith("subsonic:")

    override fun createPlayer(host: PlaybackHost): Player =
        LocalPlayerFactory.create(host)

    override suspend fun refreshAuth() {
        if (!client.preferences.isConfigured()) {
            _authState.value = BackendAuthState.LoggedOut
            return
        }
        val ping = client.ping()
        if (ping.isSuccess) {
            val info = ping.getOrThrow()
            _authState.value = BackendAuthState.LoggedIn(
                displayName = "Navidrome v${info.serverVersion} (${client.preferences.username.value})",
            )
        } else {
            _authState.value = BackendAuthState.Failed(
                ping.exceptionOrNull()?.message ?: "Could not connect to Navidrome",
            )
        }
    }

    override suspend fun logOut() {
        client.preferences.setUsername("")
        client.preferences.setPassword("")
        _authState.value = BackendAuthState.LoggedOut
    }

    /**
     * Whether an earlier install may have left downloads behind: the index lives in the app's
     * private storage and goes with an uninstall, Music/Square does not.
     * Only a folder check, which needs no permission; [relink] needs the audio one.
     */
    fun hasLeftovers(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            client.preferences.isConfigured() &&
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                "Square",
            ).isDirectory

    /**
     * Indexes again the downloads in Music/Square that the index does not know, each matched
     * back to its song on the server by the path it was saved under, Artist/Album/Title.ext
     * (see SubsonicDownloadManager.publish). Returns the songs found.
     */
    // ponytail: matched by path, not tags; a file under "Unknown Album" or renamed by hand is
    // not found. Tag reading (or a sidecar with the song id) if that ever matters.
    suspend fun relink(context: android.content.Context): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val safe = SubsonicDownloadManager.Companion::safeName
        val known = offlineStore.records.value.values.map { it.localFilePath }.toSet()
        val left = downloadManager.onDevice().filter { it.uri !in known }
        fun CatalogTrack.matches(file: SubsonicDownloadManager.DeviceFile) =
            safe(name, 100, "") == file.title &&
                safe(artist, 60, "Unknown Artist") == file.artist &&
                safe(album, 60, "Unknown Album") == file.album
        val found = mutableListOf<CatalogTrack>()
        for ((album, files) in left.groupBy { it.album }) {
            // The whole album in two calls, rather than a search per song.
            val songs = client.search(album).getOrNull()?.album.orEmpty()
                .filter { safe(it.name, 60, "Unknown Album") == album }
                .flatMap { client.getAlbum(it.id).getOrNull().orEmpty() }
                .map { it.toCatalogTrack(client, offlineStore) }
            for (file in files) {
                val track = songs.firstOrNull { it.matches(file) }
                    ?: client.search(file.title).getOrNull()?.song.orEmpty()
                        .map { it.toCatalogTrack(client, offlineStore) }
                        .firstOrNull { it.matches(file) }
                    ?: continue
                // Downloaded again by this install before it knew of the old file: the copy
                // it made ("Title (1).flac") is this install's own, so it can go.
                offlineStore.records.value[track.uri]?.localFilePath
                    ?.takeIf { it.startsWith("content://media/") }
                    ?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }
                offlineStore.saveRecord(downloadManager.recordOf(track, file))
                found += track.copy(localFile = file.uri)
            }
        }
        android.util.Log.i("SubsonicDownload", "relinked ${found.size} of ${left.size} leftover downloads")
        found
    }

    override suspend fun homeFeed(cursor: String?, params: String?): HomeFeed = withContext(Dispatchers.IO) {
        val mixes = SubsonicMixes.build(client.getGenres().getOrDefault(emptyList()))
        val chips = mixes.map { HomeChip(it.title, it.key) }
        if (params != null) {
            val mix = SubsonicMixes.find(mixes, params)
                ?: return@withContext HomeFeed(rows = emptyList(), chips = chips)
            return@withContext HomeFeed(rows = mixRows(mix), chips = chips)
        }

        val rows = mutableListOf<HomeRow>()

        // 1. Offline downloaded tracks shelf
        val offlineTracks = offlineStore.getAllCatalogTracks()
        if (offlineTracks.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Downloaded Offline",
                    strapline = "ON DEVICE",
                    tracks = offlineTracks.take(20),
                ),
            )
        }

        // The whole-library songs shelf lives in the Library's Songs chip now; see allSongs.

        // Moods first, then genres: each tile opens the whole list, playable or shufflable.
        if (mixes.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Moods & Genres",
                    strapline = "LISTEN BY",
                    items = mixes.map { it.toCatalogPlaylist() },
                ),
            )
        }

        // 3. Playlists on NAS
        val playlists = client.getPlaylists().getOrDefault(emptyList())
        if (playlists.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Playlists on NAS",
                    strapline = "PLAYLISTS",
                    items = playlists.map { pl ->
                        CatalogPlaylist(
                            uri = "subsonic:playlist:${pl.id}",
                            name = pl.name,
                            artworkUrl = client.buildCoverArtUrl(pl.coverArt),
                            subtitle = "${pl.songCount} tracks",
                            mine = false,
                        )
                    },
                ),
            )
        }

        // 4. Recently added albums on NAS
        val newestAlbums = client.getAlbumList(type = "newest", size = 20).getOrDefault(emptyList())
        if (newestAlbums.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Recently Added on NAS",
                    strapline = "HI-RES LOSSLESS",
                    items = newestAlbums.map { it.toCatalogPlaylist(client) },
                ),
            )
        }

        // 5. Recently played
        val recentAlbums = client.getAlbumList(type = "recent", size = 20).getOrDefault(emptyList())
        if (recentAlbums.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Recently Played",
                    strapline = "HISTORY",
                    items = recentAlbums.map { it.toCatalogPlaylist(client) },
                ),
            )
        }

        // 6. Random discovery
        val randomAlbums = client.getAlbumList(type = "random", size = 20).getOrDefault(emptyList())
        if (randomAlbums.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Random Discovery",
                    strapline = "EXPLORE",
                    items = randomAlbums.map { it.toCatalogPlaylist(client) },
                ),
            )
        }

        // 7. Starred / Favorites
        val starredAlbums = client.getAlbumList(type = "starred", size = 20).getOrDefault(emptyList())
        if (starredAlbums.isNotEmpty()) {
            rows.add(
                HomeRow(
                    title = "Favorites",
                    strapline = "STARRED",
                    items = starredAlbums.map { it.toCatalogPlaylist(client) },
                ),
            )
        }

        if (rows.isEmpty()) {
            rows.add(
                HomeRow(
                    title = "Navidrome Connected",
                    strapline = "SERVER READY",
                    tracks = emptyList(),
                ),
            )
        }

        HomeFeed(rows = rows, chips = chips)
    }

    private fun SubsonicMixes.Mix.toCatalogPlaylist() = CatalogPlaylist(
        uri = SubsonicMixes.URI_PREFIX + key,
        name = title,
        artworkUrl = null,
        subtitle = "${if (kind == "MOOD") "Mood" else "Genre"} • $songCount songs",
        mine = false,
    )

    /** Every song behind a mood or genre, once each, shuffled. */
    private suspend fun mixSongs(mix: SubsonicMixes.Mix): List<SubsonicChildSong> =
        mix.rawGenres.flatMap { client.getSongsByGenre(it).getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .shuffled()

    /** The page a mood or genre chip opens: the list itself, a sample of songs, its albums. */
    private suspend fun mixRows(mix: SubsonicMixes.Mix): List<HomeRow> {
        val songs = mixSongs(mix)
        val cover = songs.firstOrNull { it.coverArt != null }?.coverArt
        val albums = mix.rawGenres
            .flatMap { client.getAlbumList("byGenre", size = 30, genre = it).getOrDefault(emptyList()) }
            .distinctBy { it.id }
        return listOfNotNull(
            HomeRow(
                title = mix.title,
                strapline = if (mix.kind == "MOOD") "MOOD" else "GENRE",
                items = listOf(mix.toCatalogPlaylist().copy(artworkUrl = client.buildCoverArtUrl(cover))),
            ),
            HomeRow(
                title = "Songs",
                strapline = "${songs.size} ON NAS",
                tracks = songs.take(40).map { it.toCatalogTrack(client, offlineStore) },
            ).takeIf { songs.isNotEmpty() },
            HomeRow(
                title = "Albums",
                items = albums.map { it.toCatalogPlaylist(client) },
            ).takeIf { albums.isNotEmpty() },
        )
    }

    override suspend fun playlists(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CatalogPlaylist>()
        val offline = offlineStore.getAllCatalogTracks()
        if (offline.isNotEmpty()) {
            list.add(
                CatalogPlaylist(
                    uri = "subsonic:offline",
                    name = "Downloaded Offline",
                    artworkUrl = offline.firstOrNull()?.artworkUrl,
                    subtitle = "${offline.size} songs stored locally",
                    mine = true,
                ),
            )
        }
        val serverPlaylists = client.getPlaylists().getOrDefault(emptyList())
        list.addAll(
            serverPlaylists.map { pl ->
                CatalogPlaylist(
                    uri = "subsonic:playlist:${pl.id}",
                    name = pl.name,
                    artworkUrl = client.buildCoverArtUrl(pl.coverArt),
                    subtitle = "${pl.songCount} songs • NAS Playlist",
                    // Rename and delete are offered on the account's own lists.
                    mine = pl.owner == null || pl.owner == client.preferences.username.value,
                )
            },
        )
        list
    }

    override suspend fun savedAlbums(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        // The whole library, A to Z, 500 at a time (the most Navidrome serves per call).
        val all = mutableListOf<SubsonicAlbumEntry>()
        while (true) {
            val page = client.getAlbumList("alphabeticalByName", size = 500, offset = all.size).getOrDefault(emptyList())
            all += page
            if (page.size < 500) break
        }
        all.map { it.toCatalogPlaylist(client) }
    }

    override suspend fun allSongs(): List<CatalogTrack> = withContext(Dispatchers.IO) {
        // search3 with no query is the whole library on Navidrome, 500 at a time.
        val seen = HashSet<String>()
        val all = mutableListOf<SubsonicChildSong>()
        while (true) {
            val page = client.search("", songCount = SONG_PAGE, songOffset = all.size, albumCount = 0, artistCount = 0)
                .getOrNull()?.song.orEmpty()
            // A server that ignores the offset hands the same page back: stop rather than loop.
            val fresh = page.filter { seen.add(it.id) }
            all += fresh
            if (page.size < SONG_PAGE || fresh.isEmpty()) break
        }
        all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .map { it.toCatalogTrack(client, offlineStore) }
    }

    override suspend fun followedArtists(): List<SearchItem> = withContext(Dispatchers.IO) {
        val artists = client.getArtists().getOrDefault(emptyList())
        artists.map {
            SearchItem(
                uri = "subsonic:artist:${it.id}",
                title = it.name,
                subtitle = "${it.albumCount} albums",
                artworkUrl = client.buildCoverArtUrl(it.coverArt),
            )
        }
    }

    // The NAS playlists are .m3u8 files; every edit goes through the bridge, which writes the
    // file and has Navidrome rescan it. Creating one needs an admin account (see the bridge).
    override val canEditPlaylists: Boolean get() = client.preferences.isConfigured()

    override fun canWriteTo(playlistUri: String): Boolean = playlistUri.startsWith(PLAYLIST_PREFIX)

    override suspend fun createPlaylist(name: String, description: String, public: Boolean): CatalogPlaylist {
        val answer = client.bridgePlaylist("create", name = name).getOrThrow()
        val id = answer.optString("playlist_id").takeIf { it.isNotBlank() && it != "null" }
            ?: throw java.io.IOException("Navidrome has not imported the new playlist yet")
        return CatalogPlaylist(uri = PLAYLIST_PREFIX + id, name = name, subtitle = "0 songs", mine = true)
    }

    override suspend fun renamePlaylist(uri: String, name: String) {
        client.bridgePlaylist("rename", playlistId = uri.removePrefix(PLAYLIST_PREFIX), name = name).getOrThrow()
    }

    override suspend fun deletePlaylist(uri: String) {
        client.bridgePlaylist("delete", playlistId = uri.removePrefix(PLAYLIST_PREFIX)).getOrThrow()
    }

    override suspend fun writablePlaylists(): List<CatalogPlaylist> =
        playlists().filter { it.uri.startsWith(PLAYLIST_PREFIX) }

    override suspend fun addToPlaylist(playlistUri: String, trackUri: String) =
        addAllToPlaylist(playlistUri, listOf(trackUri))

    override suspend fun addAllToPlaylist(playlistUri: String, trackUris: List<String>) {
        client.bridgePlaylist(
            "add",
            playlistId = playlistUri.removePrefix(PLAYLIST_PREFIX),
            songIds = trackUris.filter { it.startsWith("subsonic:track:") }.map { it.removePrefix("subsonic:track:") },
        ).getOrThrow()
    }

    override suspend fun removeFromPlaylist(playlistUri: String, trackUri: String) {
        client.bridgeRemove(
            trackUri.removePrefix("subsonic:track:"),
            playlistUri.removePrefix("subsonic:playlist:"),
            deleteFile = false,
        ).getOrThrow()
    }

    /** Deletes the song's file from the NAS, which also takes it out of every playlist. */
    suspend fun deleteFromNas(trackUri: String) {
        client.bridgeRemove(trackUri.removePrefix("subsonic:track:"), null, deleteFile = true).getOrThrow()
    }

    override suspend fun tracksOf(uri: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        if (uri == "subsonic:offline") {
            return@withContext offlineStore.getAllCatalogTracks()
        }
        // The old virtual playlist, still reachable from a pin or a widget made before the Songs chip.
        if (uri == "subsonic:all_songs") return@withContext allSongs()
        if (uri.startsWith(SubsonicMixes.URI_PREFIX)) {
            val mixes = SubsonicMixes.build(client.getGenres().getOrDefault(emptyList()))
            val mix = SubsonicMixes.find(mixes, uri.removePrefix(SubsonicMixes.URI_PREFIX))
                ?: return@withContext emptyList()
            return@withContext mixSongs(mix).map { it.toCatalogTrack(client, offlineStore) }
        }
        if (uri.startsWith("subsonic:playlist:")) {
            val id = uri.removePrefix("subsonic:playlist:")
            val songs = client.getPlaylist(id).getOrDefault(emptyList())
            return@withContext songs.map { it.toCatalogTrack(client, offlineStore) }
        }
        if (uri.startsWith("subsonic:album:")) {
            val id = uri.removePrefix("subsonic:album:")
            val songs = client.getAlbum(id).getOrDefault(emptyList())
            return@withContext songs.map { it.toCatalogTrack(client, offlineStore) }
        }
        if (uri.startsWith("subsonic:artist:")) {
            val id = uri.removePrefix("subsonic:artist:")
            val artistDetail = client.getArtist(id).getOrNull()
            if (artistDetail != null) {
                val allSongs = mutableListOf<CatalogTrack>()
                for (album in artistDetail.album) {
                    val songs = client.getAlbum(album.id).getOrDefault(emptyList())
                    allSongs.addAll(songs.map { it.toCatalogTrack(client, offlineStore) })
                }
                return@withContext allSongs
            }
        }
        emptyList()
    }

    /** An artist's records on the server, newest first, for the shelf on their page. */
    suspend fun artistAlbums(uri: String): List<SearchItem> = withContext(Dispatchers.IO) {
        if (!uri.startsWith("subsonic:artist:")) return@withContext emptyList()
        val detail = client.getArtist(uri.removePrefix("subsonic:artist:")).getOrNull() ?: return@withContext emptyList()
        detail.album.sortedByDescending { it.year ?: 0 }.map {
            SearchItem(
                uri = "subsonic:album:${it.id}",
                title = it.name,
                subtitle = listOfNotNull(it.year?.toString(), "${it.songCount} songs").joinToString(" • "),
                artworkUrl = client.buildCoverArtUrl(it.coverArt),
            )
        }
    }

    override suspend fun search(query: String, labels: SearchLabels, offset: Int): SearchResults = withContext(Dispatchers.IO) {
        val result = client.search(query).getOrDefault(SubsonicSearchResult3())
        SearchResults(
            tracks = result.song.map { it.toCatalogTrack(client, offlineStore) },
            albums = result.album.map {
                SearchItem(
                    uri = "subsonic:album:${it.id}",
                    title = it.name,
                    subtitle = it.artist ?: labels.album,
                    artworkUrl = client.buildCoverArtUrl(it.coverArt),
                )
            },
            artists = result.artist.map {
                SearchItem(
                    uri = "subsonic:artist:${it.id}",
                    title = it.name,
                    subtitle = labels.artist,
                    artworkUrl = client.buildCoverArtUrl(it.coverArt),
                )
            },
        )
    }

    override suspend fun lyrics(uri: String, title: String, artist: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        // A downloaded song's own copy first: works offline and costs nothing.
        dev.lelonio.square.backend.lyrics.SpotifyLyrics.kept(uri)?.let { return@withContext it }
        if (uri.startsWith("subsonic:track:")) {
            val songId = uri.removePrefix("subsonic:track:")
            val serverLyrics = client.getLyrics(songId).getOrNull()
            if (!serverLyrics.isNullOrEmpty()) {
                val lines = serverLyrics.map { line ->
                    LyricLine(
                        startTimeMs = line.start,
                        text = line.value,
                    )
                }
                return@withContext Lyrics(
                    lines = lines,
                    synced = true,
                    source = "Navidrome",
                )
            }
        }

        // Fallback to online multi-engine provider (BetterLyrics, LrcLib, etc.)
        LyricsLibrary.lyrics(
            LyricsQuery(
                uri = uri,
                title = title,
                artist = artist,
                durationMs = durationMs,
            ),
        )
    }

    private fun SubsonicAlbumEntry.toCatalogPlaylist(client: SubsonicClient): CatalogPlaylist =
        CatalogPlaylist(
            uri = "subsonic:album:$id",
            name = name,
            artworkUrl = client.buildCoverArtUrl(coverArt),
            subtitle = "${artist.orEmpty()} • $songCount tracks",
            mine = false,
        )

    private fun SubsonicChildSong.toCatalogTrack(
        client: SubsonicClient,
        offlineStore: OfflineTrackStore,
    ): CatalogTrack {
        val trackUri = "subsonic:track:$id"
        val localFile = offlineStore.localPath(trackUri)
        return CatalogTrack(
            uri = trackUri,
            name = title,
            artist = artist.orEmpty().ifBlank { "Unknown Artist" },
            album = album.orEmpty().ifBlank { "Unknown Album" },
            durationMs = duration * 1000L,
            artworkUrl = client.buildCoverArtUrl(coverArt),
            localFile = localFile,
            artists = if (!artist.isNullOrBlank()) listOf(CatalogArtist(name = artist, uri = null)) else emptyList(),
        )
    }
}

/** The most Navidrome serves per search3 call. */
private const val SONG_PAGE = 500

private const val PLAYLIST_PREFIX = "subsonic:playlist:"
