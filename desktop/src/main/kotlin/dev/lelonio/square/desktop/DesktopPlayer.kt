package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Plays a queue of Subsonic songs: ffmpeg decodes each stream (any format the
 * server holds, FLAC included) to 44.1 kHz s16 PCM, and one thread mixes it into
 * a Java Sound line. Two decoders run at once only during a crossfade, which is
 * an equal-power fade of [crossfadeSeconds] — not the Android app's beat-matched
 * automix.
 */
class DesktopPlayer(private val streamUrl: (SubsonicChildSong) -> String) {
    val queue = MutableStateFlow<List<SubsonicChildSong>>(emptyList())
    val index = MutableStateFlow(-1)
    val current = MutableStateFlow<SubsonicChildSong?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val volume = MutableStateFlow(0.8f)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(RepeatMode.OFF)
    val error = MutableStateFlow<String?>(null)
    @Volatile var crossfadeSeconds = 6

    private var unshuffled: List<SubsonicChildSong> = emptyList()

    private val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, RATE * FRAME / 4) // 250 ms
    }

    private val lock = Object()
    private var deck: Deck? = null
    private var tail: Deck? = null // the song fading out under [deck]
    private var tailFadeFrames = 1L

    init {
        thread(isDaemon = true, name = "square-audio") { loop() }
    }

    fun playQueue(songs: List<SubsonicChildSong>, start: Int = 0) {
        unshuffled = songs
        shuffle.value = false
        queue.value = songs
        play(start)
    }

    fun play(i: Int, atMs: Long = 0L) = synchronized(lock) {
        val song = queue.value.getOrNull(i) ?: return
        tail?.close(); tail = null
        deck?.close()
        deck = Deck(song, i, atMs)
        index.value = i
        current.value = song
        positionMs.value = atMs
        error.value = null
        line.flush()
        setPlaying(true)
    }

    fun toggle() = synchronized(lock) {
        when {
            deck != null -> setPlaying(!isPlaying.value)
            queue.value.isNotEmpty() -> play(index.value.coerceAtLeast(0), positionMs.value)
        }
    }

    fun seek(ms: Long) {
        val i = index.value
        if (i >= 0) play(i, ms.coerceIn(0L, durationMs())).also { if (!isPlaying.value) setPlaying(false) }
    }

    fun next() = nextIndex(auto = false)?.let { play(it) } ?: Unit

    fun previous() {
        if (positionMs.value > 3_000 || index.value <= 0) seek(0) else play(index.value - 1)
    }

    fun toggleShuffle() = synchronized(lock) {
        val now = current.value
        val on = !shuffle.value
        val list = if (on) {
            unshuffled = queue.value
            listOfNotNull(now) + queue.value.filter { it !== now }.shuffled()
        } else unshuffled
        queue.value = list
        shuffle.value = on
        val i = list.indexOfFirst { it === now }
        index.value = i
        deck?.queueIndex = i
    }

    fun cycleRepeat() {
        repeat.value = RepeatMode.entries[(repeat.value.ordinal + 1) % RepeatMode.entries.size]
    }

    fun durationMs(): Long = (current.value?.duration ?: 0L) * 1000

    private fun setPlaying(on: Boolean) = synchronized(lock) {
        isPlaying.value = on
        if (on) line.start() else line.stop()
        lock.notifyAll()
    }

    private fun nextIndex(auto: Boolean): Int? {
        val i = index.value
        return when {
            auto && repeat.value == RepeatMode.ONE -> i
            i + 1 < queue.value.size -> i + 1
            repeat.value != RepeatMode.OFF && queue.value.isNotEmpty() -> 0
            else -> null
        }
    }

    private fun loop() {
        val a = ByteArray(CHUNK)
        val b = ByteArray(CHUNK)
        val out = ByteArray(CHUNK)
        while (true) {
            val (d, t) = synchronized(lock) {
                while (deck == null || !isPlaying.value) lock.wait()
                deck!! to tail
            }
            // Read outside the lock: a network stall must not freeze the UI's commands.
            val n = d.read(a)
            val m = t?.read(b) ?: -1
            synchronized(lock) {
                if (deck !== d || tail !== t) return@synchronized // seeked or skipped meanwhile
                if (n <= 0) {
                    onDeckEnded(d)
                    return@synchronized
                }
                val gain = volume.value * volume.value
                val fade = t?.let { ((t.framesRead - t.fadeFrom).toDouble() / tailFadeFrames).coerceIn(0.0, 1.0) }
                val gainIn = fade?.let { sin(it * PI / 2).toFloat() } ?: 1f
                val gainOut = fade?.let { cos(it * PI / 2).toFloat() } ?: 0f
                for (i in 0 until n step 2) {
                    var s = sample(a, i) * gainIn
                    if (i < m) s += sample(b, i) * gainOut
                    val v = (s * gain).toInt().coerceIn(-32768, 32767)
                    out[i] = v.toByte()
                    out[i + 1] = (v shr 8).toByte()
                }
                if (t != null && (m <= 0 || fade!! >= 1.0)) { t.close(); tail = null }
                maybeStartCrossfade(d)
            }
            if (n > 0) {
                line.write(out, 0, n - n % FRAME)
                val buffered = (line.bufferSize - line.available()) / FRAME
                val pos = ((d.framesRead - buffered) * 1000 / RATE).coerceAtLeast(0)
                if (deck === d && kotlin.math.abs(pos - positionMs.value) >= 40) positionMs.value = pos
            }
        }
    }

    private fun maybeStartCrossfade(d: Deck) {
        val fadeMs = crossfadeSeconds * 1000L
        val durMs = d.song.duration * 1000
        if (fadeMs <= 0 || tail != null || durMs < fadeMs * 3) return
        if (d.framesRead * 1000 / RATE < durMs - fadeMs) return
        val n = nextIndex(auto = true)?.takeIf { repeat.value != RepeatMode.ONE } ?: return
        val song = queue.value[n]
        d.fadeFrom = d.framesRead
        tail = d
        tailFadeFrames = fadeMs * RATE / 1000
        deck = Deck(song, n, 0L)
        index.value = n
        current.value = song
    }

    private fun onDeckEnded(d: Deck) {
        d.close()
        if (d.framesRead == d.startFrames) error.value = "Couldn't play “${d.song.title}”"
        val n = if (error.value == null) nextIndex(auto = true) else null
        if (n == null) {
            deck = null
            setPlaying(false)
            positionMs.value = 0
        } else {
            deck = Deck(queue.value[n], n, 0L)
            index.value = n
            current.value = queue.value[n]
        }
    }

    /** One song's decoder: ffmpeg reading the stream from [atMs] and writing raw PCM to its stdout. */
    private inner class Deck(val song: SubsonicChildSong, var queueIndex: Int, atMs: Long) {
        private val process: Process? = runCatching {
            ProcessBuilder(
                ffmpegPath, "-nostdin", "-loglevel", "error",
                "-ss", "%.3f".format(java.util.Locale.ROOT, atMs / 1000.0),
                "-i", streamUrl(song),
                "-f", "s16le", "-ac", "2", "-ar", RATE.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.onFailure { error.value = "ffmpeg not found — install it (sudo apt install ffmpeg)" }.getOrNull()
        private val input: InputStream? = process?.inputStream?.buffered(1 shl 16)
        val startFrames = atMs * RATE / 1000
        var framesRead = startFrames
        var fadeFrom = 0L

        /** Fills [buf] as far as the stream allows; -1 once it has ended. */
        fun read(buf: ByteArray): Int {
            val stream = input ?: return -1
            var n = 0
            try {
                while (n < buf.size) {
                    val r = stream.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            } catch (_: java.io.IOException) {
            }
            framesRead += n / FRAME
            return if (n == 0) -1 else n
        }

        fun close() {
            process?.destroy()
        }
    }

    private companion object {
        const val RATE = 44_100
        const val FRAME = 4
        const val CHUNK = 4096
        fun sample(buf: ByteArray, i: Int): Float = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toFloat()

        /** A bundled ffmpeg beside the app wins, then the one on PATH. */
        val ffmpegPath: String by lazy {
            System.getProperty("compose.application.resources.dir")
                ?.let { java.io.File(it, "ffmpeg") }?.takeIf { it.canExecute() }?.path ?: "ffmpeg"
        }
    }
}
