package dev.lelonio.square.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** A song kept on this computer, as the phone's OfflineTrackStore keeps one. */
@Serializable
data class Kept(val song: SubsonicChildSong, val path: String)

/**
 * The phone's offline downloads for the desktop: the server's original file
 * (download.view, no transcode) under ~/Music/Square-Yuma/Artist/Album, and an
 * index beside the listening stats. A kept song plays from disk, so it also
 * plays with the server away.
 */
class DownloadStore {
    private val index = File(System.getProperty("user.home"), ".square-yuma/downloads.json")
    private val json = Json { ignoreUnknownKeys = true }
    val root = File(System.getProperty("user.home"), "Music/Square-Yuma")
    val kept = mutableStateListOf<Kept>().apply {
        runCatching { addAll(json.decodeFromString<List<Kept>>(index.readText()).filter { File(it.path).isFile }) }
    }
    /** Songs on their way down, to 0..1. */
    val progress = mutableStateMapOf<String, Float>()

    fun fileFor(id: String): File? = kept.firstOrNull { it.song.id == id }?.let { File(it.path) }?.takeIf { it.isFile }
    fun has(id: String) = kept.any { it.song.id == id }

    suspend fun download(song: SubsonicChildSong, url: String): Result<File> = withContext(Dispatchers.IO) {
        if (has(song.id)) return@withContext Result.success(fileFor(song.id)!!)
        progress[song.id] = 0f
        runCatching {
            fun clean(s: String?) = (s ?: "Unknown").replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifBlank { "Unknown" }
            val name = buildString {
                song.track?.let { append("%02d ".format(it)) }
                append(clean(song.title)); append('.'); append(song.suffix ?: "mp3")
            }
            val file = File(root, "${clean(song.artist)}/${clean(song.album)}/$name")
            file.parentFile.mkdirs()
            val part = File(file.path + ".part")
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body
                val total = body.contentLength().takeIf { it > 0 } ?: song.size
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress[song.id] = (done.toFloat() / total).coerceAtMost(1f)
                        }
                    }
                }
            }
            if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
            withContext(Dispatchers.Main) { kept.add(Kept(song, file.path)); save() }
            file
        }.also { progress.remove(song.id) }
    }

    fun remove(id: String) {
        kept.filter { it.song.id == id }.forEach { File(it.path).delete() }
        kept.removeAll { it.song.id == id }
        save()
    }

    fun removeAll() {
        kept.forEach { File(it.path).delete() }
        kept.clear()
        save()
    }

    fun bytes(): Long = kept.sumOf { File(it.path).length() }

    private fun save() {
        runCatching {
            index.parentFile.mkdirs()
            index.writeText(json.encodeToString(kept.toList()))
        }
    }
}

/** What Inspect found: what the file says it is, and what its spectrum says it is. */
data class Inspection(val facts: List<Pair<String, String>>, val cutoffHz: Int?, val verdict: String)

/**
 * The phone's Inspect: the stream's own description from ffmpeg, then the
 * highest frequency that still carries energy. MP3/AAC encoders cut everything
 * above ~16–20 kHz, so a "lossless" file whose spectrum stops there was made
 * from a lossy one.
 */
suspend fun inspect(song: SubsonicChildSong, source: String): Inspection = withContext(Dispatchers.IO) {
    val facts = mutableListOf<Pair<String, String>>()
    song.suffix?.let { facts += "Format" to it.uppercase() }
    song.contentType?.let { facts += "Type" to it }
    song.bitRate?.let { facts += "Bit rate" to "$it kbps" }
    if (song.size > 0) facts += "Size" to "%.1f MB".format(song.size / 1_048_576.0)
    song.path?.let { facts += "Path" to it }

    val probe = ProcessBuilder(DesktopPlayer.ffmpegPath, "-hide_banner", "-nostdin", "-t", "0.1", "-i", source, "-f", "null", "-")
        .redirectErrorStream(true).start()
    val text = probe.inputStream.bufferedReader().readText()
    probe.waitFor()
    Regex("""Audio: ([^\n]+)""").find(text)?.groupValues?.get(1)?.let { facts += "Stream" to it.trim() }
    val rate = Regex("""(\d{4,6}) Hz""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 44_100
    Regex("""(?m)^\s+(TITLE|ARTIST|ALBUM|DATE|GENRE|TRACK)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).findAll(text)
        .distinctBy { it.groupValues[1].lowercase() }.take(8)
        .forEach { facts += it.groupValues[1].lowercase().replaceFirstChar(Char::uppercase) to it.groupValues[2].trim() }

    // 20 s of mono from the middle of the song at the file's own rate.
    val start = (song.duration / 2 - 10).coerceAtLeast(0)
    val pcm = ProcessBuilder(
        DesktopPlayer.ffmpegPath, "-nostdin", "-loglevel", "error", "-ss", start.toString(), "-t", "20", "-i", source,
        "-ac", "1", "-ar", rate.toString(), "-f", "s16le", "-",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start().inputStream.readBytes()
    val cutoff = spectralCutoff(pcm, rate)
    val lossless = song.suffix?.lowercase() in setOf("flac", "wav", "alac", "aiff", "ape", "wv")
    val verdict = when {
        cutoff == null -> "Couldn't read the audio"
        !lossless -> "Lossy file (${song.suffix ?: "?"}), audio up to ${cutoff / 1000.0} kHz"
        cutoff >= rate / 2 * 0.9 || cutoff >= 20_500 -> "Looks lossless: audio reaches ${"%.1f".format(cutoff / 1000.0)} kHz"
        else -> "Likely upsampled from a lossy source: audio stops at ${"%.1f".format(cutoff / 1000.0)} kHz"
    }
    Inspection(facts, cutoff, verdict)
}

/** The highest frequency whose average level is within 70 dB of the loudest band; null for too little audio. */
internal fun spectralCutoff(pcm: ByteArray, rate: Int, n: Int = 4096): Int? {
    val samples = pcm.size / 2
    if (samples < n * 4) return null
    val power = DoubleArray(n / 2)
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    var frames = 0
    var at = 0
    while (at + n <= samples && frames < 200) {
        for (i in 0 until n) {
            val s = ((pcm[2 * (at + i) + 1].toInt() shl 8) or (pcm[2 * (at + i)].toInt() and 0xFF)).toDouble()
            re[i] = s * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
            im[i] = 0.0
        }
        fft(re, im)
        for (k in 0 until n / 2) power[k] += re[k] * re[k] + im[k] * im[k]
        frames++
        at += n
    }
    val db = power.map { 10 * log10(it / frames + 1e-9) }
    val peak = db.drop(4).max()
    val k = db.indices.last { db[it] > peak - 70 }
    return (k.toLong() * rate / n).toInt()
}

private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
    }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len
        for (i in 0 until n step len) {
            for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
        }
        len = len shl 1
    }
}

/** A tone at [hz] must cut off just above itself: the smallest check that the FFT and the cutoff agree. */
internal fun selfCheck() {
    val rate = 44_100
    val pcm = ByteArray(rate * 2 * 2)
    for (i in 0 until rate * 2) {
        val v = (sin(2 * PI * 5_000 * i / rate) * 12_000).toInt()
        pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte()
    }
    val c = spectralCutoff(pcm, rate)!!
    check(c in 5_000..9_000) { "cutoff $c" }
}
