package dev.lelonio.square.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Parses Ogg Vorbis (`\x01vorbis`/`\x03vorbis`) and Ogg Opus (`OpusHead`/`OpusTags`) headers,
 * Vorbis comments (across multi-page Ogg packets), embedded `METADATA_BLOCK_PICTURE`, and
 * stream duration from the final Ogg page granule position.
 */
object OggMetadataParser {
    private val OGG_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private val VORBIS_ID = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    private val VORBIS_COMMENT = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    private val OPUS_HEAD = "OpusHead".toByteArray(StandardCharsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

    fun parse(file: File, includeArtwork: Boolean = false): AudioTrackMetadata? {
        if (!file.isFile || !file.canRead()) return null
        val meta = runCatching {
            file.inputStream().buffered().use { parseStream(it, file.length(), includeArtwork, readLastGranule(file)) }
        }.getOrNull() ?: return null
        return meta
    }

    fun parse(
        input: InputStream,
        fileSizeBytes: Long = -1L,
        includeArtwork: Boolean = false,
    ): AudioTrackMetadata? = runCatching {
        parseStream(input.buffered(), fileSizeBytes, includeArtwork, null)
    }.getOrNull()

    fun extractArtwork(file: File): EmbeddedArtwork? =
        parse(file, includeArtwork = true)?.artwork

    private fun parseStream(
        input: InputStream,
        fileSizeBytes: Long,
        includeArtwork: Boolean,
        lastGranuleFromTail: Long?,
    ): AudioTrackMetadata? {
        val reader = OggPacketReader(input)
        val pkt0 = reader.nextPacket() ?: return null
        val pkt1 = reader.nextPacket()

        var sampleRate = 0
        var granuleRate = 0
        var preSkip = 0
        var channels = 0
        var bitrateKbps = 0
        var isOpus = false

        when {
            pkt0.startsWith(VORBIS_ID) && pkt0.size >= 28 -> {
                channels = pkt0[11].toInt() and 0xFF
                sampleRate = readLeInt(pkt0, 12)
                granuleRate = sampleRate
                val nominal = readLeInt(pkt0, 20)
                if (nominal > 0) bitrateKbps = nominal / 1000
            }
            pkt0.startsWith(OPUS_HEAD) && pkt0.size >= 19 -> {
                isOpus = true
                channels = pkt0[9].toInt() and 0xFF
                preSkip = (pkt0[10].toInt() and 0xFF) or ((pkt0[11].toInt() and 0xFF) shl 8)
                val inputRate = readLeInt(pkt0, 12)
                sampleRate = if (inputRate > 0) inputRate else 48000
                granuleRate = 48000
            }
            else -> return null
        }

        val tags = mutableMapOf<String, String>()
        var artwork: EmbeddedArtwork? = null
        var hasArtwork = false

        if (pkt1 != null) {
            val commentStart = when {
                !isOpus && pkt1.startsWith(VORBIS_COMMENT) -> VORBIS_COMMENT.size
                isOpus && pkt1.startsWith(OPUS_TAGS) -> OPUS_TAGS.size
                else -> -1
            }
            if (commentStart >= 0) {
                parseVorbisComments(pkt1, commentStart, tags)
                val picB64 = tags["METADATA_BLOCK_PICTURE"]
                if (!picB64.isNullOrBlank()) {
                    hasArtwork = true
                    if (includeArtwork) {
                        val rawPic = runCatching { Base64.getDecoder().decode(picB64) }.getOrNull()
                        if (rawPic != null) artwork = FlacMetadataParser.parsePictureBlock(rawPic)
                    }
                }
            }
        }

        val lastGranule = lastGranuleFromTail ?: reader.lastGranule
        val durationMs = if (lastGranule != null && lastGranule > 0 && granuleRate > 0) {
            val effectiveSamples = if (isOpus) (lastGranule - preSkip).coerceAtLeast(0L) else lastGranule
            (effectiveSamples * 1000L) / granuleRate
        } else if (bitrateKbps > 0 && fileSizeBytes > 0) {
            (fileSizeBytes * 8L) / bitrateKbps
        } else 0L

        if (bitrateKbps == 0 && durationMs > 0 && fileSizeBytes > 0) {
            bitrateKbps = ((fileSizeBytes * 8L) / durationMs).toInt()
        }

        return AudioTrackMetadata(
            title = tags["TITLE"],
            artist = tags["ARTIST"],
            album = tags["ALBUM"],
            albumArtist = tags["ALBUMARTIST"] ?: tags["ALBUM_ARTIST"] ?: tags["ALBUM ARTIST"],
            trackNumber = tags["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = tags["DISCNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            year = (tags["DATE"] ?: tags["YEAR"])?.let { d ->
                d.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } ?: d
            },
            genre = tags["GENRE"],
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = 16,
            channels = channels,
            bitrateKbps = bitrateKbps,
            embeddedLyrics = tags["SYNCEDLYRICS"] ?: tags["SYNCLYRICS"] ?: tags["LYRICS"] ?: tags["UNSYNCEDLYRICS"],
            hasEmbeddedArtwork = hasArtwork,
            artwork = artwork,
            replayGainTrackDb = tags["REPLAYGAIN_TRACK_GAIN"]?.removeSuffix("dB")?.trim()?.toFloatOrNull(),
        )
    }

    private fun readLastGranule(file: File): Long? {
        val len = file.length()
        if (len < 27L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val tailSize = minOf(len, 65_536L).toInt()
                raf.seek(len - tailSize)
                val buf = ByteArray(tailSize)
                raf.readFully(buf)
                var pos = tailSize - 27
                while (pos >= 0) {
                    if (buf[pos] == OGG_MAGIC[0] &&
                        buf[pos + 1] == OGG_MAGIC[1] &&
                        buf[pos + 2] == OGG_MAGIC[2] &&
                        buf[pos + 3] == OGG_MAGIC[3] &&
                        buf[pos + 4] == 0.toByte()
                    ) {
                        val gran = readLeLong(buf, pos + 6)
                        if (gran >= 0) return@use gran
                    }
                    pos--
                }
                null
            }
        }.getOrNull()
    }

    private fun parseVorbisComments(bytes: ByteArray, offset: Int, out: MutableMap<String, String>) {
        if (offset + 4 > bytes.size) return
        var cursor = offset
        val vendorLen = readLeInt(bytes, cursor)
        if (vendorLen < 0) return
        cursor += 4 + vendorLen
        if (cursor + 4 > bytes.size) return
        val count = readLeInt(bytes, cursor)
        if (count < 0) return
        cursor += 4
        for (i in 0 until count.coerceAtMost(500)) {
            if (cursor + 4 > bytes.size) break
            val len = readLeInt(bytes, cursor)
            cursor += 4
            if (len < 0 || cursor + len > bytes.size) break
            val comment = String(bytes, cursor, len, StandardCharsets.UTF_8)
            cursor += len
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase(Locale.ROOT).trim()
                val valStr = comment.substring(eq + 1).trim()
                if (key.isNotEmpty() && valStr.isNotEmpty()) out[key] = valStr
            }
        }
    }

    private class OggPacketReader(private val input: InputStream) {
        private val current = ByteArrayOutputStream()
        private var segments: IntArray = IntArray(0)
        private var segIndex = 0
        private var pageData: ByteArray = ByteArray(0)
        private var pageOffset = 0
        var lastGranule: Long? = null
            private set

        fun nextPacket(): ByteArray? {
            while (true) {
                if (segIndex >= segments.size) {
                    if (!readPage()) return if (current.size() > 0) current.toByteArray().also { current.reset() } else null
                }
                while (segIndex < segments.size) {
                    val lacing = segments[segIndex++]
                    if (lacing > 0 && pageOffset + lacing <= pageData.size && current.size() + lacing <= MAX_PACKET_BYTES) {
                        current.write(pageData, pageOffset, lacing)
                    }
                    pageOffset += lacing
                    if (lacing < 255) {
                        val packet = current.toByteArray()
                        current.reset()
                        return packet
                    }
                }
            }
        }

        private fun readPage(): Boolean {
            val hdr = readExact(input, 27) ?: return false
            if (!hdr.startsWith(OGG_MAGIC)) return false
            val gran = readLeLong(hdr, 6)
            if (gran >= 0) lastGranule = gran
            val numSeg = hdr[26].toInt() and 0xFF
            val table = readExact(input, numSeg) ?: return false
            segments = IntArray(numSeg) { table[it].toInt() and 0xFF }
            val total = segments.sum()
            pageData = readExact(input, total) ?: return false
            segIndex = 0
            pageOffset = 0
            return true
        }

        private fun readExact(input: InputStream, len: Int): ByteArray? {
            if (len == 0) return ByteArray(0)
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(buf, off, len - off)
                if (r < 0) return null
                off += r
            }
            return buf
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun readLeInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLeLong(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[offset + i].toLong() and 0xFFL)
        return v
    }
}
