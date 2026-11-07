package dev.lelonio.square.data

import kotlinx.serialization.Serializable

@Serializable
data class CatalogTrack(
    val uri: String,
    val name: String,
    val artist: String,
    val artistUri: String? = null,
    val artists: List<CatalogArtist> = emptyList(),
    val album: String = "",
    val albumUri: String? = null,
    val year: String = "",
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val smartRecommended: Boolean = false,
    val artworkUrl: String? = null,
    val addedAt: String? = null,
    val localFile: String? = null,
    val releasedAt: String? = null,
    val resumeMs: Long = 0,
    val played: Boolean = false,
    val hasVideo: Boolean = false,
    val summary: String = "",
    val suffix: String = "flac",
    val bitRateKbps: Int = 0,
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
) {
    val isEpisode: Boolean
        get() = uri.startsWith("spotify:episode:") || releasedAt != null

    val qualityBadge: String
        get() {
            val ext = suffix.uppercase().ifBlank { "FLAC" }
            return when {
                ext == "FLAC" || ext == "ALAC" || ext == "WAV" -> {
                    val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}" else String.format("%.1f", sampleRateHz / 1000.0)
                    if (bitDepth > 0 && sampleRateHz > 0) "$ext $bitDepth-bit / $khz kHz"
                    else if (bitRateKbps > 0) "$ext • $bitRateKbps kbps"
                    else "$ext LOSSLESS"
                }
                bitRateKbps > 0 -> "$ext $bitRateKbps kbps"
                else -> ext
            }
        }

    val isHiRes: Boolean
        get() = bitDepth >= 24 || sampleRateHz > 48000
}

@Serializable
data class LyricLine(
    val startTimeMs: Long?,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val secondVoice: Boolean = false,
)

@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val background: Boolean = false,
)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String? = null,
    val credit: LyricsCredit? = null,
)

@Serializable
data class LyricsCredit(
    val provider: String,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val maker: String? = null,
    val makerUrl: String? = null,
)

data class CanvasClip(val url: String, val isVideo: Boolean)

@Serializable
data class CatalogPlaylist(
    val uri: String,
    val name: String,
    val artworkUrl: String? = null,
    val subtitle: String? = null,
    val isArtist: Boolean = false,
    val mine: Boolean? = null,
    val songCount: Int = 0,
)

@Serializable
data class CatalogArtist(val name: String, val uri: String? = null)
