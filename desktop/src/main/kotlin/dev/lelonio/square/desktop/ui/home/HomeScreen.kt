package dev.lelonio.square.desktop.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.WifiSlash
import dev.lelonio.square.backend.subsonic.SubsonicMixes
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.AlbumCard
import dev.lelonio.square.desktop.ui.components.BarClearance
import dev.lelonio.square.desktop.ui.components.Card
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.Loaded
import dev.lelonio.square.desktop.ui.components.MaxContent
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.PlaylistContextWrapper
import dev.lelonio.square.desktop.ui.components.Shelf
import dev.lelonio.square.desktop.ui.components.SongContextWrapper
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.load
import dev.lelonio.square.ui.theme.InkDim

@Composable
fun HomeScreen(state: AppState) {
    val localTracks by state.local.tracks.collectAsState()
    if (state.offlineMode) {
        val kept = state.downloads.cachedSongs()
        val localSongs = remember(localTracks) { localTracks.map { it.toDesktopSong() } }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = BarClearance)) {
                item {
                    Row(Modifier.padding(horizontal = Gutter).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Title("home", Modifier.weight(1f))
                        Pill(PhosphorIcons.Regular.WifiSlash, "offline_mode", accent = true) { state.useOfflineMode(false) }
                    }
                }
                if (kept.isNotEmpty()) item("downloaded") {
                    Shelf("downloaded") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(kept.take(30), key = { _, s -> "d" + s.id }) { i, s ->
                                Box(Modifier.width(160.dp)) {
                                    val play = { state.player.playQueue(kept, i) }
                                    SongContextWrapper(state, s, play) { menuMod ->
                                        Card(s.title, s.artist, state.cover(s.coverArt), modifier = menuMod, onPlay = play, onClick = play)
                                    }
                                }
                            }
                            item {
                                Box(Modifier.width(160.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    TextButton({ state.open(Screen.Downloads) }) { Text(str("desktop_see_all")) }
                                }
                            }
                        }
                    }
                }
                if (localSongs.isNotEmpty()) item("local") {
                    Shelf("desktop_local_folders") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(localSongs.take(30), key = { _, s -> "l" + s.id }) { i, s ->
                                Box(Modifier.width(160.dp)) {
                                    val play = { state.player.playQueue(localSongs, i) }
                                    SongContextWrapper(state, s, play) { menuMod ->
                                        Card(s.title, s.artist, state.cover(s.coverArt), modifier = menuMod, onPlay = play, onClick = play)
                                    }
                                }
                            }
                        }
                    }
                }
                if (kept.isEmpty() && localSongs.isEmpty()) item("empty_offline") {
                    Text(str("desktop_nothing_downloaded"), color = InkDim, modifier = Modifier.padding(horizontal = Gutter))
                }
            }
        }
        return
    }
    val newest = load(state) { state.client.getAlbumList("newest", 24) }
    val recent = load(state) { state.client.getAlbumList("recent", 24) }
    val frequent = load(state) { state.client.getAlbumList("frequent", 24) }
    val random = load(state) { state.client.getAlbumList("random", 24) }
    val starred = load(state) { state.client.getAlbumList("starred", 24) }
    val quick = load(state) { state.client.getRandomSongs(24) }
    val mixes = load(state) { state.client.getGenres().map { SubsonicMixes.build(it) } }
    val playlists = load(state) { state.client.getPlaylists() }
    if (newest == null && localTracks.isEmpty()) {
        Loaded<Unit>(null) {}
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = BarClearance)) {
            item { Title("home", Modifier.padding(horizontal = Gutter).padding(bottom = 16.dp)) }
            if (newest?.isFailure == true) item("offline") {
                Text(str("desktop_server_unreachable_local"), color = InkDim, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }
            val kept = state.downloads.kept.map { it.song }.reversed()
            if (kept.isNotEmpty()) item("downloaded") {
                Shelf("downloaded") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        itemsIndexed(kept.take(30), key = { _, s -> "d" + s.id }) { i, s ->
                            Box(Modifier.width(160.dp)) {
                                val play = { state.player.playQueue(kept, i) }
                                SongContextWrapper(state, s, play) { menuMod ->
                                    Card(s.title, s.artist, state.cover(s.coverArt), modifier = menuMod, onPlay = play, onClick = play)
                                }
                            }
                        }
                        item {
                            Box(Modifier.width(160.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                TextButton({ state.open(Screen.Downloads) }) { Text(str("desktop_see_all")) }
                            }
                        }
                    }
                }
            }
            if (localTracks.isNotEmpty()) item("local_home") {
                val localSongs = remember(localTracks) { localTracks.take(30).map { it.toDesktopSong() } }
                Shelf("desktop_local_folders") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        itemsIndexed(localSongs, key = { _, s -> "lh" + s.id }) { i, s ->
                            Box(Modifier.width(160.dp)) {
                                val play = { state.player.playQueue(localSongs, i) }
                                SongContextWrapper(state, s, play) { menuMod ->
                                    Card(s.title, s.artist, state.cover(s.coverArt), modifier = menuMod, onPlay = play, onClick = play)
                                }
                            }
                        }
                        item {
                            Box(Modifier.width(160.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                TextButton({ state.root(Screen.LocalFolders) }) { Text(str("desktop_see_all")) }
                            }
                        }
                    }
                }
            }
            quick?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { songs ->
                item("quick") {
                    Shelf("desktop_quick_play") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(songs, key = { _, s -> "q" + s.id }) { i, s ->
                                Box(Modifier.width(160.dp)) {
                                    val play = { state.player.playQueue(songs, i) }
                                    SongContextWrapper(state, s, play) { menuMod ->
                                        Card(s.title, s.artist, state.cover(s.coverArt), modifier = menuMod, onPlay = play, onClick = play)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            mixes?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                item("mixes") {
                    Shelf("desktop_moods_genres") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(list, key = { it.key }) { m -> MixTile(m) { state.open(Screen.Mix(m.key, m.title)) } }
                        }
                    }
                }
            }
            playlists?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                item("playlists") {
                    Shelf("desktop_playlists_nas") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            items(list, key = { "p" + it.id }) { p ->
                                Box(Modifier.width(184.dp)) {
                                    PlaylistContextWrapper(state, p) { menuMod ->
                                        Card(p.name, "${p.songCount} ${str("desktop_songs_unit")}", state.cover(p.coverArt), modifier = menuMod) {
                                            state.open(Screen.Playlist(p.id, p.name))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            listOf(
                "desktop_recently_played" to recent,
                "recently_added" to newest,
                "desktop_most_played" to frequent,
                "desktop_discover" to random,
                "desktop_favorites" to starred,
            ).forEach { (titleKey, r) ->
                val albums = r?.getOrNull().orEmpty()
                if (albums.isNotEmpty()) item(titleKey) {
                    Shelf(titleKey) {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            items(albums, key = { it.id }) { a -> Box(Modifier.width(184.dp)) { AlbumCard(state, a) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixTile(m: SubsonicMixes.Mix, onClick: () -> Unit) {
    val hue = ((m.title.hashCode() % 360) + 360) % 360
    val colour = Color.hsv(hue.toFloat(), 0.55f, 0.62f)
    Column(
        Modifier.width(184.dp).height(96.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(colour, colour.copy(alpha = 0.55f))))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(14.dp),
    ) {
        Text(m.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(
            (if (m.kind == "MOOD") str("desktop_mood") + " · " else "") + "${m.songCount} " + str("desktop_songs_unit"),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}
