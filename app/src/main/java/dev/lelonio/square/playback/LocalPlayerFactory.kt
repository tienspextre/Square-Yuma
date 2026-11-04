package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.data.LocalLibrary

/**
 * The player for music that is already on the phone.
 *
 * Nothing to fetch and nothing to decrypt, so this is ExoPlayer as it comes,
 * reading a file through the content resolver. The only piece of plumbing is
 * the resolver below.
 */
@OptIn(UnstableApi::class)
object LocalPlayerFactory {

    fun create(host: PlaybackHost): Player = dev.lelonio.square.playback.engine.YumaDualDeckPlayer(host)

    /**
     * The sink, with the app's own vocoder in its chain.
     *
     * Speed and pitch otherwise fall to the platform's stretcher, which is
     * built for speech and sounds like it on music — and the same song altered
     * the same amount would sound worse from the phone than from Spotify. See
     * BungeeAudioProcessor.
     */
    internal fun renderers(host: PlaybackHost) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // The hardware path applies speed itself, below the processors,
                // which would leave the vocoder with nothing to do and the
                // platform doing it after all.
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioProcessorChain(BungeeProcessorChain())
                .build()
        }
}
