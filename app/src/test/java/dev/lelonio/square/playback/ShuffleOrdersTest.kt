package dev.lelonio.square.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleOrdersTest {
    @Test
    fun tappedSongFirstAndEveryOtherStillToCome() {
        repeat(50) { seed ->
            val order = ShuffleOrders.startingAt(current = 4, count = 10, random = Random(seed))
            assertEquals(4, order.first())
            assertEquals((0 until 10).toSet(), order.toSet())
            assertEquals(10, order.size)
        }
    }

    @Test
    fun addedSongsLandAfterThePlayingOne() {
        repeat(50) { seed ->
            // Playing 2, which sits third in the order; 0 and 5 already played.
            val order = ShuffleOrders.withAhead(listOf(0, 5, 2, 1, 3, 4, 6, 7), added = 6..7, current = 2, random = Random(seed))
            assertEquals(listOf(0, 5, 2), order.take(3))
            assertEquals((0..7).toSet(), order.toSet())
            assertTrue(order.indexOf(6) > 2 && order.indexOf(7) > 2)
        }
    }

    @Test
    fun aDraggedSongTakesThePlaySlotOfTheOneItLandsOn() {
        // Playlist 0..5, playing 2, shuffled as 2 5 0 4 1 3.
        val order = listOf(2, 5, 0, 4, 1, 3)
        // Dragged from 4th (song 4) to 2nd place (song 5's slot): moveMediaItem(4, 5).
        val after = ShuffleOrders.movedOrder(order, 4, 5).map { ShuffleOrders.remap(it, 4, 5) }
        // Song 4 is now at list index 5, song 5 at 4: play order 2 4' 5' 0 1 3 in old names.
        org.junit.Assert.assertEquals(listOf(2, 5, 4, 0, 1, 3), after)
        // Moving down: song 5 to song 1's slot.
        val down = ShuffleOrders.movedOrder(order, 5, 1).map { ShuffleOrders.remap(it, 5, 1) }
        // List after the move: 0 5 1 2 3 4 → old 1→2, 2→3, 3→4, 4→5, 5→1.
        org.junit.Assert.assertEquals(listOf(3, 0, 5, 2, 1, 4), down)
    }
}
