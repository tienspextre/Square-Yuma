package dev.lelonio.square.desktop

import androidx.compose.runtime.mutableStateListOf
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Ten octave bands for the native 10-band parametric EQ (auto-headroom in C++).
 */
object Eq {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private fun g(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }
    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        "eq_flat" to g(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "eq_bass_boost" to g(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        "eq_treble_boost" to g(0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
        "eq_vocal" to g(-2, -2, -1, 1, 3, 4, 3, 1, 0, -1),
        "eq_rock" to g(4, 3, 2, 0, -1, 0, 2, 3, 4, 4),
        "eq_pop" to g(-1, 1, 3, 4, 3, 0, -1, -1, 0, 1),
        "eq_electronic" to g(5, 4, 1, 0, -2, 1, 0, 1, 4, 5),
        "eq_acoustic" to g(3, 3, 2, 1, 1, 1, 2, 2, 2, 1),
    )

    fun label(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
}

/**
 * Speed, pitch, reverb, karaoke, and loudness settings driven in real time by `square_win_native`.
 */
data class Fx(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val reverb: Float = 0f,
    val karaoke: Float = 0f,
    val loudness: Boolean = false,
) {
    companion object {
        val PRESETS = listOf(
            "preset_original" to Fx(),
            "desktop_slowed_reverb" to Fx(0.85f, 0.92f, 0.55f),
            "desktop_sped_up" to Fx(1.25f, 1.25f, 0f),
        )
    }
}

/** One song's listening in one month, as the phone's ListeningStore keeps it. */
@Serializable
data class Listen(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverArt: String? = null,
    val duration: Long = 0,
    val month: String,
    val ms: Long = 0,
    val plays: Int = 0,
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
