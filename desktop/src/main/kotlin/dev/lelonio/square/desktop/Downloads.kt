package dev.lelonio.square.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lelonio.square.backend.subsonic.OfflineTracks
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicArtistItem
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.ui.components.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** A song kept on this computer, keyed by Subsonic track id. */
@Serializable
data class Kept(
    val song: SubsonicChildSong,
    val path: String,
    val fileSize: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = downloadedAt,
)

/**
 * Offline downloads cache for Subsonic tracks:
 * - Single cache keyed by Subsonic track id (no duplicates across albums/playlists, concurrent requests coalesce)
 * - Original format (`download.view`, with `stream.view?format=raw` fallback if server disallows download.view)
 * - Resumable `.part` downloads via HTTP `Range`
 * - Configurable max cache size with LRU eviction
 * - Offline Mode flag and cached-track browsing queries
 * - Implements [OfflineTracks] for shared [dev.lelonio.square.backend.subsonic.SubsonicCatalog]
 */
class DownloadStore(
    private val indexPath: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "downloads.json"),
    rootPath: Path = Paths.get(System.getProperty("user.home"), "Music", "Square-Yuma"),
    private val httpClient: OkHttpClient = Http.client,
) : OfflineTracks {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Result<File>>>()

    val root: File = rootPath.toFile()
    val kept = mutableStateListOf<Kept>()

    /** Songs on their way down, to 0..1. */
    val progress = mutableStateMapOf<String, Float>()

    /** When true, UI lists only cached tracks (and Local folders) without server calls. */
    var offlineMode by mutableStateOf(false)

    /** Maximum cache size in bytes; 0 means unlimited. */
    var maxCacheBytes by mutableStateOf(0L)
        private set

    init {
        loadIndex()
    }

    private fun loadIndex() {
        runCatching {
            if (!Files.isRegularFile(indexPath)) return
            val raw = Files.readString(indexPath, StandardCharsets.UTF_8)
            val entries = json.decodeFromString<List<Kept>>(raw)
                .filter { Files.isRegularFile(Paths.get(it.path)) }
                .distinctBy { it.song.id }
            synchronized(lock) {
                kept.clear()
                kept.addAll(entries)
            }
        }
    }

    fun setCacheLimitBytes(limitBytes: Long) {
        maxCacheBytes = limitBytes.coerceAtLeast(0L)
        if (maxCacheBytes > 0L) {
            synchronized(lock) {
                evictIfNeededLocked(excludeId = null)
                saveLocked()
            }
        }
    }

    fun fileFor(id: String): File? {
        val entry = synchronized(lock) { kept.firstOrNull { it.song.id == id } } ?: return null
        val f = Paths.get(entry.path).toFile()
        if (!f.isFile) return null
        touch(id)
        return f
    }

    fun has(id: String): Boolean {
        val entry = synchronized(lock) { kept.firstOrNull { it.song.id == id } } ?: return false
        return Files.isRegularFile(Paths.get(entry.path))
    }

    fun touch(id: String) {
        synchronized(lock) {
            val idx = kept.indexOfFirst { it.song.id == id }
            if (idx >= 0) {
                kept[idx] = kept[idx].copy(lastAccessedAt = System.currentTimeMillis())
            }
        }
    }

    override fun allCatalogTracks(): List<CatalogTrack> = synchronized(lock) {
        kept.map { k ->
            CatalogTrack(
                uri = "subsonic:track:${k.song.id}",
                name = k.song.title,
                artist = k.song.artist.orEmpty().ifBlank { "Unknown Artist" },
                album = k.song.album.orEmpty().ifBlank { "Unknown Album" },
                durationMs = k.song.duration * 1000L,
                artworkUrl = k.song.coverArt,
                localFile = k.path,
                artists = k.song.artist?.takeIf { it.isNotBlank() }?.let { listOf(CatalogArtist(it, null)) } ?: emptyList(),
            )
        }
    }

    override fun localPath(trackUri: String): String? {
        val id = trackUri.removePrefix("subsonic:track:")
        return fileFor(id)?.absolutePath
    }

    /** Offline browsing helpers (when Offline Mode is active). */
    fun cachedSongs(): List<SubsonicChildSong> = synchronized(lock) {
        kept.map { it.song }.reversed()
    }

    fun cachedAlbums(): List<SubsonicAlbumEntry> = synchronized(lock) {
        kept.map { it.song }
            .groupBy { it.albumId ?: (it.album.orEmpty().ifBlank { "Unknown Album" }) }
            .map { (id, tracks) ->
                val first = tracks.first()
                SubsonicAlbumEntry(
                    id = id,
                    name = first.album.orEmpty().ifBlank { "Unknown Album" },
                    artist = first.artist,
                    artistId = first.artistId,
                    coverArt = first.coverArt,
                    songCount = tracks.size,
                    duration = tracks.sumOf { it.duration },
                    year = first.year,
                    genre = first.genre,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun cachedArtists(): List<SubsonicArtistItem> = synchronized(lock) {
        kept.map { it.song }
            .groupBy { it.artistId ?: (it.artist.orEmpty().ifBlank { "Unknown Artist" }) }
            .map { (id, tracks) ->
                val first = tracks.first()
                SubsonicArtistItem(
                    id = id,
                    name = first.artist.orEmpty().ifBlank { "Unknown Artist" },
                    coverArt = first.coverArt,
                    albumCount = tracks.map { it.album.orEmpty() }.distinct().size,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * Downloads [song] to the cache in its original format (resumable via `.part` + `Range`).
     * Deduplicated by [song.id]: concurrent or repeated requests return the single cached file.
     */
    suspend fun download(
        song: SubsonicChildSong,
        url: String,
        fallbackRawStreamUrl: String? = null,
    ): Result<File> {
        fileFor(song.id)?.let { return Result.success(it) }

        val existingJob = inflight[song.id]
        if (existingJob != null) {
            return existingJob.await()
        }

        val deferred = CompletableDeferred<Result<File>>()
        val winner = inflight.putIfAbsent(song.id, deferred)
        if (winner != null) {
            return winner.await()
        }

        return withContext(Dispatchers.IO) {
            try {
                val res = performResumableDownload(song, url, fallbackRawStreamUrl)
                deferred.complete(res)
                res
            } catch (t: Throwable) {
                val fail = Result.failure<File>(t)
                deferred.complete(fail)
                fail
            } finally {
                inflight.remove(song.id, deferred)
            }
        }
    }

    private fun performResumableDownload(
        song: SubsonicChildSong,
        primaryUrl: String,
        fallbackRawStreamUrl: String?,
    ): Result<File> {
        fileFor(song.id)?.let { return Result.success(it) }
        progress[song.id] = 0f

        return try {
            val targetPath = targetPathFor(song)
            Files.createDirectories(targetPath.parent)
            val partPath = targetPath.resolveSibling("${targetPath.fileName}.part")

            var activeUrl = primaryUrl
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val existingBytes = if (Files.isRegularFile(partPath)) Files.size(partPath) else 0L
                val reqBuilder = Request.Builder().url(activeUrl)
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                val done = httpClient.newCall(reqBuilder.build()).execute().use { r ->
                    if ((r.code == 403 || r.code == 404) && fallbackRawStreamUrl != null && activeUrl != fallbackRawStreamUrl) {
                        activeUrl = fallbackRawStreamUrl
                        return@use false
                    }
                    if (r.code == 416 && existingBytes > 0L) {
                        Files.deleteIfExists(partPath)
                        return@use false
                    }
                    if (!r.isSuccessful && r.code != 206) {
                        error("HTTP ${r.code}")
                    }

                    val append = r.code == 206 && existingBytes > 0L
                    val body = r.body
                    val contentLen = body.contentLength()
                    val total = when {
                        append && contentLen > 0 -> existingBytes + contentLen
                        contentLen > 0 -> contentLen
                        song.size > 0 -> song.size
                        else -> -1L
                    }

                    var written = if (append) existingBytes else 0L
                    if (total > 0) progress[song.id] = (written.toFloat() / total).coerceIn(0f, 1f)

                    body.byteStream().use { input ->
                        FileOutputStream(partPath.toFile(), append).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (total > 0) progress[song.id] = (written.toFloat() / total).coerceIn(0f, 1f)
                            }
                            out.flush()
                        }
                    }
                    true
                }
                if (done) break
            }

            if (!Files.isRegularFile(partPath)) error("Download produced no file")
            try {
                Files.move(partPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }

            val size = Files.size(targetPath)
            val now = System.currentTimeMillis()
            val entry = Kept(
                song = song,
                path = targetPath.toAbsolutePath().normalize().toString(),
                fileSize = size,
                downloadedAt = now,
                lastAccessedAt = now,
            )
            synchronized(lock) {
                kept.removeAll { it.song.id == song.id }
                kept.add(entry)
                evictIfNeededLocked(excludeId = song.id)
                saveLocked()
            }
            Result.success(targetPath.toFile())
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            progress.remove(song.id)
        }
    }

    private fun targetPathFor(song: SubsonicChildSong): Path {
        fun clean(s: String?) = (s ?: "Unknown").replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trimEnd('.').take(80).ifBlank { "Unknown" }
        val ext = (song.suffix?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "flac")
        val safeId = song.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifBlank { "track" }
        val fileName = buildString {
            song.track?.let { append("%02d ".format(it)) }
            append(clean(song.title))
            append(" [").append(safeId).append("].").append(ext)
        }
        return root.toPath().resolve(clean(song.artist)).resolve(clean(song.album)).resolve(fileName)
    }

    private fun evictIfNeededLocked(excludeId: String?) {
        val limit = maxCacheBytes
        if (limit <= 0L) return
        var total = kept.sumOf { currentSize(it) }
        if (total <= limit) return

        val victims = kept.filter { it.song.id != excludeId }
            .sortedBy { it.lastAccessedAt }
        for (v in victims) {
            if (total <= limit) break
            val sz = currentSize(v)
            runCatching { Files.deleteIfExists(Paths.get(v.path)) }
            kept.removeAll { it.song.id == v.song.id }
            total -= sz
        }
    }

    private fun currentSize(k: Kept): Long {
        val p = Paths.get(k.path)
        return if (Files.isRegularFile(p)) runCatching { Files.size(p) }.getOrDefault(k.fileSize) else k.fileSize
    }

    fun remove(id: String) {
        synchronized(lock) {
            kept.filter { it.song.id == id }.forEach { runCatching { Files.deleteIfExists(Paths.get(it.path)) } }
            kept.removeAll { it.song.id == id }
            saveLocked()
        }
    }

    fun removeAll() {
        synchronized(lock) {
            kept.forEach { runCatching { Files.deleteIfExists(Paths.get(it.path)) } }
            kept.clear()
            saveLocked()
        }
    }

    fun bytes(): Long = synchronized(lock) { kept.sumOf { currentSize(it) } }

    private fun saveLocked() {
        runCatching {
            Files.createDirectories(indexPath.parent)
            val tmp = indexPath.resolveSibling("${indexPath.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(kept.toList()), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** What Inspect found: what the file says it is, and what its spectrum says it is. */
data class Inspection(val facts: List<Pair<String, String>>, val cutoffHz: Int?, val verdict: String)

/**
 * The phone's Inspect: the stream's own format from `square_win_native`, then the
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
    song.artist?.let { facts += "Artist" to it }
    song.album?.let { facts += "Album" to it }

    val probe = runCatching { JSONObject(NativeEngine.probeTrack(source)) }.getOrNull()
    val rate = probe?.optInt("sampleRate", 44_100)?.takeIf { it > 0 } ?: 44_100
    if (probe != null && !probe.has("error")) {
        val codec = probe.optString("codec", "PCM")
        val ch = probe.optInt("channels", 2)
        val bits = probe.optInt("bitsPerSample", 16)
        facts += "Stream" to "$codec, $rate Hz, ${ch}ch, ${bits}-bit"
    }

    // 20 s of mono from the middle of the song at the file's own rate.
    val startMs = ((song.duration / 2 - 10).coerceAtLeast(0)) * 1000L
    val samples = NativeEngine.decodeToFloat(source, null, startMs, 20_000L, rate, 1)
    val cutoff = samples?.let { spectralCutoffFloat(it, rate) }
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
    val floats = FloatArray(samples) { i ->
        ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toFloat() / 32768f
    }
    return spectralCutoffFloat(floats, rate, n)
}

internal fun spectralCutoffFloat(samples: FloatArray, rate: Int, n: Int = 4096): Int? {
    if (samples.size < n * 4) return null
    val power = DoubleArray(n / 2)
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    var frames = 0
    var at = 0
    while (at + n <= samples.size && frames < 200) {
        for (i in 0 until n) {
            re[i] = samples[at + i].toDouble() * 32768.0 * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
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
