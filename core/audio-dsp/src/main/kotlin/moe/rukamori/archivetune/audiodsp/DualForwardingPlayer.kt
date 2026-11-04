package moe.rukamori.archivetune.audiodsp

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.util.IdentityHashMap

class DualForwardingPlayer(
    private val playerA: Player,
) : ForwardingPlayer(playerA) {
    var activePlayer: Player = playerA
        private set

    fun attachPlayer(newPlayer: Player) {
        if (activePlayer === newPlayer) return
        val previous = activePlayer
        setWrappedPlayer(newPlayer)
        activePlayer = newPlayer
        migrateListeners(from = previous, to = newPlayer)
    }

    private fun setWrappedPlayer(newPlayer: Player) {
        playerField.set(this, newPlayer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun migrateListeners(from: Player, to: Player) {
        val map =
            runCatching {
                listenersField.get(this) as? IdentityHashMap<Player.Listener, Player.Listener>
            }.getOrNull() ?: return

        val timeline = to.currentTimeline
        val mediaItem = to.currentMediaItem
        val playbackState = to.playbackState
        val playWhenReady = to.playWhenReady
        val isPlaying = to.isPlaying

        val oldPos = Player.PositionInfo(
            null,
            from.currentMediaItemIndex,
            from.currentMediaItem,
            null,
            from.currentMediaItemIndex,
            from.currentPosition,
            from.contentPosition,
            -1,
            -1,
        )
        val newPos = Player.PositionInfo(
            null,
            to.currentMediaItemIndex,
            to.currentMediaItem,
            null,
            to.currentMediaItemIndex,
            to.currentPosition,
            to.contentPosition,
            -1,
            -1,
        )

        val flags = androidx.media3.common.FlagSet.Builder()
            .add(Player.EVENT_TIMELINE_CHANGED)
            .add(Player.EVENT_MEDIA_ITEM_TRANSITION)
            .add(Player.EVENT_MEDIA_METADATA_CHANGED)
            .add(Player.EVENT_POSITION_DISCONTINUITY)
            .add(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            .add(Player.EVENT_IS_PLAYING_CHANGED)
            .build()
        val events = Player.Events(flags)

        synchronized(map) {
            for (forwardingListener in map.values) {
                runCatching { from.removeListener(forwardingListener) }
                runCatching { to.addListener(forwardingListener) }
                runCatching {
                    forwardingListener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                    if (mediaItem != null) {
                        forwardingListener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        forwardingListener.onMediaMetadataChanged(mediaItem.mediaMetadata)
                    }
                    forwardingListener.onPositionDiscontinuity(oldPos, newPos, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    forwardingListener.onPlaybackParametersChanged(to.playbackParameters)
                    forwardingListener.onPlaybackStateChanged(playbackState)
                    forwardingListener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    forwardingListener.onIsPlayingChanged(isPlaying)
                    forwardingListener.onEvents(this, events)
                }
            }
        }
    }

    companion object {
        private val playerField =
            ForwardingPlayer::class.java.getDeclaredField("player").apply {
                isAccessible = true
            }

        private val listenersField =
            ForwardingPlayer::class.java.getDeclaredField("listeners").apply {
                isAccessible = true
            }
    }
}

enum class PlayerRole {
    MASTER,
    STANDBY,
}

data class DualPlayerRoleHolder(
    var playerA: PlayerRole = PlayerRole.MASTER,
    var playerB: PlayerRole = PlayerRole.STANDBY,
) {
    fun swap() {
        val temp = playerA
        playerA = playerB
        playerB = temp
    }

    fun reset() {
        playerA = PlayerRole.MASTER
        playerB = PlayerRole.STANDBY
    }
}

fun shouldUseLegacyPath(durationMs: Long): Boolean = durationMs <= 0L
