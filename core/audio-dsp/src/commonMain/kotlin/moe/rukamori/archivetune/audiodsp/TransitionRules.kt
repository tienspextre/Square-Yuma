/*
 * Which pairs Automix leaves alone, after BitChord's TransitionPlanner
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0; from Orchard, AGPL-3.0).
 */
package moe.rukamori.archivetune.audiodsp

object TransitionRules {
    /** Spoken word, in any of a track's title, artist or album: mixing it is never wanted. */
    private val SPOKEN_TEXT = Regex("""\b(podcast|episode|audiobook|concert)\b""", RegexOption.IGNORE_CASE)

    /**
     * A live recording, by how a release labels one: "(Live)", "[Live at Wembley]", "Song - Live",
     * an album "Live at …" / "Live: …". Not the bare word, which matched "Live It Up".
     */
    private val LIVE_TEXT = Regex(
        """[(\[]\s*(?:[^)\]]*\s)?live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b[^)\]]*)?\s*[)\]]""" +
            """|[-–—]\s*live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b.*)?\s*$""" +
            """|^\s*live\s*(?::|\s(?:at|from|in|on)\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun isSpokenOrLive(title: String, artist: String, album: String): Boolean =
        SPOKEN_TEXT.containsMatchIn("$title $artist $album") ||
            // Not the artist: there is a band called Live.
            LIVE_TEXT.containsMatchIn(title) || LIVE_TEXT.containsMatchIn(album)

    /**
     * The whole queue is one album (same title and album artist), played in order: an album being
     * played through, which plays gapless rather than mixed. Album siblings that land next to each
     * other in a playlist or a shuffle still mix.
     */
    fun isAlbumPlaythrough(queue: List<Pair<String?, String?>>, shuffled: Boolean): Boolean {
        if (shuffled || queue.size < 2) return false
        val (album, artist) = queue.first()
        if (album.isNullOrBlank()) return false
        return queue.all { it.first == album && it.second == artist }
    }
}
