package dev.lelonio.square.desktop

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
import androidx.compose.runtime.mutableStateListOf
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
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The phone's equalizer as an ffmpeg filter chain: ten octave bands, each an
 * `equalizer` peak, with the loudest boost taken off the top so it cannot clip.
 */
object Eq {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private fun g(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }
    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        "Flat" to g(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Bass" to g(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        "Treble" to g(0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
        "Vocal" to g(-2, -2, -1, 1, 3, 4, 3, 1, 0, -1),
        "Rock" to g(4, 3, 2, 0, -1, 0, 2, 3, 4, 4),
        "Pop" to g(-1, 1, 3, 4, 3, 0, -1, -1, 0, 1),
        "Electronic" to g(5, 4, 1, 0, -2, 1, 0, 1, 4, 5),
        "Acoustic" to g(3, 3, 2, 1, 1, 1, 2, 2, 2, 1),
    )

    fun filter(gains: FloatArray): String? {
        if (gains.all { abs(it) < 0.05f }) return null
        fun f(x: Float) = "%.1f".format(Locale.ROOT, x)
        val headroom = gains.max().takeIf { it > 0f }?.let { "volume=-${f(it)}dB," }.orEmpty()
        return headroom + BANDS.indices.filter { abs(gains[it]) >= 0.05f }
            .joinToString(",") { "equalizer=f=${BANDS[it]}:t=o:w=1:g=${f(gains[it])}" }
    }

    fun label(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
}

/** One song's listening in one month, as the phone's ListeningStore keeps it. */
@Serializable
data class Listen(
    val id: String, val title: String, val artist: String? = null, val album: String? = null,
    val coverArt: String? = null, val duration: Long = 0, val month: String, val ms: Long = 0, val plays: Int = 0,
) {
    fun song() = SubsonicChildSong(id = id, title = title, artist = artist, album = album, coverArt = coverArt, duration = duration)
}

/** What was played, kept on this computer in ~/.square-yuma/listening.json. */
class ListeningStore {
    private val file = File(System.getProperty("user.home"), ".square-yuma/listening.json")
    private val json = Json { ignoreUnknownKeys = true }
    val entries = mutableStateListOf<Listen>().apply {
        runCatching { addAll(json.decodeFromString<List<Listen>>(file.readText())) }
    }

    fun add(song: SubsonicChildSong, month: String, ms: Long, play: Boolean) {
        val i = entries.indexOfFirst { it.id == song.id && it.month == month }
        val old = entries.getOrNull(i) ?: Listen(song.id, song.title, song.artist, song.album, song.coverArt, song.duration, month)
        val new = old.copy(ms = old.ms + ms, plays = old.plays + if (play) 1 else 0)
        if (i >= 0) entries[i] = new else entries.add(new)
    }

    fun save() {
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(entries.toList()))
        }
    }

    companion object {
        /** A listen counts as a play after this much of it, as on the phone. */
        const val PLAY_MS = 30_000L
    }
}

/** "Your month in music": the phone's monthly listening page. */
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
        LazyColumn(Modifier.widthIn(max = 980.dp).fillMaxSize(), contentPadding = PaddingValues(32.dp, 28.dp, 32.dp, BarClearance)) {
            item {
                Title("Your month in music")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)) {
                    Glyph(PhosphorIcons.Regular.CaretLeft, "Previous month", size = 20.dp) { month = month.minusMonths(1) }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)),
                        style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    if (month < YearMonth.now()) Glyph(PhosphorIcons.Regular.CaretRight, "Next month", size = 20.dp) { month = month.plusMonths(1) }
                }
                if (rows.isEmpty()) Text("Nothing played this month yet.", color = InkDim, style = MaterialTheme.typography.titleMedium)
            }
            if (rows.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 26.dp)) {
                        Stat("${rows.sumOf { it.ms } / 60_000}", "minutes")
                        Stat("${rows.sumOf { it.plays }}", "plays")
                        Stat("${rows.size}", "songs")
                        Stat("${artists.size}", "artists")
                    }
                    val top = songs.first()
                    SectionLabel("Song of the month")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 26.dp)
                        .clip(RoundedCornerShape(12.dp)).clickable { state.player.playQueue(songs.map { it.song() }) }) {
                        Artwork(state.cover(top.coverArt, 300), Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(top.title, style = MaterialTheme.typography.headlineMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(top.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = InkDim)
                            Text("${plays(top.plays)} · ${top.ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                        }
                    }
                    SectionLabel("Most played songs", Modifier.padding(bottom = 8.dp))
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
                        Text("${plays(l.plays)} · ${l.ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                    }
                }
                item { SectionLabel("Most played artists", Modifier.padding(top = 26.dp, bottom = 8.dp)) }
                itemsIndexed(artists.take(10), key = { _, a -> "a" + a.first }) { i, (name, ms, plays) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = InkDim, modifier = Modifier.width(28.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plays(plays)} · ${ms / 60_000} min", style = MaterialTheme.typography.bodyMedium, color = InkDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Ink.copy(alpha = 0.07f)).padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}

private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"
