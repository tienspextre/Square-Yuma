package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueReorderTest {

    private fun song(id: String, artist: String) =
        SubsonicChildSong(id = id, title = "Track $id", artist = artist, duration = 180)

    @Test
    fun moveQueueItemReordersAndTracksCurrentIndex() {
        val a = song("a", "A")
        val b = song("b", "B")
        val c = song("c", "C")
        val d = song("d", "D")
        val queue = listOf(a, b, c, d)

        // Move item after current (c at 2 -> 3)
        val (q1, idx1) = moveQueueItem(queue, currentIndex = 1, fromIndex = 2, toIndex = 3)
        assertEquals(listOf(a, b, d, c), q1)
        assertEquals(1, idx1)

        // Move item across current (d at 3 -> 0)
        val (q2, idx2) = moveQueueItem(queue, currentIndex = 1, fromIndex = 3, toIndex = 0)
        assertEquals(listOf(d, a, b, c), q2)
        assertEquals(2, idx2)

        // Move current item itself (b at 1 -> 3)
        val (q3, idx3) = moveQueueItem(queue, currentIndex = 1, fromIndex = 1, toIndex = 3)
        assertEquals(listOf(a, c, d, b), q3)
        assertEquals(3, idx3)
    }

    @Test
    fun smartShuffleKeepsCurrentFirstAndAvoidsAdjacentSameArtistWhenPossible() {
        val s1 = song("1", "Alpha")
        val s2 = song("2", "Alpha")
        val s3 = song("3", "Beta")
        val s4 = song("4", "Gamma")
        val s5 = song("5", "Beta")

        val shuffled = smartShuffleOrder(
            items = listOf(s1, s2, s3, s4, s5),
            currentIndex = 0,
            artistOf = { it.artist },
            random = Random(42),
        )
        assertEquals(s1, shuffled.first())
        assertEquals(5, shuffled.size)
        assertEquals(setOf("1", "2", "3", "4", "5"), shuffled.map { it.id }.toSet())
        // With Alpha appearing twice and 3 other tracks, smartShuffle avoids placing s2 right after s1.
        assertTrue("Second track should not share artist with first", shuffled[1].artist != s1.artist)
    }
}
