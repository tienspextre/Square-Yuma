package dev.lelonio.square.data.local

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
    val hasPicture: Boolean = false,
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

    /** A stream of known (or -1, unknown) length, for sources that are not files. */
    fun parse(input: InputStream, length: Long): FlacTrackInfo? = runCatching { parseInternal(input.buffered(), length) }.getOrNull()

    fun parseArtwork(file: File): EmbeddedArtwork? {
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            file.inputStream().buffered().use { parseArtwork(it) }
        }.getOrNull()
    }

    fun parseArtwork(input: InputStream): EmbeddedArtwork? = runCatching {
        val buffered = input.buffered()
        val magic = ByteArray(4)
        if (buffered.read(magic) != 4 || !magic.contentEquals(FLAC_MAGIC)) return@runCatching null
        var firstArt: EmbeddedArtwork? = null
        var isLast = false
        while (!isLast) {
            val header = ByteArray(4)
            if (buffered.read(header) != 4) break
            isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7F
            val blockLength = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (blockLength < 0 || blockLength > 16 * 1024 * 1024) break
            if (blockType == 6) {
                val data = readExact(buffered, blockLength) ?: break
                val parsed = parsePictureBlock(data)
                if (parsed != null) {
                    val picType = readBeInt(data, 0)
                    if (picType == 3) return@runCatching parsed
                    if (firstArt == null) firstArt = parsed
                }
            } else {
                skipExact(buffered, blockLength.toLong())
            }
        }
        firstArt
    }.getOrNull()

    internal fun parsePictureBlock(data: ByteArray): EmbeddedArtwork? {
        if (data.size < 32) return null
        var cursor = 4 // skip picture type
        val mimeLen = readBeInt(data, cursor)
        cursor += 4
        if (mimeLen < 0 || cursor + mimeLen + 4 > data.size) return null
        val mime = String(data, cursor, mimeLen, StandardCharsets.UTF_8).ifBlank { null }
        cursor += mimeLen
        val descLen = readBeInt(data, cursor)
        cursor += 4
        if (descLen < 0 || cursor + descLen + 20 > data.size) return null
        cursor += descLen + 16 // skip width, height, depth, colors
        val picLen = readBeInt(data, cursor)
        cursor += 4
        if (picLen <= 0 || cursor + picLen > data.size) return null
        return EmbeddedArtwork(data.copyOfRange(cursor, cursor + picLen), mime)
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
        var hasPicture = false
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
            if (blockLength < 0 || blockLength > 16 * 1024 * 1024) break

            when (blockType) {
                0 -> { // STREAMINFO (34 bytes)
                    val data = readExact(input, blockLength)
                    if (data != null && blockLength >= 18) {
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
                    val data = readExact(input, blockLength)
                    if (data != null) {
                        parseVorbisComments(data, tags)
                    }
                }
                6 -> { // PICTURE
                    hasPicture = true
                    skipExact(input, blockLength.toLong())
                }
                else -> skipExact(input, blockLength.toLong())
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
            hasPicture = hasPicture,
        )
    }

    private fun readExact(input: InputStream, len: Int): ByteArray? {
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(data, off, len - off)
            if (r < 0) return null
            off += r
        }
        return data
    }

    private fun skipExact(input: InputStream, count: Long) {
        var remaining = count
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

    private fun readBeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
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
