package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    // ---- Hand-queued songs ("Add to queue" / "Play next") ----

    fun isQueued(item: MediaItem) = item.mediaMetadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_PLAY_NEXT) == true

    private fun isFirst(item: MediaItem) = item.mediaMetadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_PLAY_FIRST) == true

    /**
     * Where [items] go in the list: "Play next" right after the song playing, "Add to queue"
     * after the run already queued behind it (as on the Spotify side, PlayQueue.insertNext).
     * Anything else stays at [requested].
     */
    fun Player.queueInsertAt(items: List<MediaItem>, requested: Int): Int {
        if (items.isEmpty() || !items.all(::isQueued)) return requested
        if (mediaItemCount == 0) return 0
        if (items.all(::isFirst)) return currentMediaItemIndex + 1
        var at = currentMediaItemIndex + 1
        while (at < mediaItemCount && isQueued(getMediaItemAt(at))) at++
        return at
    }

    /**
     * After an insert at [at]: shuffled, hand-queued songs are placed the same way in the play
     * order (ExoPlayer gave them a random place, so "play next" played a random song next);
     * anything else goes somewhere after the song playing.
     */
    fun ExoPlayer.afterInsert(at: Int, items: List<MediaItem>) {
        val count = items.size
        if (!shuffleModeEnabled || count <= 0) return
        if (!items.all(::isQueued)) return shuffleInAhead(at, count)
        val order = of(currentTimeline)
        if (order.size != mediaItemCount) return
        val added = (at until at + count).toList()
        order.removeAll(added.toSet())
        var place = order.indexOf(currentMediaItemIndex) + 1
        if (!items.all(::isFirst)) while (place in order.indices && isQueued(getMediaItemAt(order[place]))) place++
        order.addAll(place.coerceIn(0, order.size), added)
        apply(order)
    }

    // ---- Moving in the order songs play ----

    /** A list index after moving the item at [from] to [to]. */
    fun remap(i: Int, from: Int, to: Int): Int = when {
        i == from -> to
        from < to && i in from + 1..to -> i - 1
        from > to && i in to until from -> i + 1
        else -> i
    }

    /** [order] with [from] taking the play slot of [target], as a dragged row does (pre-move indices). */
    fun movedOrder(order: List<Int>, from: Int, target: Int): List<Int> {
        val slot = order.indexOf(target)
        if (slot < 0 || from == target) return order
        return order.toMutableList().apply { remove(from); add(slot.coerceIn(0, size), from) }
    }

    /**
     * moveMediaItem(from, to) that, shuffled, also moves the song to [to]'s place in the play
     * order. The queue shows the play order, so that is where a drag means. ExoPlayer on its own
     * reinserted the song at a random place in it.
     */
    fun ExoPlayer.moveInPlayOrder(from: Int, to: Int, move: () -> Unit) {
        if (!shuffleModeEnabled || from == to) return move()
        val order = of(currentTimeline)
        if (order.size != mediaItemCount) return move()
        val next = movedOrder(order, from, to).map { remap(it, from, to) }
        move()
        apply(next)
    }

    private fun ExoPlayer.apply(order: List<Int>) =
        setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
}
