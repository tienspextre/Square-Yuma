package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import dev.lelonio.square.playback.ShuffleOrders.shuffleFromCurrent
import dev.lelonio.square.playback.ShuffleOrders.afterInsert
import dev.lelonio.square.playback.ShuffleOrders.moveInPlayOrder
import dev.lelonio.square.playback.ShuffleOrders.queueInsertAt

/** Keeps the public queue on the main player while its outgoing audio overlaps the next song. */
@UnstableApi
class SkipFadePlayer(
    player: Player,
    private val fades: YouTubeFadeController,
    /** Zero while the listener has the setting off; see PreferencesStore. */
    private val fadeMs: () -> Int,
) : ForwardingPlayer(player) {

    private fun changing(change: () -> Unit) = fades.changeWith(fadeMs(), change)

    override fun release() {
        fades.release()
        super.release()
        fades.releaseMixer()
    }

    // Hand-queued songs land after the song playing (see ShuffleOrders.queueInsertAt); ExoPlayer
    // on its own appends, which put a queued song behind the rest of the playlist or the radio.

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(mutableListOf(mediaItem))

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) = addMediaItems(mediaItemCount, mediaItems)

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        val at = queueInsertAt(mediaItems, index)
        super.addMediaItems(at, mediaItems)
        exo?.afterInsert(at, mediaItems)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val e = exo ?: return super.moveMediaItem(currentIndex, newIndex)
        e.moveInPlayOrder(currentIndex, newIndex) { super.moveMediaItem(currentIndex, newIndex) }
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val e = exo
        if (e == null || toIndex != fromIndex + 1) return super.moveMediaItems(fromIndex, toIndex, newIndex)
        e.moveInPlayOrder(fromIndex, newIndex) { super.moveMediaItems(fromIndex, toIndex, newIndex) }
    }

    override fun seekToNext() = changing { super.seekToNext() }

    override fun seekToNextMediaItem() = changing { super.seekToNextMediaItem() }

    override fun seekToPrevious() = changing { super.seekToPrevious() }

    override fun seekToPreviousMediaItem() = changing { super.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
        } else {
            changing { super.seekTo(mediaItemIndex, positionMs) }
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekToDefaultPosition(mediaItemIndex)
        } else {
            changing { super.seekToDefaultPosition(mediaItemIndex) }
        }
    }

    override fun setMediaItem(mediaItem: MediaItem) = changing { super.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        changing { super.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        changing { super.setMediaItem(mediaItem, resetPosition) }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) =
        changing { super.setMediaItems(mediaItems); exo?.shuffleFromCurrent() }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
        changing { super.setMediaItems(mediaItems, resetPosition); exo?.shuffleFromCurrent() }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = changing { super.setMediaItems(mediaItems, startIndex, startPositionMs); exo?.shuffleFromCurrent() }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        // From the song playing: everything else, before it in the list or after, is still to come.
        if (shuffleModeEnabled) exo?.shuffleFromCurrent()
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    private val exo get() = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer
}
