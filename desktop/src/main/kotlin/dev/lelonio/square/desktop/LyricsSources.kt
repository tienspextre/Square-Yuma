package dev.lelonio.square.desktop

import dev.lelonio.square.backend.lyrics.BetterLyrics
import dev.lelonio.square.backend.lyrics.KuGou
import dev.lelonio.square.backend.lyrics.LrcLib
import dev.lelonio.square.backend.lyrics.Lossless
import dev.lelonio.square.backend.lyrics.LyricsOvh
import dev.lelonio.square.backend.lyrics.NetEase
import dev.lelonio.square.backend.lyrics.Translate
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics

/**
 * Where the words can come from, in the order the phone asks for a NAS song
 * (LyricsLibrary.sourcesFor): the server's own first, then the online
 * libraries. Spotify's, AMLL's and Spicy Lyrics' need a Spotify track, so they
 * are not offered for a Navidrome library on the phone either.
 */
enum class LyricSource(val label: String) {
    NAVIDROME("Navidrome"),
    APPLE_MUSIC("Apple Music"),
    BETTER_LYRICS("Better Lyrics"),
    LRCLIB("LRCLIB"),
    NETEASE("NetEase"),
    KUGOU("KuGou"),
    LYRICS_OVH("Lyrics.ovh"),
}

suspend fun fetchLyrics(state: AppState, s: SubsonicChildSong, source: LyricSource): Lyrics? = runCatching {
    val title = s.title
    val artist = s.artist.orEmpty()
    val ms = s.duration * 1000
    when (source) {
        LyricSource.NAVIDROME -> state.client.getLyrics(s.id).getOrNull().orEmpty().takeIf { it.isNotEmpty() }?.let { lines ->
            val synced = lines.any { it.start > 0 }
            Lyrics(lines.map { LyricLine(it.start.takeIf { synced }, it.value) }, synced = synced, source = "Navidrome")
        }
        LyricSource.APPLE_MUSIC -> Lossless.lyrics(title, artist, ms)
        LyricSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, ms)
        LyricSource.LRCLIB -> LrcLib.lyrics(title, artist, ms)
        LyricSource.NETEASE -> NetEase.lyrics(title, artist, ms)
        LyricSource.KUGOU -> KuGou.lyrics(title, artist, ms)
        LyricSource.LYRICS_OVH -> LyricsOvh.lyrics(title, artist, ms)
    }
}.getOrNull()?.takeIf { it.lines.isNotEmpty() }

/** The listener's pick for this song if there is one, else the first source that has it, timed words preferred over plain text. */
suspend fun bestLyrics(state: AppState, s: SubsonicChildSong): Pair<LyricSource, Lyrics>? {
    state.lyricsPick(s.id)?.let { pick -> fetchLyrics(state, s, pick)?.let { return pick to it } }
    var plain: Pair<LyricSource, Lyrics>? = null
    for (src in LyricSource.entries) {
        val l = fetchLyrics(state, s, src) ?: continue
        if (l.synced) return src to l
        if (plain == null) plain = src to l
    }
    return plain
}

/** Each line with the translator's answer under it, unless the document brought its own. */
suspend fun translated(lyrics: Lyrics, target: String): Lyrics {
    if (lyrics.lines.any { !it.translation.isNullOrBlank() }) return lyrics
    val answers = Translate.lines(lyrics.lines.map { it.text }, target) ?: return lyrics
    return lyrics.copy(lines = lyrics.lines.mapIndexed { i, l -> l.copy(translation = answers.getOrNull(i)?.takeIf { it.isNotBlank() }) })
}
