package dev.lelonio.square.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.FolderPlus
import com.adamglin.phosphoricons.regular.Shuffle
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.BarClearance
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.ListEntry
import dev.lelonio.square.desktop.ui.components.MaxContent
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.TrackRow
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import java.nio.file.Paths
import javax.swing.JFileChooser

@Composable
fun LocalFoldersScreen(state: AppState) {
    val folders by state.local.folders.collectAsState()
    val tracks by state.local.tracks.collectAsState()
    val scanning by state.local.scanning.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var pathInput by remember { mutableStateOf<String?>(null) }

    val songs = remember(tracks) { tracks.map { it.toDesktopSong() } }
    val albums by produceState(emptyList<CatalogPlaylist>(), tracks) {
        value = state.local.savedAlbums()
    }
    val artists by produceState(emptyList<SearchItem>(), tracks) {
        value = state.local.followedArtists()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(Gutter, 20.dp, Gutter, BarClearance)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    Title("desktop_local_folders", Modifier.weight(1f))
                    Pill(PhosphorIcons.Regular.FolderPlus, "desktop_add_folder", accent = true) {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = str("desktop_add_folder")
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            scope.launch { state.local.addFolder(chooser.selectedFile.toPath()) }
                        } else {
                            pathInput = ""
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(PhosphorIcons.Regular.ArrowsClockwise, if (scanning) "desktop_scanning" else "desktop_rescan") {
                        scope.launch { state.local.scan() }
                    }
                }
            }
            item {
                Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (folders.isEmpty()) {
                        Text(str("desktop_no_local_folders"), color = InkDim, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        folders.forEach { folder ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ink.copy(alpha = 0.06f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(folder.toString(), color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton({ scope.launch { state.local.removeFolder(folder) } }) { Text(str("queue_remove")) }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    Text("${tracks.size} ${str("desktop_songs_unit")}", color = InkDim, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (songs.isNotEmpty()) {
                        Pill(PhosphorIcons.Fill.Play, "play", accent = true) { state.player.playQueue(songs) }
                        Spacer(Modifier.width(10.dp))
                        Pill(PhosphorIcons.Regular.Shuffle, "shuffle") { state.player.playQueue(songs.shuffled()) }
                        Spacer(Modifier.width(12.dp))
                    }
                    Segmented(listOf("tracks", "albums", "artists"), tab) { tab = it }
                }
            }
            when (tab) {
                0 -> itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                    TrackRow(state, s, null, true) { state.player.playQueue(songs, i) }
                }
                1 -> items(albums, key = { it.uri }) { a ->
                    ListEntry(state.cover(a.artworkUrl, 100), a.name, a.subtitle.orEmpty()) {
                        state.open(Screen.LocalAlbum(a.uri, a.name))
                    }
                }
                else -> items(artists, key = { it.uri }) { a ->
                    ListEntry(state.cover(a.artworkUrl, 100), a.title, a.subtitle, round = true) {
                        state.open(Screen.LocalArtist(a.uri, a.title))
                    }
                }
            }
        }
    }

    pathInput?.let { draft ->
        var text by remember { mutableStateOf(draft) }
        AlertDialog(
            onDismissRequest = { pathInput = null },
            title = { Text(str("desktop_add_folder")) },
            text = {
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().onFocusChanged { state.typing = it.isFocused },
                    label = { Text(str("desktop_folder_path")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton({
                    val p = runCatching { Paths.get(text.trim()) }.getOrNull()
                    pathInput = null
                    if (p != null) scope.launch {
                        if (!state.local.addFolder(p)) state.say(str("desktop_folder_not_found"))
                    }
                }) { Text(str("desktop_add_folder")) }
            },
            dismissButton = { TextButton({ pathInput = null }) { Text(str("cancel")) } },
        )
    }
}
