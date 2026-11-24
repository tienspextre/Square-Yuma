package dev.lelonio.square.data

import kotlinx.serialization.Serializable

/**
 * One line of lyrics.
 *
 * @param startTimeMs when the line begins, or null for unsynced lyrics — the
 *   distinction decides whether the view can highlight along with playback.
 */
@Serializable
data class LyricLine(
    val startTimeMs: Long?,
    val text: String,
    /**
     * When each word of this line is sung, where the source says so.
     *
     * Empty for everything that only times whole lines, which is most of what
     * exists: Spotify's own lyrics and LrcLib both stop at the line. See
     * Ttml for the one that does not.
     */
    val words: List<LyricWord> = emptyList(),
    /**
     * The same line in the listener's language, where the source carries it.
     *
     * Only TTML has this, and only for a song worth translating: an Apple
     * document ships the subtitle track a label had made, so it is a real
     * translation rather than a machine's guess, and it is missing far more
     * often than not. Null means the view has nothing to show under the line.
     */
    val translation: String? = null,
    /** Sung by the second voice of a duet (TTML agent other than the first); drawn on the far side. */
    val secondVoice: Boolean = false,
)

/** One word, and the moment it belongs to. */
@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** A backing vocal (TTML `ttm:role="x-bg"`), sung over the main line rather than after it. */
    val background: Boolean = false,
)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** The LyricsSource it came from, by name; null for words kept before this was recorded. */
    val source: String? = null,
    /** Who to credit under the words, where the source requires it; see LyricsCredit. */
    val credit: LyricsCredit? = null,
)

/**
 * The credit a source asks to be shown wherever its words are: its name, and
 * for community syncs the person who uploaded them and the one who made them.
 */
@Serializable
data class LyricsCredit(
    val provider: String,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val maker: String? = null,
    val makerUrl: String? = null,
)
