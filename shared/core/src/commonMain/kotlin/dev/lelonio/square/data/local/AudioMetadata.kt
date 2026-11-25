package dev.lelonio.square.data.local

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class EmbeddedArtwork(
    val bytes: ByteArray,
    val mimeType: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedArtwork) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (mimeType?.hashCode() ?: 0)
}

data class AudioTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val bitrateKbps: Int = 0,
    val embeddedLyrics: String? = null,
    val hasEmbeddedArtwork: Boolean = false,
    val artwork: EmbeddedArtwork? = null,
    val replayGainTrackDb: Float? = null,
)

/**
 * Format-dispatching metadata & artwork reader for local audio files
 * (FLAC, MP3 ID3v2/ID3v1, Ogg Vorbis/Opus, MP4/M4A, WAV).
 */
object AudioMetadataParser {
    val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "ogg", "oga", "opus", "m4a", "mp4", "m4b", "wav")

    fun isSupportedAudioFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
    }

    fun parse(path: Path, includeArtwork: Boolean = false): AudioTrackMetadata? {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null
        return parse(path.toFile(), includeArtwork)
    }

    fun parse(file: File, includeArtwork: Boolean = false): AudioTrackMetadata? {
        if (!file.isFile || !file.canRead()) return null
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "flac" -> parseFlac(file, includeArtwork)
            "mp3" -> Id3TagParser.parse(file, includeArtwork)
            "ogg", "oga", "opus" -> OggMetadataParser.parse(file, includeArtwork)
            "m4a", "mp4", "m4b" -> Mp4MetadataParser.parse(file, includeArtwork)
            "wav" -> parseWav(file)
            else -> null
        }
    }

    fun parse(
        input: InputStream,
        fileName: String,
        fileSizeBytes: Long = -1L,
        includeArtwork: Boolean = false,
    ): AudioTrackMetadata? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "flac" -> FlacMetadataParser.parse(input, fileSizeBytes)?.toAudioMetadata()
            "mp3" -> Id3TagParser.parse(input, fileSizeBytes, includeArtwork)
            "ogg", "oga", "opus" -> OggMetadataParser.parse(input, fileSizeBytes, includeArtwork)
            "m4a", "mp4", "m4b" -> Mp4MetadataParser.parse(input, fileSizeBytes, includeArtwork)
            "wav" -> parseWavStream(input, fileSizeBytes)
            else -> null
        }
    }

    fun extractArtwork(path: Path): EmbeddedArtwork? =
        if (Files.isRegularFile(path)) extractArtwork(path.toFile()) else null

    fun extractArtwork(file: File): EmbeddedArtwork? {
        if (!file.isFile || !file.canRead()) return null
        return when (file.extension.lowercase(Locale.ROOT)) {
            "flac" -> FlacMetadataParser.parseArtwork(file)
            "mp3" -> Id3TagParser.extractArtwork(file)
            "ogg", "oga", "opus" -> OggMetadataParser.extractArtwork(file)
            "m4a", "mp4", "m4b" -> Mp4MetadataParser.extractArtwork(file)
            else -> null
        }
    }

    private fun parseFlac(file: File, includeArtwork: Boolean): AudioTrackMetadata? {
        val info = FlacMetadataParser.parse(file) ?: return null
        val art = if (includeArtwork && info.hasPicture) FlacMetadataParser.parseArtwork(file) else null
        return info.toAudioMetadata(art)
    }

    private fun FlacTrackInfo.toAudioMetadata(art: EmbeddedArtwork? = null) = AudioTrackMetadata(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: date,
        genre = genre,
        durationMs = durationMs,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channels = channels,
        bitrateKbps = bitrateKbps,
        embeddedLyrics = lyrics,
        hasEmbeddedArtwork = hasPicture,
        artwork = art,
        replayGainTrackDb = replayGainTrackDb,
    )

    private fun parseWav(file: File): AudioTrackMetadata? = runCatching {
        file.inputStream().buffered().use { parseWavStream(it, file.length()) }
    }.getOrNull()

    private fun parseWavStream(input: InputStream, fileSizeBytes: Long): AudioTrackMetadata? = runCatching {
        val header = ByteArray(12)
        if (input.read(header) != 12) return@runCatching null
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return@runCatching null

        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var byteRate = 0
        var dataSize = 0L

        val chunkHdr = ByteArray(8)
        while (input.read(chunkHdr) == 8) {
            val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
            val size = readLeUInt(chunkHdr, 4)
            if (id == "fmt " && size >= 16) {
                val fmt = ByteArray(16)
                if (input.read(fmt) != 16) break
                channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                sampleRate = readLeUInt(fmt, 4).toInt()
                byteRate = readLeUInt(fmt, 8).toInt()
                bitDepth = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                val rem = size - 16 + (size and 1L)
                if (rem > 0) skipExact(input, rem)
            } else if (id == "data") {
                dataSize = size
                break
            } else {
                val skipBytes = size + (size and 1L)
                if (skipBytes > 0) skipExact(input, skipBytes)
            }
        }
        if (sampleRate <= 0 || channels <= 0) return@runCatching null
        val durationMs = if (byteRate > 0 && dataSize > 0) (dataSize * 1000L) / byteRate
        else if (byteRate > 0 && fileSizeBytes > 44) ((fileSizeBytes - 44L) * 1000L) / byteRate
        else 0L
        AudioTrackMetadata(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bitrateKbps = if (byteRate > 0) (byteRate * 8) / 1000 else 0,
        )
    }.getOrNull()

    private fun readLeUInt(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24)

    private fun skipExact(input: InputStream, count: Long) {
        var rem = count
        while (rem > 0) {
            val s = input.skip(rem)
            if (s > 0) rem -= s
            else if (input.read() < 0) break
            else rem--
        }
    }
}
