package dev.lelonio.square.ui.player

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedLyricsTest {
    @Test
    fun backingVocalsBecomeTheirOwnLineAndOverlapsStayLit() {
        val lyrics = Lyrics(
            synced = true,
            lines = listOf(
                LyricLine(
                    startTimeMs = 1000,
                    text = "Hello there (ooh)",
                    words = listOf(
                        LyricWord(1000, 1400, "Hello"),
                        LyricWord(1400, 2600, "there"),
                        LyricWord(1500, 2500, "(ooh)", background = true),
                    ),
                ),
                LyricLine(startTimeMs = 2200, text = "second", words = listOf(LyricWord(2200, 3000, "second")), secondVoice = true),
                LyricLine(startTimeMs = 3000, text = ""),
                LyricLine(startTimeMs = 9000, text = "plain line"),
            ),
        )
        val synced = lyrics.toSyncedLyrics()
        assertEquals(3, synced.lines.size)

        val first = synced.lines[0] as KaraokeLine.MainKaraokeLine
        assertEquals("Hello there ", first.syllables.joinToString("") { it.content })
        assertEquals(1000 to 2600, first.start to first.end)
        assertEquals("(ooh)", first.accompanimentLines!!.single().syllables.single().content)
        assertEquals(KaraokeAlignment.End, (synced.lines[1] as KaraokeLine).alignment)

        // Both lines are being sung at 2400ms: both are lit.
        assertEquals(listOf(0, 1), synced.getCurrentAllHighlightLineIndicesByTime(2400))

        // The last line-timed line holds for four seconds, not forever.
        val last = synced.lines[2] as SyncedLine
        assertEquals(9000 to 13000, last.start to last.end)
    }
}
