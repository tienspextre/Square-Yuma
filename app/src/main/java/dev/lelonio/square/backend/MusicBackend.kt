package dev.lelonio.square.backend

import androidx.media3.common.Player

/** The catalogue half, [MusicCatalog], is shared with the desktop; a backend here also plays through a media3 [Player]. */
interface MusicBackend : MusicCatalog {
    /**
     * Builds the [Player] this backend plays through.
     *
     * Called once by `PlaybackService` for the selected backend; the caller owns
     * the player from there — releasing it, and attaching it to the session.
     */
    fun createPlayer(host: PlaybackHost): Player
}

/**
 * What a backend's player needs from the service hosting it.
 *
 * Kept narrow on purpose: a player reaching back into `PlaybackService` would
 * couple every backend to Spotify's queue-saving and engine-restart logic, none
 * of which applies to a backend that has no native engine.
 */
interface PlaybackHost {
    val context: android.content.Context
    val looper: android.os.Looper
    fun onActivePlayerChanged(player: androidx.media3.common.Player) {}
}
