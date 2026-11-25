package dev.lelonio.square.desktop.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.WifiSlash
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicArtistDetail
import dev.lelonio.square.backend.subsonic.SubsonicMixes
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.AlbumCard
import dev.lelonio.square.desktop.ui.components.BackButton
import dev.lelonio.square.desktop.ui.components.Grid
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.Loaded
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.load
import dev.lelonio.square.ui.theme.InkDim

@Composable
fun MixScreen(state: AppState, screen: Screen.Mix) {
    val songs = load(state, screen) {
        state.client.getGenres().mapCatching { genres ->
            val mix = SubsonicMixes.find(SubsonicMixes.build(genres), screen.key) ?: error("No such mix")
            mix.rawGenres.flatMap { state.client.getSongsByGenre(it, 500).getOrDefault(emptyList()) }.distinctBy { it.id }
        }
    }
    Loaded(songs) { list ->
        Tracks(state, list, screen.name, subtitle = "${list.size} ${str("desktop_songs_unit")}", cover = list.firstOrNull()?.coverArt, showAlbum = true)
    }
}

@Composable
fun DownloadsScreen(state: AppState) {
    val songs = state.downloads.kept.map { it.song }.reversed()
    val limitText = if (state.cacheLimitGb <= 0) str("desktop_unlimited") else "${state.cacheLimitGb} GB"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${songs.size} ${str("desktop_songs_unit")} · %.2f GB / $limitText".format(state.downloads.bytes() / 1e9),
                color = InkDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Pill(PhosphorIcons.Regular.WifiSlash, "offline_mode", accent = state.offlineMode) {
                state.useOfflineMode(!state.offlineMode)
            }
        }
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(str("desktop_nothing_downloaded"), color = InkDim)
            }
        } else {
            Tracks(state, songs, "downloaded", "${songs.size} ${str("desktop_songs_unit")} · %.2f GB".format(state.downloads.bytes() / 1e9), songs.first().coverArt, showAlbum = true)
        }
    }
}

@Composable
fun LocalAlbumScreen(state: AppState, screen: Screen.LocalAlbum) {
    val tracks by state.local.tracks.collectAsState()
    val list = remember(tracks, screen.uri) { state.local.desktopTracksOf(screen.uri) }
    val first = list.firstOrNull()
    Tracks(
        state,
        list,
        screen.name,
        subtitle = listOfNotNull(first?.artist, first?.year?.toString(), first?.genre).joinToString(" · "),
        cover = first?.coverArt,
        showAlbum = false,
    )
}

@Composable
fun LocalArtistScreen(state: AppState, screen: Screen.LocalArtist) {
    val tracks by state.local.tracks.collectAsState()
    val list = remember(tracks, screen.uri) { state.local.desktopTracksOf(screen.uri) }
    Tracks(state, list, screen.name, subtitle = "${list.size} ${str("desktop_songs_unit")}", cover = list.firstOrNull()?.coverArt, showAlbum = true)
}

@Composable
fun ArtistScreen(state: AppState, screen: Screen.Artist) {
    val result = load(state, screen, state.offlineMode) {
        if (state.offlineMode) {
            val albums = state.downloads.cachedSongs()
                .filter { (it.artistId ?: it.artist.orEmpty()) == screen.id }
                .groupBy { it.albumId ?: it.album.orEmpty() }
                .map { (id, tracks) ->
                    val first = tracks.first()
                    SubsonicAlbumEntry(id = id, name = first.album.orEmpty(), artist = first.artist, artistId = first.artistId, coverArt = first.coverArt, songCount = tracks.size, year = first.year)
                }
            Result.success(SubsonicArtistDetail(id = screen.id, name = screen.name, albumCount = albums.size, album = albums))
        } else {
            state.client.getArtist(screen.id)
        }
    }
    Loaded(result) { artist ->
        Grid(header = {
            Column {
                BackButton(state)
                Title(artist.name)
            }
        }) {
            items(artist.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { AlbumCard(state, it) }
        }
    }
}

@Composable
fun AlbumScreen(state: AppState, screen: Screen.Album) {
    val songs = load(state, screen, state.offlineMode) {
        if (state.offlineMode) {
            Result.success(state.downloads.cachedSongs().filter { (it.albumId ?: it.album.orEmpty()) == screen.id })
        } else {
            state.client.getAlbum(screen.id)
        }
    }
    Loaded(songs) { list ->
        val first = list.firstOrNull()
        Tracks(
            state,
            list,
            screen.name,
            subtitle = listOfNotNull(first?.artist, first?.year?.toString(), first?.genre).joinToString(" · "),
            cover = first?.coverArt,
            showAlbum = false,
        )
    }
}

@Composable
fun PlaylistScreen(state: AppState, screen: Screen.Playlist) {
    val isLocal = screen.id.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX)
    val localPlaylists by state.local.playlistStore.playlists.collectAsState()
    if (isLocal) {
        val list = remember(localPlaylists, screen.id, state.generation) { state.local.desktopTracksOf(screen.id) }
        val plName = localPlaylists.firstOrNull { LocalCatalog.LOCAL_PLAYLIST_PREFIX + it.id == screen.id }?.name ?: screen.name
        Tracks(state, list, plName, subtitle = "${list.size} ${str("desktop_songs_unit")} · .m3u8", cover = list.firstOrNull()?.coverArt, showAlbum = true, playlistId = screen.id)
    } else {
        val songs = load(state, screen) { state.client.getPlaylist(screen.id) }
        Loaded(songs) { list ->
            Tracks(state, list, screen.name, subtitle = "${list.size} ${str("desktop_songs_unit")}", cover = list.firstOrNull()?.coverArt, showAlbum = true, playlistId = screen.id)
        }
    }
}
