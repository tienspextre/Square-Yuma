package dev.lelonio.square.data.local

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses MP3 ID3v2 (v2.2 / v2.3 / v2.4) and ID3v1 / ID3v1.1 tags, plus MPEG frame audio info
 * (sample rate, channels, bitrate, Xing/Info/VBRI/CBR duration) and embedded APIC artwork.
 */
object Id3TagParser {
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MPEG_SCAN_BYTES = 16 * 1024

    fun parse(file: File, includeArtwork: Boolean = false): AudioTrackMetadata? {
        if (!file.isFile || !file.canRead()) return null
        val fileLength = file.length()
        val v1 = readId3v1(file, fileLength)
        val v2 = runCatching {
            file.inputStream().buffered().use { parseStreamInternal(it, fileLength, includeArtwork) }
        }.getOrNull()

        if (v2 == null && v1 == null) return null
        if (v2 == null) return v1
        if (v1 == null) return v2

        return v2.copy(
            title = v2.title ?: v1.title,
            artist = v2.artist ?: v1.artist,
            album = v2.album ?: v1.album,
            year = v2.year ?: v1.year,
            trackNumber = v2.trackNumber ?: v1.trackNumber,
            genre = v2.genre ?: v1.genre,
        )
    }

    fun parse(
        input: InputStream,
        fileSizeBytes: Long = -1L,
        includeArtwork: Boolean = false,
    ): AudioTrackMetadata? = runCatching {
        parseStreamInternal(input.buffered(), fileSizeBytes, includeArtwork)
    }.getOrNull()

    fun extractArtwork(file: File): EmbeddedArtwork? =
        parse(file, includeArtwork = true)?.artwork

    private fun parseStreamInternal(
        input: InputStream,
        fileSizeBytes: Long,
        includeArtwork: Boolean,
    ): AudioTrackMetadata? {
        input.mark(16)
        val header = readExact(input, 10) ?: return null

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var year: String? = null
        var genre: String? = null
        var durationFromTagMs: Long = 0L
        var lyrics: String? = null
        var replayGainDb: Float? = null
        var hasArtwork = false
        var bestArtwork: EmbeddedArtwork? = null
        var bestPicPriority = Int.MAX_VALUE
        var audioStartOffset = 0L

        if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
            val major = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = readSynchsafeInt(header, 6)
            if (major in 2..4 && tagSize in 1..MAX_TAG_BYTES) {
                audioStartOffset = 10L + tagSize
                val rawTag = readExact(input, tagSize) ?: return null
                val tagUnsync = (flags and 0x80) != 0 && major < 4
                val tagBytes = if (tagUnsync) removeUnsync(rawTag) else rawTag

                var offset = 0
                if (major >= 3 && (flags and 0x40) != 0 && tagBytes.size >= 4) {
                    val extSize = if (major == 4) {
                        readSynchsafeInt(tagBytes, 0)
                    } else {
                        4 + readBeInt(tagBytes, 0)
                    }
                    if (extSize in 4..tagBytes.size) offset = extSize
                }

                while (offset < tagBytes.size) {
                    val frame = readFrame(tagBytes, offset, major) ?: break
                    offset = frame.nextOffset
                    if (frame.payload.isEmpty()) continue

                    when (frame.id) {
                        "TIT2", "TT2" -> title = decodeTextFrame(frame.payload) ?: title
                        "TPE1", "TP1" -> artist = decodeTextFrame(frame.payload, joinMulti = true) ?: artist
                        "TALB", "TAL" -> album = decodeTextFrame(frame.payload) ?: album
                        "TPE2", "TP2" -> albumArtist = decodeTextFrame(frame.payload, joinMulti = true) ?: albumArtist
                        "TRCK", "TRK" -> trackNumber = decodeTextFrame(frame.payload)?.substringBefore('/')?.trim()?.toIntOrNull() ?: trackNumber
                        "TPOS", "TPA" -> discNumber = decodeTextFrame(frame.payload)?.substringBefore('/')?.trim()?.toIntOrNull() ?: discNumber
                        "TDRC", "TYER", "TYE" -> {
                            val raw = decodeTextFrame(frame.payload)
                            val y = raw?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: raw
                            if (y != null && (frame.id == "TDRC" || year == null)) year = y
                        }
                        "TCON", "TCO" -> genre = normalizeGenre(decodeTextFrame(frame.payload)) ?: genre
                        "TLEN" -> durationFromTagMs = decodeTextFrame(frame.payload)?.toLongOrNull() ?: durationFromTagMs
                        "TXXX" -> {
                            val (desc, value) = decodeTxxx(frame.payload) ?: (null to null)
                            when (desc?.uppercase(Locale.ROOT)?.trim()) {
                                "REPLAYGAIN_TRACK_GAIN" -> replayGainDb = value?.removeSuffix("dB")?.trim()?.toFloatOrNull()
                                "ALBUMARTIST", "ALBUM ARTIST" -> if (albumArtist == null) albumArtist = value
                            }
                        }
                        "USLT", "ULT" -> if (lyrics == null) lyrics = decodeUslt(frame.payload)
                        "APIC", "PIC" -> {
                            hasArtwork = true
                            if (includeArtwork) {
                                val parsed = if (frame.id == "PIC") decodePicV22(frame.payload) else decodeApic(frame.payload)
                                if (parsed != null) {
                                    val (art, picType) = parsed
                                    val prio = if (picType == 3) 0 else 1
                                    if (prio < bestPicPriority) {
                                        bestPicPriority = prio
                                        bestArtwork = art
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            input.reset()
        }

        val mpegBytes = readUpTo(input, MPEG_SCAN_BYTES)
        val audioBytes = if (fileSizeBytes > audioStartOffset) {
            (fileSizeBytes - audioStartOffset - if (fileSizeBytes >= 128) 128L else 0L).coerceAtLeast(0L)
        } else 0L
        val mpeg = parseMpegInfo(mpegBytes, audioBytes)

        val durationMs = when {
            mpeg.durationMs > 0L -> mpeg.durationMs
            durationFromTagMs > 0L -> durationFromTagMs
            else -> 0L
        }

        val hasAny = title != null || artist != null || album != null || mpeg.sampleRate > 0
        if (!hasAny) return null

        return AudioTrackMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre,
            durationMs = durationMs,
            sampleRate = mpeg.sampleRate,
            bitDepth = 16,
            channels = mpeg.channels,
            bitrateKbps = mpeg.bitrateKbps,
            embeddedLyrics = lyrics,
            hasEmbeddedArtwork = hasArtwork,
            artwork = bestArtwork,
            replayGainTrackDb = replayGainDb,
        )
    }

    private fun readId3v1(file: File, length: Long): AudioTrackMetadata? {
        if (length < 128L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - 128L)
                val buf = ByteArray(128)
                raf.readFully(buf)
                if (buf[0] != 'T'.code.toByte() || buf[1] != 'A'.code.toByte() || buf[2] != 'G'.code.toByte()) {
                    return@runCatching null
                }
                fun slice(start: Int, len: Int): String? =
                    String(buf, start, len, StandardCharsets.ISO_8859_1)
                        .trim { it <= ' ' || it == '\u0000' }
                        .takeIf { it.isNotBlank() }
                val track = if (buf[125] == 0.toByte() && buf[126] != 0.toByte()) buf[126].toInt() and 0xFF else null
                val genreIdx = buf[127].toInt() and 0xFF
                AudioTrackMetadata(
                    title = slice(3, 30),
                    artist = slice(33, 30),
                    album = slice(63, 30),
                    year = slice(93, 4),
                    trackNumber = track,
                    genre = ID3V1_GENRES.getOrNull(genreIdx),
                )
            }
        }.getOrNull()
    }

    private data class ParsedFrame(val id: String, val payload: ByteArray, val nextOffset: Int)

    private fun readFrame(bytes: ByteArray, offset: Int, major: Int): ParsedFrame? {
        if (major == 2) {
            if (offset + 6 > bytes.size) return null
            if (bytes[offset] == 0.toByte()) return null
            val id = String(bytes, offset, 3, StandardCharsets.ISO_8859_1)
            if (id.any { !it.isLetterOrDigit() }) return null
            val size = ((bytes[offset + 3].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 4].toInt() and 0xFF) shl 8) or
                (bytes[offset + 5].toInt() and 0xFF)
            val start = offset + 6
            if (size <= 0 || start + size > bytes.size) return null
            return ParsedFrame(id, bytes.copyOfRange(start, start + size), start + size)
        }

        if (offset + 10 > bytes.size) return null
        if (bytes[offset] == 0.toByte()) return null
        val id = String(bytes, offset, 4, StandardCharsets.ISO_8859_1)
        if (id.any { !it.isLetterOrDigit() }) return null

        val rawSize = readBeInt(bytes, offset + 4)
        val size = if (major == 4) {
            val ss = readSynchsafeInt(bytes, offset + 4)
            if (offset + 10 + ss <= bytes.size) ss else rawSize
        } else {
            rawSize
        }
        if (size <= 0 || offset + 10 + size > bytes.size) return null

        val flags1 = bytes[offset + 9].toInt() and 0xFF
        var start = offset + 10
        var payload = bytes.copyOfRange(start, start + size)
        if (major == 4) {
            if ((flags1 and 0x02) != 0) payload = removeUnsync(payload)
            if ((flags1 and 0x01) != 0 && payload.size >= 4) payload = payload.copyOfRange(4, payload.size)
        }
        return ParsedFrame(id, payload, offset + 10 + size)
    }

    private fun decodeTextFrame(bytes: ByteArray, joinMulti: Boolean = false): String? {
        if (bytes.size <= 1) return null
        val encoding = bytes[0].toInt() and 0xFF
        val charset = charsetFor(encoding)
        val decoded = String(bytes, 1, bytes.size - 1, charset).removePrefix("\uFEFF")
        val parts = decoded.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return if (joinMulti) parts.joinToString(", ") else parts.first()
    }

    private fun decodeTxxx(bytes: ByteArray): Pair<String, String>? {
        if (bytes.size <= 2) return null
        val encoding = bytes[0].toInt() and 0xFF
        val charset = charsetFor(encoding)
        val term = findTerminator(bytes, 1, encoding) ?: return null
        val desc = String(bytes, 1, term - 1, charset).removePrefix("\uFEFF").trim()
        val valStart = term + termSize(encoding)
        if (valStart >= bytes.size) return desc to ""
        val value = String(bytes, valStart, bytes.size - valStart, charset)
            .removePrefix("\uFEFF").replace("\u0000", "").trim()
        return desc to value
    }

    private fun decodeUslt(bytes: ByteArray): String? {
        if (bytes.size <= 5) return null
        val encoding = bytes[0].toInt() and 0xFF
        val term = findTerminator(bytes, 4, encoding) ?: return null
        val start = term + termSize(encoding)
        if (start >= bytes.size) return null
        return String(bytes, start, bytes.size - start, charsetFor(encoding))
            .removePrefix("\uFEFF").replace("\u0000", "").trim().takeIf { it.isNotBlank() }
    }

    private fun decodeApic(bytes: ByteArray): Pair<EmbeddedArtwork, Int>? {
        if (bytes.size < 6) return null
        val encoding = bytes[0].toInt() and 0xFF
        val mimeEnd = findTerminator(bytes, 1, 0) ?: return null
        val mime = String(bytes, 1, mimeEnd - 1, StandardCharsets.ISO_8859_1).trim().ifBlank { null }
        val picTypeIdx = mimeEnd + 1
        if (picTypeIdx >= bytes.size) return null
        val picType = bytes[picTypeIdx].toInt() and 0xFF
        val descStart = picTypeIdx + 1
        val descEnd = findTerminator(bytes, descStart, encoding) ?: return null
        val dataStart = descEnd + termSize(encoding)
        if (dataStart >= bytes.size) return null
        return EmbeddedArtwork(bytes.copyOfRange(dataStart, bytes.size), mime) to picType
    }

    private fun decodePicV22(bytes: ByteArray): Pair<EmbeddedArtwork, Int>? {
        if (bytes.size < 8) return null
        val encoding = bytes[0].toInt() and 0xFF
        val fmt = String(bytes, 1, 3, StandardCharsets.ISO_8859_1).uppercase(Locale.ROOT)
        val mime = when (fmt) {
            "PNG" -> "image/png"
            "JPG" -> "image/jpeg"
            else -> null
        }
        val picType = bytes[4].toInt() and 0xFF
        val descEnd = findTerminator(bytes, 5, encoding) ?: return null
        val dataStart = descEnd + termSize(encoding)
        if (dataStart >= bytes.size) return null
        return EmbeddedArtwork(bytes.copyOfRange(dataStart, bytes.size), mime) to picType
    }

    private fun normalizeGenre(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val match = Regex("""^\((\d+)\)(.*)$""").find(trimmed)
        if (match != null) {
            val idx = match.groupValues[1].toIntOrNull()
            val rest = match.groupValues[2].trim()
            if (rest.isNotEmpty()) return rest
            if (idx != null) return ID3V1_GENRES.getOrNull(idx) ?: trimmed
        }
        return trimmed
    }

    private data class MpegInfo(val sampleRate: Int = 0, val channels: Int = 0, val bitrateKbps: Int = 0, val durationMs: Long = 0L)

    private fun parseMpegInfo(bytes: ByteArray, audioBytes: Long): MpegInfo {
        var i = 0
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                val b3 = bytes[i + 3].toInt() and 0xFF
                val versionBits = (b1 ushr 3) and 0x03
                val layerBits = (b1 ushr 1) and 0x03
                val brIdx = (b2 ushr 4) and 0x0F
                val srIdx = (b2 ushr 2) and 0x03
                val chMode = (b3 ushr 6) and 0x03

                if (versionBits != 1 && layerBits != 0 && brIdx in 1..14 && srIdx in 0..2) {
                    val sampleRate = SAMPLE_RATES[versionBits][srIdx]
                    val bitrateKbps = bitrateKbps(versionBits, layerBits, brIdx)
                    val channels = if (chMode == 3) 1 else 2
                    val samplesPerFrame = when (layerBits) {
                        3 -> 384 // Layer I
                        2 -> 1152 // Layer II
                        else -> if (versionBits == 3) 1152 else 576 // Layer III
                    }
                    if (sampleRate > 0 && bitrateKbps > 0) {
                        var durationMs = 0L
                        val xingOffset = i + when {
                            versionBits == 3 && channels == 2 -> 36
                            versionBits == 3 && channels == 1 -> 21
                            versionBits != 3 && channels == 2 -> 21
                            else -> 13
                        }
                        if (xingOffset + 12 <= bytes.size) {
                            val tag = String(bytes, xingOffset, 4, StandardCharsets.US_ASCII)
                            if (tag == "Xing" || tag == "Info") {
                                val xFlags = readBeInt(bytes, xingOffset + 4)
                                if ((xFlags and 0x01) != 0) {
                                    val frames = readBeInt(bytes, xingOffset + 8).toLong() and 0xFFFFFFFFL
                                    if (frames > 0) durationMs = (frames * samplesPerFrame * 1000L) / sampleRate
                                }
                            }
                        }
                        if (durationMs == 0L && i + 36 + 18 <= bytes.size) {
                            val vbri = String(bytes, i + 36, 4, StandardCharsets.US_ASCII)
                            if (vbri == "VBRI") {
                                val frames = readBeInt(bytes, i + 36 + 14).toLong() and 0xFFFFFFFFL
                                if (frames > 0) durationMs = (frames * samplesPerFrame * 1000L) / sampleRate
                            }
                        }
                        if (durationMs == 0L && audioBytes > 0) {
                            durationMs = (audioBytes * 8L) / bitrateKbps
                        }
                        return MpegInfo(sampleRate, channels, bitrateKbps, durationMs)
                    }
                }
            }
            i++
        }
        return MpegInfo()
    }

    private fun bitrateKbps(versionBits: Int, layerBits: Int, brIdx: Int): Int {
        val mpeg1 = versionBits == 3
        return when (layerBits) {
            3 -> if (mpeg1) V1_L1[brIdx] else V2_L1[brIdx]
            2 -> if (mpeg1) V1_L2[brIdx] else V2_L23[brIdx]
            1 -> if (mpeg1) V1_L3[brIdx] else V2_L23[brIdx]
            else -> 0
        }
    }

    private fun charsetFor(encoding: Int): Charset = when (encoding) {
        0 -> StandardCharsets.ISO_8859_1
        1 -> StandardCharsets.UTF_16
        2 -> StandardCharsets.UTF_16BE
        else -> StandardCharsets.UTF_8
    }

    private fun termSize(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun findTerminator(bytes: ByteArray, start: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < bytes.size) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                i += 2
            }
            return null
        }
        for (i in start until bytes.size) if (bytes[i] == 0.toByte()) return i
        return null
    }

    private fun removeUnsync(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        var w = 0
        var r = 0
        while (r < bytes.size) {
            val v = bytes[r]
            out[w++] = v
            if (v == 0xFF.toByte() && r + 1 < bytes.size && bytes[r + 1] == 0.toByte()) r++
            r++
        }
        return out.copyOf(w)
    }

    private fun readSynchsafeInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun readBeInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

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

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val r = input.read(buf, off, max - off)
            if (r < 0) break
            off += r
        }
        return if (off == max) buf else buf.copyOf(off)
    }

    private val SAMPLE_RATES = arrayOf(
        intArrayOf(11025, 12000, 8000), // 0: MPEG 2.5
        intArrayOf(0, 0, 0),            // 1: reserved
        intArrayOf(22050, 24000, 16000),// 2: MPEG 2
        intArrayOf(44100, 48000, 32000),// 3: MPEG 1
    )
    private val V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
    private val V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    private val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )
}
