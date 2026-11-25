package dev.lelonio.square.desktop.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.backend.subsonic.SubsonicSearchResult3
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.AlbumCard
import dev.lelonio.square.desktop.ui.components.ArtistCard
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.Grid
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.TrackRow
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SearchScreen(state: AppState) {
    val query = state.searchQuery
    val result by produceState<Result<SubsonicSearchResult3>?>(null, query, state.generation, state.offlineMode) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        delay(280)
        val q = query.trim().lowercase()
        val localHits = state.local.desktopSongs().filter {
            it.title.lowercase().contains(q) || it.artist.orEmpty().lowercase().contains(q) || it.album.orEmpty().lowercase().contains(q)
        }
        if (state.offlineMode || !state.prefs.isConfigured()) {
            val cachedSongs = state.downloads.cachedSongs().filter {
                it.title.lowercase().contains(q) || it.artist.orEmpty().lowercase().contains(q) || it.album.orEmpty().lowercase().contains(q)
            }
            val cachedAlbums = state.downloads.cachedAlbums().filter { it.name.lowercase().contains(q) || it.artist.orEmpty().lowercase().contains(q) }
            val cachedArtists = state.downloads.cachedArtists().filter { it.name.lowercase().contains(q) }
            value = Result.success(SubsonicSearchResult3(artist = cachedArtists, album = cachedAlbums, song = localHits + cachedSongs))
        } else {
            val remote = state.client.search(query)
            value = remote.map { r -> r.copy(song = r.song + localHits) }
        }
        if (value?.getOrNull()?.let { it.song.isNotEmpty() || it.album.isNotEmpty() || it.artist.isNotEmpty() } == true) {
            delay(1500)
            state.rememberSearch(query)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val r = result?.getOrNull()
    Grid(header = {
        Column {
            Title("search", Modifier.padding(bottom = 14.dp))
            OutlinedTextField(
                query,
                { state.searchQuery = it },
                Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { state.typing = it.isFocused },
                placeholder = { Text(str("search_placeholder")) },
                textStyle = MaterialTheme.typography.titleMedium,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardActions = KeyboardActions.Default,
            )
            if (query.isBlank() && state.searchHistory.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) {
                    SectionLabel("desktop_recent_searches", Modifier.weight(1f))
                    TextButton({ state.clearSearches() }) { Text(str("clear_history")) }
                }
                state.searchHistory.forEach { q ->
                    SearchHistoryRow(
                        query = q,
                        onSelect = { state.searchQuery = q },
                        onDelete = { state.removeSearchHistory(q) },
                    )
                }
            }
            if (r != null && r.song.isNotEmpty()) {
                SectionLabel("tracks", Modifier.padding(top = 22.dp, bottom = 8.dp))
                r.song.take(10).forEachIndexed { i, s -> TrackRow(state, s, null, true) { state.player.playQueue(r.song, i) } }
            }
            if (r != null && r.artist.isNotEmpty()) {
                SectionLabel("artists", Modifier.padding(top = 22.dp, bottom = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(r.artist, key = { it.id }) { a -> Box(Modifier.width(150.dp)) { ArtistCard(state, a) } }
                }
            }
            if (r != null && r.album.isNotEmpty()) SectionLabel("albums", Modifier.padding(top = 22.dp))
            if (result?.isFailure == true) Text(str("desktop_server_unreachable"), color = InkDim, modifier = Modifier.padding(top = 16.dp))
        }
    }) {
        items(r?.album.orEmpty(), key = { it.id }) { AlbumCard(state, it) }
    }
}

@Composable
private fun SearchHistoryRow(
    query: String,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragX by remember(query) { mutableFloatStateOf(0f) }

    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Ink.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(hover)
            .pointerInput(query) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragX) > 90f) onDelete() else dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, d -> dragX = (dragX + d).coerceIn(-180f, 180f) },
                )
            }
            .clickable(onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Regular.ClockCounterClockwise, null, tint = InkDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(query, color = Ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (hovered) {
            Glyph(PhosphorIcons.Regular.X, "queue_remove", size = 14.dp, onClick = onDelete)
        }
    }
}
