package dev.lelonio.square.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SortAscending
import com.adamglin.phosphoricons.regular.SortDescending
import com.adamglin.phosphoricons.regular.Trash
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.DropZone
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.BackButton
import dev.lelonio.square.desktop.ui.components.BarClearance
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.MaxContent
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.TrackRow
import dev.lelonio.square.desktop.ui.components.dragReorderHandle
import dev.lelonio.square.desktop.ui.components.rememberDragReorderState
import dev.lelonio.square.desktop.ui.components.reorderableRow
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import kotlinx.coroutines.launch

private val SORT_KEYS = listOf("desktop_order", "title", "artist", "album", "desktop_added", "duration")

@Composable
fun Tracks(
    state: AppState,
    all: List<SubsonicChildSong>,
    title: String,
    subtitle: String,
    cover: String?,
    showAlbum: Boolean,
    playlistId: String? = null,
) {
    var find by remember(all) { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) }
    var descending by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDeletePl by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isLocalPlaylist = playlistId?.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX) == true
    val canDragReorder = isLocalPlaylist && sort == 0 && !descending && find.isBlank()

    var displayOrder by remember(all, find, sort, descending) {
        mutableStateOf(
            run {
                val q = find.trim().lowercase()
                val hits = if (q.isEmpty()) all else all.filter { s ->
                    listOfNotNull(s.title, s.artist, s.album).any { q in it.lowercase() }
                }
                val sorted = when (SORT_KEYS[sort]) {
                    "title" -> hits.sortedBy { it.title.lowercase() }
                    "artist" -> hits.sortedBy { it.artist.orEmpty().lowercase() }
                    "album" -> hits.sortedBy { it.album.orEmpty().lowercase() }
                    "desktop_added" -> hits.sortedByDescending { it.created.orEmpty() }
                    "duration" -> hits.sortedBy { it.duration }
                    else -> hits
                }
                if (descending) sorted.reversed() else sorted
            },
        )
    }

    val reorderState = rememberDragReorderState(
        itemCount = { displayOrder.size },
        canDragIndex = { canDragReorder },
        onMove = { from, to ->
            if (playlistId != null && isLocalPlaylist) {
                val list = displayOrder.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)
                displayOrder = list
                state.local.reorderPlaylist(playlistId, from, to)
            }
        },
    )

    DisposableEffect(isLocalPlaylist) {
        onDispose { state.playlistDropBounds = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (isLocalPlaylist) {
                    Modifier.onGloballyPositioned { state.playlistDropBounds = it.boundsInWindow() }
                } else {
                    Modifier
                },
            )
            .then(
                if (isLocalPlaylist && state.dropZoneHighlight == DropZone.LOCAL_PLAYLIST) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(Gutter, 18.dp, Gutter, BarClearance)) {
            item {
                BackButton(state)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 24.dp)) {
                    Artwork(state.cover(cover, 600), Modifier.size(220.dp).softShadow(RoundedCornerShape(14.dp), 24.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(26.dp))
                    Column {
                        Title(title)
                        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = InkDim, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(PhosphorIcons.Fill.Play, "play", accent = true) { state.player.playQueue(displayOrder) }
                            Pill(PhosphorIcons.Regular.Shuffle, "shuffle") { state.player.playQueue(displayOrder.shuffled()) }
                            val missing = all.count { !it.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX) && !state.downloads.has(it.id) }
                            if (missing > 0) Pill(PhosphorIcons.Regular.DownloadSimple, "download") { state.download(all) }
                            if (playlistId != null) {
                                val pinned = state.pins.any { it.id == playlistId }
                                Pill(PhosphorIcons.Regular.PushPin, if (pinned) "unpin" else "pin") { state.togglePin(playlistId, title, cover) }
                                Pill(PhosphorIcons.Regular.PencilSimple, "rename") { renaming = true }
                                Pill(PhosphorIcons.Regular.Trash, "delete") { confirmDeletePl = true }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    OutlinedTextField(
                        find,
                        { find = it },
                        Modifier.width(240.dp).onFocusChanged { state.typing = it.isFocused },
                        placeholder = { Text(str(if (showAlbum) "desktop_find_in_list" else "desktop_find_in_album")) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Segmented(SORT_KEYS, sort) { sort = it }
                    Glyph(
                        if (descending) PhosphorIcons.Regular.SortDescending else PhosphorIcons.Regular.SortAscending,
                        if (descending) "sort_descending" else "desktop_ascending",
                        size = 20.dp,
                    ) { descending = !descending }
                }
            }
            itemsIndexed(displayOrder, key = { i, s -> "${s.id}#$i" }) { i, s ->
                Row(
                    Modifier
                        .reorderableRow(reorderState, i)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (reorderState.draggingIndex == i) Ink.copy(alpha = 0.12f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canDragReorder) {
                        Icon(
                            PhosphorIcons.Regular.DotsSixVertical,
                            contentDescription = str("queue_reorder"),
                            tint = InkDim,
                            modifier = Modifier.dragReorderHandle(reorderState, i).padding(8.dp).size(18.dp),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        TrackRow(state, s, if (showAlbum) null else (s.track ?: (i + 1)), showAlbum, playlistId) {
                            state.player.playQueue(displayOrder, i)
                        }
                    }
                }
            }
        }
    }

    if (renaming && playlistId != null) {
        var newName by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(str("rename")) },
            text = { OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth().onFocusChanged { state.typing = it.isFocused }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty()) {
                        renaming = false
                        scope.launch {
                            runCatching {
                                if (isLocalPlaylist) state.local.renamePlaylist(playlistId, trimmed)
                                else state.subsonicCatalog.renamePlaylist("subsonic:playlist:$playlistId", trimmed)
                            }.onSuccess { state.generation++; state.say(trimmed) }
                                .onFailure { state.say("${it.message}") }
                        }
                    }
                }) { Text(str("rename")) }
            },
            dismissButton = { TextButton({ renaming = false }) { Text(str("cancel")) } },
        )
    }
    if (confirmDeletePl && playlistId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeletePl = false },
            title = { Text("${str("delete")} “$title”?") },
            confirmButton = {
                TextButton({
                    confirmDeletePl = false
                    scope.launch {
                        runCatching {
                            if (isLocalPlaylist) state.local.deletePlaylist(playlistId)
                            else state.subsonicCatalog.deletePlaylist("subsonic:playlist:$playlistId")
                        }.onSuccess { state.generation++; state.back(); state.say(title) }
                            .onFailure { state.say("${it.message}") }
                    }
                }) { Text(str("delete"), color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton({ confirmDeletePl = false }) { Text(str("cancel")) } },
        )
    }
}
