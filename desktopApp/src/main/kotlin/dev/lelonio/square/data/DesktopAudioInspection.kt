package dev.lelonio.square.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.kc7bfi.jflac.FLACDecoder
import org.kc7bfi.jflac.PCMProcessor
import org.kc7bfi.jflac.metadata.StreamInfo
import org.kc7bfi.jflac.util.ByteData
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

object DesktopAudioInspection {

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
        val blocks: List<Pair<String, Int>>,
        val audioOffset: Long,
    ) {
        val durationMs: Long get() = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
    }

    data class Header(val flac: Flac?, val fileBytes: Long?, val mime: String?)

    class Spectrum(
        val sampleRate: Int,
        val columns: Int,
        val db: Array<FloatArray>,
        val average: FloatArray,
    )

    sealed interface Verdict {
        data object Lossless : Verdict
        data class Upsampled(val cutoffHz: Int) : Verdict
        data class Lossy(val cutoffHz: Int, val kbps: String) : Verdict
        data object TooQuiet : Verdict
    }

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
                    val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
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

    suspend fun header(sourceUrlOrPath: String): Header = withContext(Dispatchers.IO) {
        if (sourceUrlOrPath.startsWith("http://") || sourceUrlOrPath.startsWith("https://")) {
            val connection = URL(sourceUrlOrPath).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            try {
                val size = connection.contentLengthLong.takeIf { it > 0 }
                val mime = connection.contentType
                val flac = runCatching { readFlac(connection.inputStream) }.getOrNull()
                Header(flac, size, mime)
            } finally {
                connection.disconnect()
            }
        } else {
            val file = File(sourceUrlOrPath.removePrefix("file://"))
            if (!file.exists()) return@withContext Header(null, null, null)
            val size = file.length().takeIf { it > 0 }
            val flac = runCatching { FileInputStream(file).use(::readFlac) }.getOrNull()
            val ext = file.extension.lowercase()
            val mime = when (ext) {
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg", "opus" -> "audio/ogg"
                "m4a", "alac", "aac" -> "audio/mp4"
                else -> "audio/$ext"
            }
            Header(flac, size, mime)
        }
    }

    suspend fun spectrum(
        sourceUrlOrPath: String,
        expectedDurationMs: Long = 180_000L,
        expectedSampleRate: Int = 44100,
        columns: Int = 360,
        onProgress: (Float) -> Unit,
    ): Spectrum = withContext(Dispatchers.Default) {
        var sampleRate = expectedSampleRate.coerceAtLeast(8000)
        val totalFrames = (expectedDurationMs.coerceAtLeast(1000L) * sampleRate / 1000L).coerceAtLeast(WINDOW.toLong())

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

        fun analyse(dynamicTotalFrames: Long) {
            for (i in 0 until WINDOW) {
                re[i] = pending[i] * hann[i]
                im[i] = 0f
            }
            fft(re, im, false)
            val column = ((frame - WINDOW).coerceAtLeast(0) * columns / dynamicTotalFrames.coerceAtLeast(1))
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

        // Try fast FFmpeg decode first (handles HTTP streams + 24-bit FLAC + Opus/MP3 natively)
        val decodedByFfmpeg = runCatching {
            val process = ProcessBuilder(
                "ffmpeg",
                "-v", "error",
                "-i", sourceUrlOrPath.removePrefix("file://"),
                "-ac", "1",
                "-f", "f32le",
                "pipe:1",
            ).start()
            try {
                val input = BufferedInputStream(process.inputStream, 65536)
                val buf = ByteArray(16384)
                var leftover = 0
                var lastReportedWindow = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buf, leftover, buf.size - leftover)
                    if (read <= 0) break
                    val available = leftover + read
                    val floats = available / 4
                    val consumed = floats * 4
                    val floatBuf = ByteBuffer.wrap(buf, 0, consumed).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until floats) {
                        val s = floatBuf.getFloat(i * 4)
                        pending[filled++] = if (s.isFinite()) s else 0f
                        frame++
                        if (filled == WINDOW) {
                            analyse(totalFrames)
                            filled = 0
                            if (windows - lastReportedWindow >= 32) {
                                lastReportedWindow = windows
                                onProgress((frame.toFloat() / totalFrames).coerceIn(0f, 0.99f))
                            }
                        }
                    }
                    leftover = available - consumed
                    if (leftover > 0) {
                        System.arraycopy(buf, consumed, buf, 0, leftover)
                    }
                }
                windows > 0
            } finally {
                process.destroy()
            }
        }.getOrDefault(false)

        if (!decodedByFfmpeg) {
            // Pure JVM fallback using jFLAC or JavaSound SPI
            runCatching {
                val rawStream: InputStream = if (sourceUrlOrPath.startsWith("http://") || sourceUrlOrPath.startsWith("https://")) {
                    URL(sourceUrlOrPath).openStream()
                } else {
                    FileInputStream(File(sourceUrlOrPath.removePrefix("file://")))
                }
                rawStream.use { stream ->
                    var flacTotalFrames = totalFrames
                    val decoder = FLACDecoder(BufferedInputStream(stream, 65536))
                    decoder.addPCMProcessor(object : PCMProcessor {
                        private var channels = 2
                        private var bps = 16
                        override fun processStreamInfo(streamInfo: StreamInfo) {
                            sampleRate = streamInfo.sampleRate
                            channels = streamInfo.channels.coerceAtLeast(1)
                            bps = streamInfo.bitsPerSample
                            if (streamInfo.totalSamples > 0) {
                                flacTotalFrames = streamInfo.totalSamples
                            }
                        }

                        override fun processPCM(pcm: ByteData) {
                            val data = pcm.data
                            val len = pcm.len
                            val bytesPerSample = (bps / 8).coerceAtLeast(2)
                            val frameBytes = bytesPerSample * channels
                            var pos = 0
                            while (pos + frameBytes <= len) {
                                val sample: Float = when (bytesPerSample) {
                                    3 -> {
                                        val b0 = data[pos].toInt() and 0xFF
                                        val b1 = data[pos + 1].toInt() and 0xFF
                                        val b2 = data[pos + 2].toInt()
                                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                                    }
                                    else -> {
                                        val b0 = data[pos].toInt() and 0xFF
                                        val b1 = data[pos + 1].toInt()
                                        ((b1 shl 8) or b0) / 32768f
                                    }
                                }
                                pending[filled++] = sample
                                frame++
                                if (filled == WINDOW) {
                                    analyse(flacTotalFrames)
                                    filled = 0
                                    onProgress((frame.toFloat() / flacTotalFrames).coerceIn(0f, 0.99f))
                                }
                                pos += frameBytes
                            }
                        }
                    })
                    decoder.decode()
                }
            }
        }

        onProgress(1f)
        val db = Array(columns) { c ->
            FloatArray(ROWS) { r -> decibels(power[c][r] / max(1, counts[c]) / (WINDOW / 2 / ROWS)) }
        }
        for (c in 1 until columns) if (counts[c] == 0) db[c] = db[c - 1]
        Spectrum(
            sampleRate = sampleRate,
            columns = columns,
            db = db,
            average = FloatArray(WINDOW / 2) { decibels((average[it] / max(1, windows)).toFloat()) },
        )
    }

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
        val threshold = max(floor + 15f, loudest - 100f)
        val last = (bins - 1 downTo 0).firstOrNull { smooth[it] > threshold } ?: return Verdict.TooQuiet
        val cutoffHz = (last * hzPerBin).toInt()
        val nyquist = sampleRate / 2
        val below = smooth[(last - (1000 / hzPerBin).toInt()).coerceAtLeast(0)]
        val above = smooth[(last + (500 / hzPerBin).toInt()).coerceAtMost(bins - 1)]
        val wall = below - above >= 25f
        return when {
            !wall || cutoffHz >= nyquist * 0.95f -> Verdict.Lossless
            sampleRate > 48_000 && cutoffHz <= 24_500 -> Verdict.Upsampled(cutoffHz)
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
    private val BLOCK_NAMES = listOf("STREAMINFO", "PADDING", "APPLICATION", "SEEKTABLE", "VORBIS_COMMENT", "CUESHEET", "PICTURE")
}

internal fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }

    var length = 2
    while (length <= n) {
        val angle = (if (inverse) 2.0 * PI else -2.0 * PI) / length
        val stepReal = cos(angle).toFloat()
        val stepImaginary = sin(angle).toFloat()
        var i = 0
        while (i < n) {
            var wr = 1f
            var wi = 0f
            for (k in 0 until length / 2) {
                val ar = re[i + k]
                val ai = im[i + k]
                val br = re[i + k + length / 2] * wr - im[i + k + length / 2] * wi
                val bi = re[i + k + length / 2] * wi + im[i + k + length / 2] * wr
                re[i + k] = ar + br
                im[i + k] = ai + bi
                re[i + k + length / 2] = ar - br
                im[i + k + length / 2] = ai - bi
                val nextR = wr * stepReal - wi * stepImaginary
                wi = wr * stepImaginary + wi * stepReal
                wr = nextR
            }
            i += length
        }
        length = length shl 1
    }

    if (inverse) {
        for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}
