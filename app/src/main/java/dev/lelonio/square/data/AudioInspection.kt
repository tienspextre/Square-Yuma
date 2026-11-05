package dev.lelonio.square.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.fft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

/**
 * What a song's file really is: its FLAC header, and what is in its spectrum.
 *
 * Only for songs that are a file Square can open: the NAS (its original, not
 * the stream, which may be transcoded), the phone's own audio, and a Spotify
 * playlist's local entries. Spotify and YouTube stream something else.
 */
object AudioInspection {

    /** The file behind [uri], or null when there is none to read. */
    fun sourceFor(context: Context, uri: String, localFile: String? = null): Uri? {
        val app = context.applicationContext as? SquareApplication ?: return null
        return when {
            LocalLibrary.isLocal(uri) -> LocalLibrary.contentUri(uri)
            uri.startsWith(SUBSONIC) -> app.offlineTrackStore.getLocalUri(uri)
                ?: app.subsonicClient.buildDownloadUrl(uri.removePrefix(SUBSONIC))
                    .takeIf { it.isNotBlank() }?.let(Uri::parse)
            localFile != null -> if (localFile.startsWith("content://")) Uri.parse(localFile) else Uri.fromFile(java.io.File(localFile))
            else -> null
        }
    }

    data class Picture(val type: Int, val mime: String, val width: Int, val height: Int, val bytes: Int)

    data class Flac(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val minBlock: Int,
        val maxBlock: Int,
        val md5: String,
        val vendor: String?,
        val tags: List<Pair<String, String>>,
        val pictures: List<Picture>,
        /** Every metadata block, by name, with its size in bytes. */
        val blocks: List<Pair<String, Int>>,
        /** Where the audio frames begin: everything before is header. */
        val audioOffset: Long,
    ) {
        val durationMs: Long get() = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
    }

    /**
     * Reads a FLAC stream's metadata blocks, or null when it is not FLAC.
     * Skips an ID3v2 tag in front, which some taggers leave there.
     */
    fun readFlac(input: InputStream): Flac? {
        val data = DataInputStream(input.buffered())
        var offset = 0L
        val magic = ByteArray(4).also { data.readFully(it) }
        offset += 4
        if (String(magic, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val rest = ByteArray(6).also { data.readFully(it) }
            val size = (rest[2].toInt() and 0x7f shl 21) or (rest[3].toInt() and 0x7f shl 14) or
                (rest[4].toInt() and 0x7f shl 7) or (rest[5].toInt() and 0x7f)
            data.skipFully(size.toLong())
            data.readFully(magic)
            offset += 6 + size + 4
        }
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null

        var info: ByteArray? = null
        var vendor: String? = null
        val tags = mutableListOf<Pair<String, String>>()
        val pictures = mutableListOf<Picture>()
        val blocks = mutableListOf<Pair<String, Int>>()
        do {
            val head = data.readInt()
            offset += 4
            val last = head ushr 31 == 1
            val type = head ushr 24 and 0x7f
            val length = head and 0xffffff
            offset += length
            blocks += (BLOCK_NAMES.getOrNull(type) ?: "TYPE $type") to length
            when (type) {
                0 -> info = ByteArray(length).also { data.readFully(it) }
                4 -> {
                    val block = ByteArray(length).also { data.readFully(it) }
                    val buf = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    fun string(): String {
                        val n = buf.int
                        return String(block, buf.position(), n, Charsets.UTF_8).also { buf.position(buf.position() + n) }
                    }
                    vendor = string()
                    repeat(buf.int) {
                        val entry = string()
                        tags += entry.substringBefore('=').uppercase() to entry.substringAfter('=', "")
                    }
                }
                6 -> {
                    val pictureType = data.readInt()
                    val mime = ByteArray(data.readInt()).also { data.readFully(it) }
                    data.skipFully(data.readInt().toLong())
                    val width = data.readInt()
                    val height = data.readInt()
                    data.skipFully(8)
                    val bytes = data.readInt()
                    data.skipFully(bytes.toLong())
                    pictures += Picture(pictureType, String(mime, Charsets.ISO_8859_1), width, height, bytes)
                }
                else -> data.skipFully(length.toLong())
            }
        } while (!last)

        val s = info ?: return null
        fun u(i: Int) = s[i].toLong() and 0xff
        return Flac(
            minBlock = (u(0) shl 8 or u(1)).toInt(),
            maxBlock = (u(2) shl 8 or u(3)).toInt(),
            sampleRate = (u(10) shl 12 or (u(11) shl 4) or (u(12) shr 4)).toInt(),
            channels = ((u(12) shr 1) and 0x7).toInt() + 1,
            bitsPerSample = (((u(12) and 1) shl 4) or (u(13) shr 4)).toInt() + 1,
            totalSamples = ((u(13) and 0xf) shl 32) or (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17),
            md5 = s.copyOfRange(18, 34).joinToString("") { "%02x".format(it) },
            vendor = vendor,
            tags = tags,
            pictures = pictures,
            blocks = blocks,
            audioOffset = offset,
        )
    }

    /** What the header says, and how big the whole file is (null when the source does not say). */
    data class Header(val flac: Flac?, val fileBytes: Long?, val mime: String?)

    suspend fun header(context: Context, source: Uri): Header = withContext(Dispatchers.IO) {
        if (source.scheme == "http" || source.scheme == "https") {
            val connection = URL(source.toString()).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val size = connection.contentLengthLong.takeIf { it > 0 }
                Header(runCatching { readFlac(connection.inputStream) }.getOrNull(), size, connection.contentType)
            } finally {
                connection.disconnect()
            }
        } else {
            val resolver = context.contentResolver
            val size = runCatching { resolver.openAssetFileDescriptor(source, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it > 0 }
            val flac = runCatching { resolver.openInputStream(source)?.use(::readFlac) }.getOrNull()
            Header(flac, size, runCatching { resolver.getType(source) }.getOrNull())
        }
    }

    /**
     * The song's spectrum: [columns] slices across its length, each [ROWS]
     * bands from 0 Hz (row 0) to half the sample rate, in dB; and the whole
     * song's average, at full resolution, for [verdict].
     */
    class Spectrum(
        val sampleRate: Int,
        val columns: Int,
        val db: Array<FloatArray>,
        val average: FloatArray,
    )

    suspend fun spectrum(
        context: Context,
        source: Uri,
        columns: Int = 400,
        onProgress: (Float) -> Unit,
    ): Spectrum = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val power = Array(columns) { FloatArray(ROWS) }
            val counts = IntArray(columns)
            val average = DoubleArray(WINDOW / 2)
            var windows = 0
            val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1))).toFloat() }
            val re = FloatArray(WINDOW)
            val im = FloatArray(WINDOW)
            val pending = FloatArray(WINDOW)
            var filled = 0
            var frame = 0L
            var float = false
            val totalFrames = durationUs * sampleRate / 1_000_000
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            fun analyse() {
                for (i in 0 until WINDOW) {
                    re[i] = pending[i] * hann[i]
                    im[i] = 0f
                }
                fft(re, im, false)
                val column = ((frame - WINDOW).coerceAtLeast(0) * columns / totalFrames.coerceAtLeast(1))
                    .toInt().coerceIn(0, columns - 1)
                val row = power[column]
                for (bin in 0 until WINDOW / 2) {
                    val p = re[bin] * re[bin] + im[bin] * im[bin]
                    row[bin * ROWS / (WINDOW / 2)] += p
                    average[bin] += p.toDouble()
                }
                counts[column]++
                windows++
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (out >= 0) {
                    val buffer = decoder.getOutputBuffer(out)!!.order(java.nio.ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    // Mixed to mono: a cutoff is in both channels or it is not a cutoff.
                    if (float) {
                        val samples = buffer.asFloatBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() }
                            pending[filled++] = sum / channels
                            frame++
                            if (filled == WINDOW) { analyse(); filled = 0 }
                        }
                    } else {
                        val samples = buffer.asShortBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() / 32768f }
                            pending[filled++] = sum / channels
                            frame++
                            if (filled == WINDOW) { analyse(); filled = 0 }
                        }
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            onProgress(1f)

            val db = Array(columns) { c ->
                FloatArray(ROWS) { r -> decibels(power[c][r] / max(1, counts[c]) / (WINDOW / 2 / ROWS)) }
            }
            // Columns no window landed in (a song shorter than the image is
            // wide) take the one before.
            for (c in 1 until columns) if (counts[c] == 0) db[c] = db[c - 1]
            Spectrum(sampleRate, columns, db, FloatArray(WINDOW / 2) { decibels((average[it] / max(1, windows)).toFloat()) })
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    sealed interface Verdict {
        /** Content reaches up to the top of the band, or fades out with no wall. */
        data object Lossless : Verdict
        /** A hi-res file whose content stops where a CD's or a lossy file's would. */
        data class Upsampled(val cutoffHz: Int) : Verdict
        /** A brick wall where an encoder of about [kbps] would put one. */
        data class Lossy(val cutoffHz: Int, val kbps: String) : Verdict
        data object TooQuiet : Verdict
    }

    /**
     * Reads the average spectrum for the wall a lossy encoder leaves.
     *
     * MP3 and AAC throw away everything above a frequency set by their
     * bitrate, so a file decoded from one has a cliff there and nothing
     * above. A real recording fades out instead, or carries on to the top.
     * ponytail: a heuristic, like Spek's eye or Fakin' The Funk's; a
     * recording mastered with a steep low-pass reads as lossy.
     */
    fun verdict(averageDb: FloatArray, sampleRate: Int): Verdict {
        val bins = averageDb.size
        val hzPerBin = sampleRate / 2f / bins
        val span = (500 / hzPerBin).toInt().coerceAtLeast(1)
        val smooth = FloatArray(bins) { i ->
            var sum = 0f
            var n = 0
            for (k in max(0, i - span / 2)..minOf(bins - 1, i + span / 2)) { sum += averageDb[k]; n++ }
            sum / n
        }
        val loudest = smooth.maxOrNull() ?: return Verdict.TooQuiet
        if (loudest < -90f) return Verdict.TooQuiet
        val top = (bins * 0.97).toInt()
        val floor = smooth.copyOfRange(top, bins).minOrNull() ?: loudest
        // The highest band that still stands clear of the floor.
        val threshold = max(floor + 15f, loudest - 100f)
        val last = (bins - 1 downTo 0).firstOrNull { smooth[it] > threshold } ?: return Verdict.TooQuiet
        val cutoffHz = (last * hzPerBin).toInt()
        val nyquist = sampleRate / 2
        // A wall drops at least 25 dB inside a kilohertz; a fade does not.
        val below = smooth[(last - (1000 / hzPerBin).toInt()).coerceAtLeast(0)]
        val above = smooth[(last + (500 / hzPerBin).toInt()).coerceAtMost(bins - 1)]
        val wall = below - above >= 25f
        return when {
            !wall || cutoffHz >= nyquist * 0.95f -> Verdict.Lossless
            sampleRate > 48_000 && cutoffHz <= 24_500 -> Verdict.Upsampled(cutoffHz)
            // LAME's own low-pass for each bitrate, which most lossy files carry.
            cutoffHz < 17_200 -> Verdict.Lossy(cutoffHz, "≤128")
            cutoffHz < 19_000 -> Verdict.Lossy(cutoffHz, "≈160–192")
            cutoffHz < 20_000 -> Verdict.Lossy(cutoffHz, "≈256")
            cutoffHz < 21_000 -> Verdict.Lossy(cutoffHz, "≈320")
            else -> Verdict.Lossless
        }
    }

    private fun decibels(power: Float) = 10f * log10(power + 1e-12f)

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { read().takeIf { it >= 0 } ?: throw java.io.EOFException(); left-- } else left -= skipped
        }
    }

    const val ROWS = 256
    private const val WINDOW = 4096
    private const val SUBSONIC = "subsonic:track:"
    private val BLOCK_NAMES = listOf("STREAMINFO", "PADDING", "APPLICATION", "SEEKTABLE", "VORBIS_COMMENT", "CUESHEET", "PICTURE")
}
