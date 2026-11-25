package dev.lelonio.square.desktop.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.User
import com.adamglin.phosphoricons.regular.XCircle
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicArtistItem
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.backend.subsonic.SubsonicPlaylistEntry
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.DropTargetHandler
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.ShortcutDispatch
import dev.lelonio.square.desktop.str
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContextMenuBox(
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val triggerModifier = Modifier
        .onSizeChanged { size = it }
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .then(if (focused) Modifier.border(1.dp, Ink.copy(alpha = 0.28f), RoundedCornerShape(10.dp)) else Modifier)
        .onPointerEvent(PointerEventType.Press) { e ->
            if (e.buttons.isSecondaryPressed) {
                e.changes.first().position.let { menuAt = IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
            }
        }
        .onPreviewKeyEvent { e ->
            if (ShortcutDispatch.isContextMenuShortcut(e)) {
                menuAt = IntOffset((size.width / 3).coerceAtLeast(16), (size.height / 2).coerceAtLeast(16))
                true
            } else {
                false
            }
        }

    Box(modifier) {
        content(triggerModifier)
        GlassMenu(menuAt != null, menuAt ?: IntOffset.Zero, onDismiss = { menuAt = null }) {
            menuContent { menuAt = null }
        }
    }
}

@Composable
fun BatchPlaylistPickerDialog(
    state: AppState,
    songs: List<SubsonicChildSong>,
    isLocal: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val targets by produceState<List<CatalogPlaylist>>(emptyList(), isLocal) {
        value = if (isLocal) state.local.writablePlaylists() else state.subsonicCatalog.writablePlaylists()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str("add_to_playlist")) },
        text = {
            Column {
                if (targets.isEmpty()) Text(str("desktop_no_playlists"), color = InkDim)
                targets.forEach { pl ->
                    TextButton({
                        onDismiss()
                        scope.launch {
                            runCatching {
                                if (isLocal) {
                                    state.local.addAllToPlaylist(pl.uri, songs.map { it.id })
                                } else {
                                    state.subsonicCatalog.addAllToPlaylist(pl.uri, songs.map { "subsonic:track:${it.id}" })
                                }
                            }.onSuccess {
                                state.generation++
                                state.say(str("added_to", pl.name))
                            }.onFailure { state.say("${it.message}") }
                        }
                    }) { Text(pl.name) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(str("close")) } },
    )
}

@Composable
fun AlbumContextWrapper(
    state: AppState,
    album: SubsonicAlbumEntry,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isLocal = album.id.startsWith(LocalCatalog.LOCAL_ALBUM_PREFIX)
    var pickSongs by remember { mutableStateOf<List<SubsonicChildSong>?>(null) }

    suspend fun loadTracks(): List<SubsonicChildSong> = when {
        isLocal -> state.local.desktopTracksOf(album.id)
        state.offlineMode -> state.downloads.cachedSongs().filter { (it.albumId ?: it.album.orEmpty()) == album.id }
        else -> state.client.getAlbum(album.id).getOrDefault(emptyList())
    }

    ContextMenuBox(
        menuContent = { dismiss ->
            GlassMenuItem(str("play"), PhosphorIcons.Fill.Play) {
                dismiss()
                scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.player.playQueue(it) } }
            }
            GlassMenuItem(str("desktop_play_next"), PhosphorIcons.Regular.Queue) {
                dismiss()
                scope.launch { loadTracks().asReversed().forEach { state.player.playNext(it) } }
            }
            GlassMenuItem(str("add_to_queue"), PhosphorIcons.Regular.ListPlus) {
                dismiss()
                scope.launch { loadTracks().forEach { state.player.addToQueue(it) } }
            }
            GlassMenuItem(str("add_to_playlist"), PhosphorIcons.Regular.Plus) {
                dismiss()
                scope.launch { pickSongs = loadTracks() }
            }
            GlassMenuItem(str("go_to_album"), PhosphorIcons.Regular.Disc) {
                dismiss()
                if (isLocal) state.open(Screen.LocalAlbum(album.id, album.name))
                else state.open(Screen.Album(album.id, album.name))
            }
            album.artistId?.let { aid ->
                GlassMenuItem(str("go_to_artist"), PhosphorIcons.Regular.User) {
                    dismiss()
                    if (isLocal) state.open(Screen.LocalArtist(aid, album.artist.orEmpty()))
                    else state.open(Screen.Artist(aid, album.artist.orEmpty()))
                }
            }
            if (!isLocal) {
                GlassMenuItem(str("download"), PhosphorIcons.Regular.DownloadSimple) {
                    dismiss()
                    scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.download(it) } }
                }
                GlassMenuItem(str("remove_download"), PhosphorIcons.Regular.XCircle) {
                    dismiss()
                    scope.launch { loadTracks().forEach { state.downloads.remove(it.id) } }
                }
            } else {
                GlassMenuItem(str("desktop_show_in_folder"), PhosphorIcons.Regular.FolderOpen) {
                    dismiss()
                    scope.launch {
                        val firstPath = loadTracks().firstOrNull()?.path
                        DropTargetHandler.openInFolder(state, firstPath)
                    }
                }
            }
            GlassMenuItem(str("desktop_radio_from_song"), PhosphorIcons.Regular.Broadcast) {
                dismiss()
                scope.launch { loadTracks().firstOrNull()?.let(state::startRadio) }
            }
        },
        content = content,
    )

    pickSongs?.let { songs ->
        if (songs.isNotEmpty()) {
            BatchPlaylistPickerDialog(state, songs, isLocal) { pickSongs = null }
        } else {
            pickSongs = null
        }
    }
}

@Composable
fun ArtistContextWrapper(
    state: AppState,
    artist: SubsonicArtistItem,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isLocal = artist.id.startsWith(LocalCatalog.LOCAL_ARTIST_PREFIX)
    var pickSongs by remember { mutableStateOf<List<SubsonicChildSong>?>(null) }

    suspend fun loadTracks(): List<SubsonicChildSong> = when {
        isLocal -> state.local.desktopTracksOf(artist.id)
        state.offlineMode -> state.downloads.cachedSongs().filter { (it.artistId ?: it.artist.orEmpty()) == artist.id }
        else -> {
            val detail = state.client.getArtist(artist.id).getOrNull()
            detail?.album.orEmpty().flatMap { al -> state.client.getAlbum(al.id).getOrDefault(emptyList()) }
        }
    }

    ContextMenuBox(
        menuContent = { dismiss ->
            GlassMenuItem(str("play"), PhosphorIcons.Fill.Play) {
                dismiss()
                scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.player.playQueue(it) } }
            }
            GlassMenuItem(str("desktop_play_next"), PhosphorIcons.Regular.Queue) {
                dismiss()
                scope.launch { loadTracks().asReversed().forEach { state.player.playNext(it) } }
            }
            GlassMenuItem(str("add_to_queue"), PhosphorIcons.Regular.ListPlus) {
                dismiss()
                scope.launch { loadTracks().forEach { state.player.addToQueue(it) } }
            }
            GlassMenuItem(str("add_to_playlist"), PhosphorIcons.Regular.Plus) {
                dismiss()
                scope.launch { pickSongs = loadTracks() }
            }
            GlassMenuItem(str("go_to_artist"), PhosphorIcons.Regular.User) {
                dismiss()
                if (isLocal) state.open(Screen.LocalArtist(artist.id, artist.name))
                else state.open(Screen.Artist(artist.id, artist.name))
            }
            if (!isLocal) {
                GlassMenuItem(str("download"), PhosphorIcons.Regular.DownloadSimple) {
                    dismiss()
                    scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.download(it) } }
                }
            } else {
                GlassMenuItem(str("desktop_show_in_folder"), PhosphorIcons.Regular.FolderOpen) {
                    dismiss()
                    scope.launch { DropTargetHandler.openInFolder(state, loadTracks().firstOrNull()?.path) }
                }
            }
            GlassMenuItem(str("desktop_radio_from_song"), PhosphorIcons.Regular.Broadcast) {
                dismiss()
                scope.launch { loadTracks().firstOrNull()?.let(state::startRadio) }
            }
        },
        content = content,
    )

    pickSongs?.let { songs ->
        if (songs.isNotEmpty()) {
            BatchPlaylistPickerDialog(state, songs, isLocal) { pickSongs = null }
        } else {
            pickSongs = null
        }
    }
}

@Composable
fun PlaylistContextWrapper(
    state: AppState,
    playlist: SubsonicPlaylistEntry,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isLocal = playlist.id.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX)
    val pinned = state.pins.any { it.id == playlist.id }

    suspend fun loadTracks(): List<SubsonicChildSong> =
        if (isLocal) state.local.desktopTracksOf(playlist.id)
        else state.client.getPlaylist(playlist.id).getOrDefault(emptyList())

    ContextMenuBox(
        menuContent = { dismiss ->
            GlassMenuItem(str("play"), PhosphorIcons.Fill.Play) {
                dismiss()
                scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.player.playQueue(it) } }
            }
            GlassMenuItem(str("desktop_play_next"), PhosphorIcons.Regular.Queue) {
                dismiss()
                scope.launch { loadTracks().asReversed().forEach { state.player.playNext(it) } }
            }
            GlassMenuItem(str("add_to_queue"), PhosphorIcons.Regular.ListPlus) {
                dismiss()
                scope.launch { loadTracks().forEach { state.player.addToQueue(it) } }
            }
            GlassMenuItem(str(if (pinned) "unpin" else "pin"), PhosphorIcons.Regular.PushPin) {
                dismiss()
                state.togglePin(playlist.id, playlist.name, playlist.coverArt)
            }
            if (!isLocal) {
                GlassMenuItem(str("download"), PhosphorIcons.Regular.DownloadSimple) {
                    dismiss()
                    scope.launch { loadTracks().takeIf { it.isNotEmpty() }?.let { state.download(it) } }
                }
            } else {
                GlassMenuItem(str("desktop_show_in_folder"), PhosphorIcons.Regular.FolderOpen) {
                    dismiss()
                    val m3uPath = state.local.playlistStore
                        .read(playlist.id.removePrefix(LocalCatalog.LOCAL_PLAYLIST_PREFIX))
                        ?.file
                        ?.toString()
                    DropTargetHandler.openInFolder(state, m3uPath)
                }
            }
            GlassMenuItem(str("desktop_radio_from_song"), PhosphorIcons.Regular.Broadcast) {
                dismiss()
                scope.launch { loadTracks().firstOrNull()?.let(state::startRadio) }
            }
        },
        content = content,
    )
}

@Composable
fun SongContextWrapper(
    state: AppState,
    song: SubsonicChildSong,
    onPlay: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val isLocal = song.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)
    var pickingPlaylist by remember { mutableStateOf(false) }

    ContextMenuBox(
        menuContent = { dismiss ->
            GlassMenuItem(str("play"), PhosphorIcons.Fill.Play) { dismiss(); onPlay() }
            GlassMenuItem(str("desktop_play_next"), PhosphorIcons.Regular.Queue) { dismiss(); state.player.playNext(song) }
            GlassMenuItem(str("add_to_queue"), PhosphorIcons.Regular.ListPlus) { dismiss(); state.player.addToQueue(song) }
            GlassMenuItem(str("add_to_playlist"), PhosphorIcons.Regular.Plus) { dismiss(); pickingPlaylist = true }
            song.albumId?.let { id ->
                GlassMenuItem(str("go_to_album"), PhosphorIcons.Regular.Disc) {
                    dismiss()
                    if (isLocal) state.open(Screen.LocalAlbum(id, song.album.orEmpty()))
                    else state.open(Screen.Album(id, song.album.orEmpty()))
                }
            }
            song.artistId?.let { id ->
                GlassMenuItem(str("go_to_artist"), PhosphorIcons.Regular.User) {
                    dismiss()
                    if (isLocal) state.open(Screen.LocalArtist(id, song.artist.orEmpty()))
                    else state.open(Screen.Artist(id, song.artist.orEmpty()))
                }
            }
            if (!isLocal) {
                if (state.downloads.has(song.id)) {
                    GlassMenuItem(str("remove_download"), PhosphorIcons.Regular.XCircle) { dismiss(); state.downloads.remove(song.id) }
                } else {
                    GlassMenuItem(str("download"), PhosphorIcons.Regular.DownloadSimple) { dismiss(); state.download(listOf(song)) }
                }
            } else {
                val trackPath = song.path ?: state.local.findById(song.id)?.path
                if (!trackPath.isNullOrBlank()) {
                    GlassMenuItem(str("desktop_show_in_folder"), PhosphorIcons.Regular.FolderOpen) {
                        dismiss()
                        DropTargetHandler.openInFolder(state, trackPath)
                    }
                }
            }
            GlassMenuItem(str("desktop_radio_from_song"), PhosphorIcons.Regular.Broadcast) { dismiss(); state.startRadio(song) }
        },
        content = content,
    )

    if (pickingPlaylist) {
        BatchPlaylistPickerDialog(state, listOf(song), isLocal) { pickingPlaylist = false }
    }
}
