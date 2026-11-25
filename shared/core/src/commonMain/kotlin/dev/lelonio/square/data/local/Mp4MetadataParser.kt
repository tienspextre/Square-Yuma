package dev.lelonio.square.data.local

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses MP4/M4A container atoms (`mvhd`, `mdhd`, `stsd`, `udta/meta/ilst`) for track metadata,
 * duration, sample format, lyrics (`©lyr`), and embedded cover artwork (`covr`).
 */
object Mp4MetadataParser {
    private const val HEADER_SIZE = 8
    private const val DATA_HEADER_SIZE = 8
    private const val MAX_ATOM_BYTES = 16 * 1024 * 1024L
    private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "ilst")

    fun parse(file: File, includeArtwork: Boolean = false): AudioTrackMetadata? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            file.inputStream().buffered().use { parseStream(it, file.length(), includeArtwork) }
        }.getOrNull()
    }

    fun parse(
        input: InputStream,
        fileSizeBytes: Long = -1L,
        includeArtwork: Boolean = false,
    ): AudioTrackMetadata? = runCatching {
        parseStream(input.buffered(), fileSizeBytes, includeArtwork)
    }.getOrNull()

    fun extractArtwork(file: File): EmbeddedArtwork? =
        parse(file, includeArtwork = true)?.artwork

    private class State {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var year: String? = null
        var genre: String? = null
        var lyrics: String? = null
        var replayGainDb: Float? = null
        var durationMs: Long = 0L
        var sampleRate: Int = 0
        var channels: Int = 0
        var bitDepth: Int = 0
        var hasArtwork = false
        var artwork: EmbeddedArtwork? = null
        var foundMoov = false
    }

    private fun parseStream(
        input: InputStream,
        fileSizeBytes: Long,
        includeArtwork: Boolean,
    ): AudioTrackMetadata? {
        val state = State()
        parseBoxes(input, Long.MAX_VALUE, state, includeArtwork)
        if (!state.foundMoov && state.title == null && state.durationMs == 0L) return null
        val bitrateKbps = if (state.durationMs > 0L && fileSizeBytes > 0L) {
            ((fileSizeBytes * 8L) / state.durationMs).toInt()
        } else 0
        return AudioTrackMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            albumArtist = state.albumArtist,
            trackNumber = state.trackNumber,
            discNumber = state.discNumber,
            year = state.year,
            genre = state.genre,
            durationMs = state.durationMs,
            sampleRate = state.sampleRate,
            bitDepth = if (state.bitDepth > 0) state.bitDepth else 16,
            channels = if (state.channels > 0) state.channels else 2,
            bitrateKbps = bitrateKbps,
            embeddedLyrics = state.lyrics,
            hasEmbeddedArtwork = state.hasArtwork,
            artwork = state.artwork,
            replayGainTrackDb = state.replayGainDb,
        )
    }

    private fun parseBoxes(
        input: InputStream,
        remainingBytes: Long,
        state: State,
        includeArtwork: Boolean,
    ) {
        var remaining = remainingBytes
        while (remaining > HEADER_SIZE) {
            val hdr = readHeader(input) ?: return
            val payloadSize = hdr.payloadSize
            if (remainingBytes != Long.MAX_VALUE) remaining -= hdr.size
            if (payloadSize < 0) return

            when {
                hdr.type == "moov" -> {
                    state.foundMoov = true
                    parseBoxes(input, payloadSize, state, includeArtwork)
                }
                hdr.type == "meta" -> {
                    if (payloadSize <= 4L) {
                        skipFully(input, payloadSize)
                    } else {
                        skipFully(input, 4L) // version + flags
                        parseBoxes(input, payloadSize - 4L, state, includeArtwork)
                    }
                }
                hdr.type == "mvhd" -> {
                    val data = readCapped(input, payloadSize) ?: continue
                    parseMvhd(data, state)
                }
                hdr.type == "mdhd" -> {
                    val data = readCapped(input, payloadSize) ?: continue
                    parseMdhd(data, state)
                }
                hdr.type == "stsd" -> {
                    val data = readCapped(input, payloadSize) ?: continue
                    parseStsd(data, state)
                }
                hdr.type in CONTAINER_BOXES -> {
                    parseBoxes(input, payloadSize, state, includeArtwork)
                }
                hdr.typeBytes.isIlstAtom() -> {
                    val data = readCapped(input, payloadSize)
                    if (data != null) parseIlstItem(hdr.typeBytes, data, state, includeArtwork)
                }
                else -> skipFully(input, payloadSize)
            }
        }
    }

    private fun parseMvhd(data: ByteArray, state: State) {
        if (data.size < 20) return
        val version = data[0].toInt() and 0xFF
        val (timescale, duration) = if (version == 1 && data.size >= 32) {
            readBeInt(data, 20).toLong().and(0xFFFFFFFFL) to readBeLong(data, 24)
        } else {
            readBeInt(data, 12).toLong().and(0xFFFFFFFFL) to readBeInt(data, 16).toLong().and(0xFFFFFFFFL)
        }
        if (timescale > 0L && duration > 0L) {
            state.durationMs = (duration * 1000L) / timescale
        }
    }

    private fun parseMdhd(data: ByteArray, state: State) {
        if (state.durationMs > 0L || data.size < 20) return
        val version = data[0].toInt() and 0xFF
        val (timescale, duration) = if (version == 1 && data.size >= 32) {
            readBeInt(data, 20).toLong().and(0xFFFFFFFFL) to readBeLong(data, 24)
        } else {
            readBeInt(data, 12).toLong().and(0xFFFFFFFFL) to readBeInt(data, 16).toLong().and(0xFFFFFFFFL)
        }
        if (timescale > 0L && duration > 0L) {
            state.durationMs = (duration * 1000L) / timescale
        }
    }

    private fun parseStsd(data: ByteArray, state: State) {
        // 4B version/flags + 4B entryCount + first sample entry (>= 36 bytes)
        if (data.size < 44) return
        val entryType = String(data, 12, 4, StandardCharsets.ISO_8859_1)
        if (entryType in setOf("mp4a", "alac", "fLaC", "Opus")) {
            val ch = ((data[32].toInt() and 0xFF) shl 8) or (data[33].toInt() and 0xFF)
            val bits = ((data[34].toInt() and 0xFF) shl 8) or (data[35].toInt() and 0xFF)
            val srFixed = ((data[40].toInt() and 0xFF) shl 8) or (data[41].toInt() and 0xFF)
            if (ch in 1..16) state.channels = ch
            if (bits in 8..32) state.bitDepth = bits
            if (srFixed > 0) state.sampleRate = srFixed
        }
    }

    private fun parseIlstItem(typeBytes: ByteArray, bytes: ByteArray, state: State, includeArtwork: Boolean) {
        val typeStr = String(typeBytes, StandardCharsets.ISO_8859_1)
        if (typeStr == "covr") {
            state.hasArtwork = true
            if (!includeArtwork) return
            val dataAtom = findDataAtom(bytes) ?: return
            val mime = when (dataAtom.dataType) {
                13 -> "image/jpeg"
                14 -> "image/png"
                27 -> "image/bmp"
                else -> null
            }
            state.artwork = EmbeddedArtwork(dataAtom.payload, mime)
            return
        }
        if (typeStr == "----") {
            parseFreeform(bytes, state)
            return
        }

        val dataAtom = findDataAtom(bytes) ?: return
        val p = dataAtom.payload
        when {
            typeBytes.contentEquals(ATOM_NAM) -> state.title = decodeText(p) ?: state.title
            typeBytes.contentEquals(ATOM_ART) -> state.artist = decodeText(p) ?: state.artist
            typeBytes.contentEquals(ATOM_ALB) -> state.album = decodeText(p) ?: state.album
            typeStr == "aART" -> state.albumArtist = decodeText(p) ?: state.albumArtist
            typeBytes.contentEquals(ATOM_DAY) -> {
                val raw = decodeText(p)
                state.year = raw?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: raw ?: state.year
            }
            typeBytes.contentEquals(ATOM_GEN) -> state.genre = decodeText(p) ?: state.genre
            typeStr == "gnre" && p.size >= 2 -> {
                val idx = (((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)) - 1
                if (state.genre == null && idx >= 0) state.genre = GENRE_LIST.getOrNull(idx)
            }
            typeBytes.contentEquals(ATOM_LYR) -> state.lyrics = decodeText(p) ?: state.lyrics
            typeStr == "trkn" && p.size >= 4 -> {
                val n = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
                if (n > 0) state.trackNumber = n
            }
            typeStr == "disk" && p.size >= 4 -> {
                val n = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
                if (n > 0) state.discNumber = n
            }
        }
    }

    private fun parseFreeform(bytes: ByteArray, state: State) {
        var name: String? = null
        var value: String? = null
        var offset = 0
        while (offset + HEADER_SIZE <= bytes.size) {
            val size = readBeInt(bytes, offset)
            val t = String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1)
            if (size < HEADER_SIZE || offset + size > bytes.size) break
            if (t == "name" && size > 12) {
                name = String(bytes, offset + 12, size - 12, StandardCharsets.UTF_8).trim()
            } else if (t == "data" && size > 16) {
                value = decodeText(bytes.copyOfRange(offset + 16, offset + size))
            }
            offset += size
        }
        if (name?.uppercase(Locale.ROOT) == "REPLAYGAIN_TRACK_GAIN") {
            state.replayGainDb = value?.removeSuffix("dB")?.trim()?.toFloatOrNull()
        }
    }

    private data class DataAtom(val dataType: Int, val payload: ByteArray)

    private fun findDataAtom(bytes: ByteArray): DataAtom? {
        var offset = 0
        while (offset + HEADER_SIZE <= bytes.size) {
            val size = readBeInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1)
            if (size < HEADER_SIZE || offset + size > bytes.size) return null
            if (type == "data") {
                if (size < HEADER_SIZE + DATA_HEADER_SIZE) return null
                val dataType = readBeInt(bytes, offset + HEADER_SIZE) and 0x00FFFFFF
                val start = offset + HEADER_SIZE + DATA_HEADER_SIZE
                return DataAtom(dataType, bytes.copyOfRange(start, offset + size))
            }
            offset += size
        }
        return null
    }

    private fun decodeText(bytes: ByteArray): String? {
        val utf8 = String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").replace("\u0000", "").trim()
        return utf8.takeIf { it.isNotBlank() }
    }

    private fun ByteArray.isIlstAtom(): Boolean {
        val s = String(this, StandardCharsets.ISO_8859_1)
        return this.contentEquals(ATOM_NAM) ||
            this.contentEquals(ATOM_ART) ||
            this.contentEquals(ATOM_ALB) ||
            this.contentEquals(ATOM_DAY) ||
            this.contentEquals(ATOM_GEN) ||
            this.contentEquals(ATOM_LYR) ||
            s in setOf("aART", "trkn", "disk", "gnre", "covr", "----")
    }

    private data class BoxHeader(val type: String, val typeBytes: ByteArray, val size: Long, val payloadSize: Long)

    private fun readHeader(input: InputStream): BoxHeader? {
        val hdr = readExact(input, HEADER_SIZE) ?: return null
        val small = readBeInt(hdr, 0).toLong() and 0xFFFFFFFFL
        val typeBytes = hdr.copyOfRange(4, 8)
        val type = String(typeBytes, StandardCharsets.ISO_8859_1)
        val size = when (small) {
            0L -> return null
            1L -> {
                val ext = readExact(input, 8) ?: return null
                readBeLong(ext, 0)
            }
            else -> small
        }
        val hdrLen = if (small == 1L) 16 else HEADER_SIZE
        if (size < hdrLen) return null
        return BoxHeader(type, typeBytes, size, size - hdrLen)
    }

    private fun readCapped(input: InputStream, payloadSize: Long): ByteArray? {
        if (payloadSize !in 1..MAX_ATOM_BYTES) {
            skipFully(input, payloadSize)
            return null
        }
        return readExact(input, payloadSize.toInt())
    }

    private fun readExact(input: InputStream, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun skipFully(input: InputStream, count: Long) {
        var rem = count
        while (rem > 0L) {
            val s = input.skip(rem)
            if (s > 0L) rem -= s
            else if (input.read() == -1) throw EOFException()
            else rem--
        }
    }

    private fun readBeInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readBeLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in off until off + 8) v = (v shl 8) or (b[i].toLong() and 0xFFL)
        return v
    }

    private val ATOM_NAM = byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
    private val ATOM_ART = byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte())
    private val ATOM_ALB = byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte())
    private val ATOM_DAY = byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte())
    private val ATOM_GEN = byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte())
    private val ATOM_LYR = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())

    private val GENRE_LIST = listOf("Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska")
}
