package dev.lelonio.square.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.PushPin
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.backend.subsonic.SubsonicPlaylistEntry
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.LibraryTab
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.AlbumCard
import dev.lelonio.square.desktop.ui.components.AlbumContextWrapper
import dev.lelonio.square.desktop.ui.components.ArtistCard
import dev.lelonio.square.desktop.ui.components.ArtistContextWrapper
import dev.lelonio.square.desktop.ui.components.Card
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.Grid
import dev.lelonio.square.desktop.ui.components.LayoutToggle
import dev.lelonio.square.desktop.ui.components.ListEntry
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.PlaylistContextWrapper
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.TrackRow
import dev.lelonio.square.desktop.ui.components.load
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(state: AppState) {
    val tab = state.libraryTab
    val scope = rememberCoroutineScope()
    var creatingPlaylist by remember { mutableStateOf(false) }

    val localTracks by state.local.tracks.collectAsState()
    val localPlaylists by state.local.playlistStore.playlists.collectAsState()
    val localSongs = remember(localTracks) { localTracks.map { it.toDesktopSong() } }

    val albums = remember(state.generation, state.offlineMode) { mutableStateListOf<SubsonicAlbumEntry>() }
    var moreAlbums by remember(state.generation, state.offlineMode) { mutableStateOf(!state.offlineMode) }
    var failed by remember(state.generation, state.offlineMode) { mutableStateOf<String?>(null) }

    val serverSongs = remember(state.generation, state.offlineMode) { mutableStateListOf<SubsonicChildSong>() }
    var songsLoaded by remember(state.generation, state.offlineMode) { mutableStateOf(false) }

    val artistList = load(state, tab == LibraryTab.ARTISTS, state.offlineMode) {
        if (tab != LibraryTab.ARTISTS) Result.success(emptyList())
        else if (state.offlineMode || !state.prefs.isConfigured()) Result.success(state.downloads.cachedArtists())
        else state.client.getArtists()
    }
    val remotePlaylists = load(state, tab == LibraryTab.PLAYLISTS, state.offlineMode) {
        if (tab != LibraryTab.PLAYLISTS || state.offlineMode || !state.prefs.isConfigured()) Result.success(emptyList())
        else state.client.getPlaylists()
    }

    suspend fun loadAlbumPage() {
        if (state.offlineMode || !state.prefs.isConfigured()) {
            albums.clear()
            albums.addAll(state.downloads.cachedAlbums())
            moreAlbums = false
            return
        }
        state.client.getAlbumList("alphabeticalByName", 120, albums.size)
            .onSuccess { albums.addAll(it); moreAlbums = it.size == 120 }
            .onFailure { failed = it.message; moreAlbums = false }
    }

    LaunchedEffect(state.generation, state.offlineMode, tab) {
        if (tab == LibraryTab.ALBUMS && albums.isEmpty()) loadAlbumPage()
        if (tab == LibraryTab.SONGS && !songsLoaded) {
            songsLoaded = true
            if (!state.offlineMode && state.prefs.isConfigured()) {
                state.client.search("", songCount = 200, songOffset = 0, albumCount = 0, artistCount = 0)
                    .onSuccess { serverSongs.addAll(it.song) }
                    .onFailure { failed = it.message }
            }
        }
    }

    val allSongs = remember(localSongs, serverSongs.size, state.offlineMode) {
        val base = if (state.offlineMode || !state.prefs.isConfigured()) state.downloads.cachedSongs() else serverSongs.toList()
        (localSongs + base).distinctBy { it.id }
    }

    val mergedPlaylists = remember(localPlaylists, remotePlaylists, state.pins.size, localTracks) {
        val remote = remotePlaylists?.getOrNull().orEmpty().map { p ->
            SubsonicPlaylistEntry(id = p.id, name = p.name, coverArt = p.coverArt, songCount = p.songCount)
        }
        val localAsEntries = localPlaylists.map { lp ->
            val art = lp.entries.firstNotNullOfOrNull { state.local.findById(it.path)?.artworkUrl }
            SubsonicPlaylistEntry(
                id = LocalCatalog.LOCAL_PLAYLIST_PREFIX + lp.id,
                name = lp.name,
                coverArt = art,
                songCount = lp.entries.size,
            )
        }
        val pinnedIds = state.pins.map { it.id }.toSet()
        (localAsEntries + remote).sortedByDescending { it.id in pinnedIds }
    }

    Grid(header = {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("library", Modifier.weight(1f))
            if (tab == LibraryTab.PLAYLISTS) {
                Pill(PhosphorIcons.Regular.Plus, "new_playlist", accent = true) { creatingPlaylist = true }
            }
            LayoutToggle(state)
            Segmented(
                listOf("tracks", "albums", "artists", "playlists"),
                tab.ordinal,
            ) { state.selectLibraryTab(LibraryTab.entries[it]) }
        }
        failed?.let { Text("${str("desktop_server_unreachable")}: $it", color = InkDim, modifier = Modifier.padding(top = 6.dp)) }
    }) {
        when (tab) {
            LibraryTab.SONGS -> {
                itemsIndexed(allSongs, key = { _, s -> "song:${s.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, s ->
                    TrackRow(state, s, null, showArt = true) { state.player.playQueue(allSongs, i) }
                }
            }
            LibraryTab.ALBUMS -> {
                items(albums, key = { "al:" + it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { a ->
                    if (state.listMode) {
                        AlbumContextWrapper(state, a) { menuMod ->
                            ListEntry(
                                state.cover(a.coverArt, 100),
                                a.name,
                                listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
                                modifier = menuMod,
                            ) { state.open(Screen.Album(a.id, a.name)) }
                        }
                    } else {
                        AlbumCard(state, a)
                    }
                }
                if (moreAlbums) item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(albums.size) { loadAlbumPage() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }
            LibraryTab.ARTISTS -> {
                items(artistList?.getOrNull().orEmpty(), key = { "ar:" + it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { ar ->
                    if (state.listMode) {
                        ArtistContextWrapper(state, ar) { menuMod ->
                            ListEntry(state.cover(ar.coverArt, 100), ar.name, str("album_count", ar.albumCount), round = true, modifier = menuMod) {
                                state.open(Screen.Artist(ar.id, ar.name))
                            }
                        }
                    } else {
                        ArtistCard(state, ar)
                    }
                }
            }
            LibraryTab.PLAYLISTS -> {
                val pinnedIds = state.pins.map { it.id }.toSet()
                items(mergedPlaylists, key = { "pl:" + it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { p ->
                    val pin = p.id in pinnedIds
                    val isLocal = p.id.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX)
                    val sub = "${p.songCount} ${str("desktop_songs_unit")}" +
                        (if (isLocal) " · .m3u8" else "") +
                        (if (pin) " · " + str("pinned") else "")
                    val open = { state.open(Screen.Playlist(p.id, p.name)) }
                    PlaylistContextWrapper(state, p) { menuMod ->
                        if (state.listMode) {
                            ListEntry(
                                state.cover(p.coverArt, 100),
                                p.name,
                                sub,
                                modifier = menuMod,
                                trailing = {
                                    Glyph(PhosphorIcons.Regular.PushPin, if (pin) "unpin" else "pin", active = pin) {
                                        state.togglePin(p.id, p.name, p.coverArt)
                                    }
                                },
                                onClick = open,
                            )
                        } else {
                            Card(p.name, sub, state.cover(p.coverArt), modifier = menuMod, onClick = open)
                        }
                    }
                }
            }
        }
    }

    if (creatingPlaylist) {
        CreatePlaylistDialog(
            state = state,
            onDismiss = { creatingPlaylist = false },
            onCreate = { name, asLocal ->
                creatingPlaylist = false
                scope.launch {
                    runCatching {
                        if (asLocal) state.local.createPlaylist(name)
                        else state.subsonicCatalog.createPlaylist(name)
                    }.onSuccess {
                        state.generation++
                        state.say(name)
                    }.onFailure { state.say("${it.message}") }
                }
            },
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onCreate: (name: String, asLocal: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var asLocal by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str("new_playlist")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().onFocusChanged { state.typing = it.isFocused },
                    label = { Text(str("name")) },
                    singleLine = true,
                )
                if (!state.offlineMode && state.prefs.isConfigured()) {
                    Segmented(listOf("desktop_local_m3u8", "desktop_subsonic"), if (asLocal) 0 else 1) { asLocal = it == 0 }
                }
            }
        },
        confirmButton = {
            TextButton({
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) onCreate(trimmed, asLocal)
            }) { Text(str("create")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(str("cancel")) } },
    )
}
