package dev.lelonio.square.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.BarClearance
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.MaxContent
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MonthScreen(state: AppState) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val key = month.toString()
    val rows = state.listening.entries.filter { it.month == key }
    val songs = rows.sortedByDescending { it.ms }
    val artists = rows.groupBy { it.artist ?: "Unknown artist" }
        .map { (name, l) -> Triple(name, l.sumOf { it.ms }, l.sumOf { it.plays }) }
        .sortedByDescending { it.second }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(Gutter, 20.dp, Gutter, BarClearance)) {
            item {
                Title("recap_title")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)) {
                    Glyph(PhosphorIcons.Regular.CaretLeft, "recap_previous", size = 20.dp) { month = month.minusMonths(1) }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag(state.language.code))),
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    if (month < YearMonth.now()) Glyph(PhosphorIcons.Regular.CaretRight, "recap_next", size = 20.dp) { month = month.plusMonths(1) }
                }
                if (rows.isEmpty()) Text(str("recap_empty"), color = InkDim, style = MaterialTheme.typography.titleMedium)
            }
            if (rows.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 26.dp)) {
                        Stat("${rows.sumOf { it.ms } / 60_000}", "desktop_minutes_unit")
                        Stat("${rows.sumOf { it.plays }}", "desktop_plays_unit")
                        Stat("${rows.size}", "desktop_songs_unit")
                        Stat("${artists.size}", "desktop_artists_unit")
                    }
                    val top = songs.first()
                    SectionLabel("recap_song_of_month")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp, bottom = 26.dp)
                            .clip(RoundedCornerShape(12.dp)).clickable { state.player.playQueue(songs.map { it.song() }) },
                    ) {
                        Artwork(state.cover(top.coverArt, 300), Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(top.title, style = MaterialTheme.typography.headlineMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(top.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = InkDim)
                            Text("${top.plays} ${str("desktop_plays_unit")} · ${top.ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                        }
                    }
                    SectionLabel("recap_tracks", Modifier.padding(bottom = 8.dp))
                }
                itemsIndexed(songs.take(10), key = { _, l -> "s" + l.id }) { i, l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { state.player.playQueue(songs.map { it.song() }, i) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = InkDim, modifier = Modifier.width(28.dp))
                        Artwork(state.cover(l.coverArt, 100), Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(l.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1)
                        }
                        Text("${l.plays} ${str("desktop_plays_unit")} · ${l.ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                    }
                }
                item { SectionLabel("recap_artists", Modifier.padding(top = 26.dp, bottom = 8.dp)) }
                itemsIndexed(artists.take(10), key = { _, a -> "a" + a.first }) { i, (name, ms, plays) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = InkDim, modifier = Modifier.width(28.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$plays ${str("desktop_plays_unit")} · ${ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, labelKey: String) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Ink.copy(alpha = 0.07f)).padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(str(labelKey), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}
