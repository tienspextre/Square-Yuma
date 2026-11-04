package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle orders for the ExoPlayer-backed players, so that nothing counts as
 * played before it has been.
 *
 * ExoPlayer's own shuffle is a random permutation of the whole queue, and the
 * song playing sits wherever it fell in it: everything placed before it was
 * treated as already heard. Tapping the fifth song of an album with shuffle on
 * skipped a random half of the album; so did every song added to the queue
 * and every Automix handoff, which reshuffled the queue on the other deck.
 */
@OptIn(UnstableApi::class)
object ShuffleOrders {

    /** The queue in the order shuffle will play it. */
    fun of(timeline: Timeline): MutableList<Int> {
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    /** [current] first and everything else after it, in random order. */
    fun startingAt(current: Int, count: Int, random: Random = Random): List<Int> =
        listOf(current) + (0 until count).filter { it != current }.shuffled(random)

    /** [order] with each of [added] moved to a random place after [current]. */
    fun withAhead(order: List<Int>, added: IntRange, current: Int, random: Random = Random): List<Int> {
        val out = order.filterNot { it in added }.toMutableList()
        val after = out.indexOf(current) + 1
        added.forEach { out.add(random.nextInt(after, out.size + 1), it) }
        return out
    }

    /** Reshuffles with the song playing first: the rest are all still to come. */
    fun ExoPlayer.shuffleFromCurrent() {
        val count = mediaItemCount
        if (count < 2 || currentMediaItemIndex !in 0 until count) return
        apply(startingAt(currentMediaItemIndex, count))
    }

    /** After an insert at [at]: the [count] new songs go after the one playing, not wherever ExoPlayer put them. */
    fun ExoPlayer.shuffleInAhead(at: Int, count: Int) {
        if (!shuffleModeEnabled || count <= 0) return
        val order = of(currentTimeline)
        if (order.size != mediaItemCount) return
        apply(withAhead(order, at until at + count, currentMediaItemIndex))
    }

    /** [source]'s shuffled order, so a handoff carries on through the same order instead of a new one. */
    fun ExoPlayer.copyShuffleOrder(source: Player) {
        val order = of(source.currentTimeline)
        if (order.size == mediaItemCount && order.isNotEmpty()) apply(order)
    }

    private fun ExoPlayer.apply(order: List<Int>) =
        setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
}
