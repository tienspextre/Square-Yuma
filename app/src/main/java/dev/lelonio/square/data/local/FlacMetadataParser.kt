package dev.lelonio.square.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class FlacTrackInfo(
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val durationMs: Long = 0L,
    val bitrateKbps: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val date: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val lyrics: String? = null,
    val replayGainTrackDb: Float? = null,
) {
    val isHiRes: Boolean get() = bitDepth >= 24 || sampleRate >= 48000

    val qualityBadge: String
        get() = when {
            bitDepth >= 24 -> "${bitDepth}-bit / ${sampleRate / 1000f} kHz FLAC"
            sampleRate > 44100 -> "16-bit / ${sampleRate / 1000f} kHz FLAC"
            else -> "Lossless FLAC"
        }
}

object FlacMetadataParser {
    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    fun parse(file: File): FlacTrackInfo? {
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            file.inputStream().buffered().use { parseInternal(it, file.length()) }
        }.getOrNull()
    }

    fun parse(context: Context, uri: Uri): FlacTrackInfo? {
        return runCatching {
            val length = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            context.contentResolver.openInputStream(uri)?.buffered()?.use { parseInternal(it, length) }
        }.getOrNull()
    }

    private fun parseInternal(input: InputStream, fileSizeBytes: Long): FlacTrackInfo? {
        val magic = ByteArray(4)
        if (input.read(magic) != 4 || !magic.contentEquals(FLAC_MAGIC)) {
            return null
        }

        var sampleRate = 44100
        var bitDepth = 16
        var channels = 2
        var totalSamples = 0L
        val tags = mutableMapOf<String, String>()

        var isLast = false
        while (!isLast) {
            val header = ByteArray(4)
            if (input.read(header) != 4) break
            isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7F
            val blockLength = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            when (blockType) {
                0 -> { // STREAMINFO (34 bytes)
                    val data = ByteArray(blockLength)
                    if (input.read(data) == blockLength && blockLength >= 18) {
                        // sampleRate: 20 bits at byte 10..12
                        val b10 = data[10].toInt() and 0xFF
                        val b11 = data[11].toInt() and 0xFF
                        val b12 = data[12].toInt() and 0xFF
                        val b13 = data[13].toInt() and 0xFF

                        sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                        channels = ((b12 ushr 1) and 0x07) + 1
                        bitDepth = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1

                        val s0 = (b13 and 0x0F).toLong()
                        val s1 = data[14].toLong() and 0xFFL
                        val s2 = data[15].toLong() and 0xFFL
                        val s3 = data[16].toLong() and 0xFFL
                        val s4 = data[17].toLong() and 0xFFL
                        totalSamples = (s0 shl 32) or (s1 shl 24) or (s2 shl 16) or (s3 shl 8) or s4
                    }
                }
                4 -> { // VORBIS_COMMENT
                    val data = ByteArray(blockLength)
                    if (input.read(data) == blockLength) {
                        parseVorbisComments(data, tags)
                    }
                }
                else -> {
                    var remaining = blockLength.toLong()
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) {
                            if (input.read() == -1) break
                            remaining--
                        } else {
                            remaining -= skipped
                        }
                    }
                }
            }
        }

        val durationMs = if (sampleRate > 0) (totalSamples * 1000L) / sampleRate else 0L
        val bitrateKbps = if (durationMs > 0 && fileSizeBytes > 0) {
            ((fileSizeBytes * 8L) / durationMs).toInt()
        } else 0

        val replayGainDb = tags["REPLAYGAIN_TRACK_GAIN"]?.removeSuffix("dB")?.trim()?.toFloatOrNull()
        val trackNum = tags["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull()
        val discNum = tags["DISCNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull()

        return FlacTrackInfo(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps,
            title = tags["TITLE"],
            artist = tags["ARTIST"],
            album = tags["ALBUM"],
            albumArtist = tags["ALBUMARTIST"] ?: tags["ALBUM_ARTIST"],
            trackNumber = trackNum,
            discNumber = discNum,
            date = tags["DATE"] ?: tags["YEAR"],
            genre = tags["GENRE"],
            composer = tags["COMPOSER"],
            lyrics = tags["SYNCEDLYRICS"] ?: tags["LYRICS"] ?: tags["UNSYNCEDLYRICS"],
            replayGainTrackDb = replayGainDb,
        )
    }

    private fun parseVorbisComments(bytes: ByteArray, tags: MutableMap<String, String>) {
        if (bytes.size < 8) return
        var cursor = 0
        val vendorLength = readLeInt(bytes, cursor)
        cursor += 4 + vendorLength
        if (cursor + 4 > bytes.size) return

        val commentCount = readLeInt(bytes, cursor)
        cursor += 4

        for (i in 0 until commentCount.coerceAtMost(500)) {
            if (cursor + 4 > bytes.size) break
            val len = readLeInt(bytes, cursor)
            cursor += 4
            if (len < 0 || cursor + len > bytes.size) break
            val comment = String(bytes, cursor, len, StandardCharsets.UTF_8)
            cursor += len

            val eqIndex = comment.indexOf('=')
            if (eqIndex > 0) {
                val key = comment.substring(0, eqIndex).uppercase(Locale.ROOT).trim()
                val value = comment.substring(eqIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    tags[key] = value
                }
            }
        }
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
