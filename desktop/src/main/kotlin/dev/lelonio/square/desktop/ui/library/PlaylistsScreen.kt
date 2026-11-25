package dev.lelonio.square.desktop.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.PushPin
import dev.lelonio.square.backend.subsonic.SubsonicPlaylistEntry
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Card
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.Grid
import dev.lelonio.square.desktop.ui.components.LayoutToggle
import dev.lelonio.square.desktop.ui.components.ListEntry
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.PlaylistContextWrapper
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.load
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(state: AppState) {
    val localPlaylists by state.local.playlistStore.playlists.collectAsState()
    val remoteResult = load(state, state.offlineMode) {
        if (state.offlineMode || !state.prefs.isConfigured()) Result.success(emptyList())
        else state.client.getPlaylists()
    }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    val remote = remoteResult?.getOrNull().orEmpty().map { p ->
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
    val pinned = state.pins.map { it.id }.toSet()
    val list = (localAsEntries + remote).sortedByDescending { it.id in pinned }

    Grid(header = {
        Row(verticalAlignment = Alignment.Bottom) {
            Title("playlists", Modifier.weight(1f))
            Pill(PhosphorIcons.Regular.Plus, "new_playlist", accent = true) { creating = true }
            Spacer(Modifier.width(10.dp))
            LayoutToggle(state)
        }
    }) {
        items(list, key = { it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { p ->
            val pin = p.id in pinned
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

    if (creating) {
        CreatePlaylistDialog(
            state = state,
            onDismiss = { creating = false },
            onCreate = { trimmed, asLocal ->
                creating = false
                scope.launch {
                    runCatching {
                        if (asLocal) state.local.createPlaylist(trimmed)
                        else state.subsonicCatalog.createPlaylist(trimmed)
                    }.onSuccess {
                        state.generation++
                        state.say(trimmed)
                    }.onFailure { state.say("${it.message}") }
                }
            },
        )
    }
}
