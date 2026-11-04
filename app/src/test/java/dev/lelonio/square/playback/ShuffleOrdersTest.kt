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
}
