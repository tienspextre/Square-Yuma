package dev.lelonio.square.playback.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.data.LocalLibrary
import dev.lelonio.square.playback.AudioEffects
import dev.lelonio.square.playback.AutomixStateHolder
import dev.lelonio.square.playback.BungeeAudioProcessor
import dev.lelonio.square.playback.BungeeProcessorChain
import dev.lelonio.square.playback.ShuffleOrders.copyShuffleOrder
import dev.lelonio.square.playback.ShuffleOrders.shuffleFromCurrent
import dev.lelonio.square.playback.ShuffleOrders.shuffleInAhead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiodsp.AutomixPlan
import moe.rukamori.archivetune.audiodsp.DjFilterAudioProcessor
import moe.rukamori.archivetune.audiodsp.DualForwardingPlayer
import moe.rukamori.archivetune.audiodsp.MixFeatures
import moe.rukamori.archivetune.audiodsp.PairPlanner
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import moe.rukamori.archivetune.audiodsp.TransitionPlanner
import moe.rukamori.archivetune.audiodsp.TransitionTier
import moe.rukamori.archivetune.audiodsp.requiredStartBufferMs
import moe.rukamori.archivetune.playback.engine.ExoDeck
import moe.rukamori.archivetune.playback.smart.TrackAnalyzer
import kotlin.math.abs
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class YumaDualDeckPlayer private constructor(
    val host: PlaybackHost,
    val playerA: ExoPlayer,
    val playerB: ExoPlayer,
    val filterA: DjFilterAudioProcessor,
    val filterB: DjFilterAudioProcessor,
    val bungeeA: BungeeAudioProcessor,
    val bungeeB: BungeeAudioProcessor,
    val forwardingPlayer: DualForwardingPlayer,
) : Player by forwardingPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hardwareEffectsManager = HardwareAudioEffectsManager(host.context, scope)

    private var deckA = ExoDeck(playerA, filterA)
    private var deckB = ExoDeck(playerB, filterB)

    private var activeDeck: ExoDeck = deckA
    private var standbyDeck: ExoDeck = deckB

    private var crossfadeJob: Job? = null
    private var isCrossfading = false
    // The user's play/pause intent during a fade. Not outgoing.playWhenReady: ExoPlayer clears
    // that itself when pauseAtEndOfMediaItems parks the outgoing deck, which read as a user
    // pause and froze the incoming deck for good.
    private var crossfadePlayRequested = false
    // Only the active deck may own audio focus. With both handling it, the incoming deck's focus
    // request at fade start made Android take focus from the outgoing deck, and ExoPlayer paused
    // it: the "song 1 cuts out, song 2 fades in" bug, and the freeze when the ramp read that pause.
    private var focusHandled = true
    private var userVolume = 1f
    private var schedulerJob: Job? = null

    private var preparedStandbyIndex: Int = C.INDEX_UNSET
    private var preparedStandbyMediaId: String? = null
    private var isStandbyPrimed: Boolean = false

    private var lastTriggeredIndex: Int = C.INDEX_UNSET
    private var lastTriggeredMediaId: String? = null
    private var lastSeekTimestamp: Long = 0L

    // The listener's own speed/pitch. A beat-matched handoff leaves the new song at the old one's
    // tempo and eases it back here, so the decks' live parameters are not always this.
    private var userParams = PlaybackParameters(AudioEffects.speed.value, AudioEffects.pitch.value)
    private var tempoEaseJob: Job? = null

    private class PairCache(
        val out: TrackAnalysisResult, val inc: TrackAnalysisResult,
        val durationMs: Long, val fadeMs: Long, val bassSwap: Boolean, val plan: AutomixPlan?,
    )
    private var pairCache: PairCache? = null

    /** The two songs' own meeting point, or null to fall back to the one-sided planner. */
    private fun pairPlan(
        out: TrackAnalysisResult, inc: TrackAnalysisResult, durationMs: Long, fadeMs: Long,
    ): AutomixPlan? {
        val bass = AudioEffects.automixBassSwap.value
        pairCache?.let {
            if (it.out === out && it.inc === inc && it.durationMs == durationMs && it.fadeMs == fadeMs && it.bassSwap == bass) return it.plan
        }
        val plan = runCatching {
            val o = MixFeatures.parse(out.rawJson, out.tailRawJson, out.tailOffset) ?: return@runCatching null
            val i = MixFeatures.parse(inc.rawJson) ?: return@runCatching null
            PairPlanner.plan(o, i, durationMs, fadeMs, bass)
        }.getOrNull()
        pairCache = PairCache(out, inc, durationMs, fadeMs, bass, plan)
        return plan
    }

    private fun qualityLabel(f: androidx.media3.common.Format): String? {
        val mime = f.sampleMimeType ?: return null
        val rateKhz = f.sampleRate.takeIf { it > 0 }?.let {
            val k = it / 1000.0
            if (k % 1.0 == 0.0) k.toInt().toString() else "%.1f".format(java.util.Locale.US, k)
        }
        val bits = when (f.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        val lossless = mime == androidx.media3.common.MimeTypes.AUDIO_FLAC ||
            mime == androidx.media3.common.MimeTypes.AUDIO_ALAC ||
            mime == androidx.media3.common.MimeTypes.AUDIO_RAW ||
            mime == androidx.media3.common.MimeTypes.AUDIO_WAV
        if (lossless) {
            // Apple's split: above 48 kHz is Hi-Res Lossless.
            val name = if (f.sampleRate > 48_000) "Hi-Res Lossless" else "Lossless"
            val detail = listOfNotNull(bits?.toString(), rateKhz).joinToString("/")
            return if (detail.isEmpty()) name else "$name · $detail"
        }
        val codec = when (mime) {
            androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
            androidx.media3.common.MimeTypes.AUDIO_MPEG -> "MP3"
            androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
            androidx.media3.common.MimeTypes.AUDIO_VORBIS -> "Vorbis"
            else -> mime.substringAfter('/').uppercase()
        }
        val kbps = f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }
        return listOfNotNull(codec, kbps).joinToString(" · ")
    }

    companion object {
        private const val READY_TIMEOUT_MS = 5_000L
        private const val FRAME_MS = 32L
        // -16 LUFS: the common streaming reference; tracks louder than this are pulled down to it.
        private const val TARGET_LUFS = -16.0
        private const val MIN_NORM_GAIN = 0.25f // never more than -12 dB

        // Beat-phase hold during a matched overlap: every half second, trim the incoming speed so
        // the drift is gone in about two seconds, never by more than 4% (inaudible on Bungee).
        private const val NUDGE_EVERY_MS = 500L
        private const val NUDGE_HORIZON_MS = 2000.0
        private const val MAX_NUDGE = 0.04f
        private const val PHASE_TOLERANCE_MS = 12.0
        // Further off than this is a stall or a bad grid, not drift: leave it a plain crossfade.
        private const val PHASE_GIVE_UP_MS = 400.0
        // ponytail: calibration knob. Bungee's window delays a stretched deck against one at
        // rest; if matched kicks flam on a device, set this to that delay.
        private const val STRETCH_LATENCY_MS = 0.0

        // automix-engine brings the new song back to its own tempo over ~6.5 s after the handoff.
        private const val TEMPO_EASE_MS = 6500L
        private const val EASE_STEP_MS = 250L

        operator fun invoke(host: PlaybackHost): YumaDualDeckPlayer {
            val filterA = DjFilterAudioProcessor()
            val filterB = DjFilterAudioProcessor()
            val bungeeA = BungeeAudioProcessor()
            val bungeeB = BungeeAudioProcessor()
            val playerA = buildDeckPlayer(host, filterA, bungeeA, handleAudioFocus = true)
            val playerB = buildDeckPlayer(host, filterB, bungeeB, handleAudioFocus = false)
            val forwarding = DualForwardingPlayer(playerA)
            return YumaDualDeckPlayer(
                host = host,
                playerA = playerA,
                playerB = playerB,
                filterA = filterA,
                filterB = filterB,
                bungeeA = bungeeA,
                bungeeB = bungeeB,
                forwardingPlayer = forwarding,
            )
        }

        private fun buildDeckPlayer(
            host: PlaybackHost,
            djFilter: DjFilterAudioProcessor,
            bungee: BungeeAudioProcessor,
            handleAudioFocus: Boolean,
        ): ExoPlayer {
            val renderers = object : DefaultRenderersFactory(host.context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ) = DefaultAudioSink.Builder(context)
                    // Float output makes DefaultAudioSink skip the whole AudioProcessorChain: no Bungee
                    // (speed/pitch sprang back to 1.0) and no DjFilter (automix lost its filter sweep).
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessorChain(BungeeProcessorChain(bungee, arrayOf(djFilter)))
                    .build()
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(
                    DefaultDataSource.Factory(host.context),
                    ResolvingDataSource.Resolver { spec ->
                        val uri = spec.uri.toString()
                        if (LocalLibrary.isLocal(uri)) {
                            spec.buildUpon().setUri(LocalLibrary.contentUri(uri)).build()
                        } else if (uri.startsWith("subsonic:track:")) {
                            val app = host.context.applicationContext as? dev.lelonio.square.SquareApplication
                            val offlineUri = app?.offlineTrackStore?.getLocalUri(uri)
                            if (offlineUri != null) {
                                spec.buildUpon().setUri(offlineUri).build()
                            } else {
                                val songId = uri.removePrefix("subsonic:track:")
                                val streamUrl = app?.subsonicClient?.buildStreamUrl(songId).orEmpty()
                                if (streamUrl.isNotBlank()) {
                                    spec.buildUpon().setUri(android.net.Uri.parse(streamUrl)).build()
                                } else {
                                    spec
                                }
                            }
                        } else {
                            spec
                        }
                    },
                ),
            )

            return ExoPlayer.Builder(host.context, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .setHandleAudioBecomingNoisy(true)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    handleAudioFocus,
                )
                .setLooper(host.looper)
                .build()
        }
    }

    init {
        TrackAnalyzer.init(host.context)
        hardwareEffectsManager.attachSession(playerA.audioSessionId)

        forwardPlayerEvents(playerA)
        forwardPlayerEvents(playerB)

        // Offload hands decoding to the DSP chip, which bypasses every AudioProcessor (DJ filter,
        // Bungee speed/pitch) and cannot overlap two decks. Toggling it mid-playback re-selects
        // tracks and rebuilds the AudioTrack: that rebuild was the cut on the outgoing song at
        // crossfade start and the blip at handoff. So it is decided from settings only, never per fade.
        merge(
            AudioEffects.audioOffload,
            AudioEffects.smartAutomix,
            AudioEffects.crossfadeSeconds,
            AudioEffects.speed,
            AudioEffects.pitch,
            AudioEffects.reverb,
        ).onEach {
            val needsDsp = AudioEffects.smartAutomix.value || AudioEffects.crossfadeSeconds.value > 0 ||
                AudioEffects.speed.value != 1f || AudioEffects.pitch.value != 1f || AudioEffects.reverb.value > 0f
            val enabled = AudioEffects.audioOffload.value && !needsDsp
            applyOffload(playerA, enabled)
            applyOffload(playerB, enabled)
        }.launchIn(scope)

        // When new track analysis is available, wake up scheduler immediately
        TrackAnalyzer.analysisEvents.onEach { (trackId, analysis) ->
            val activeItem = activeDeck.player.currentMediaItem
            if (activeItem?.mediaId == trackId) {
                updateAutomixStateForCurrentTrack(analysis)
            }
        }.launchIn(scope)

        startScheduler()
    }

    private fun applyOffload(player: ExoPlayer, enabled: Boolean) {
        runCatching {
            val offloadMode = if (enabled) {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
            val prefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(offloadMode)
                .setIsGaplessSupportRequired(true)
                .build()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(prefs)
                .build()
        }
    }

    private fun forwardPlayerEvents(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (player === activeDeck.player && !isCrossfading && tempoEaseJob?.isActive != true) {
                    AudioEffects.rememberSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player === activeDeck.player && !isCrossfading) {
                    lastTriggeredIndex = C.INDEX_UNSET
                    lastTriggeredMediaId = null
                    releaseStandby()
                    applyRestingVolume()
                    triggerAnalysisForCurrentAndNext()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (player === activeDeck.player) publishQuality()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === activeDeck.player && playbackState == Player.STATE_READY) {
                    triggerAnalysisForCurrentAndNext()
                }
            }
        })
    }

    private fun triggerAnalysisForCurrentAndNext() {
        val active = activeDeck.player
        val currentItem = active.currentMediaItem
        val nextIndex = nextIndexOf(active)
        val nextItem = if (nextIndex != C.INDEX_UNSET) runCatching { active.getMediaItemAt(nextIndex) }.getOrNull() else null

        kickOffAnalysis(currentItem)
        kickOffAnalysis(nextItem)

        currentItem?.mediaId?.let { id ->
            val cached = TrackAnalyzer.getCached(id)
            updateAutomixStateForCurrentTrack(cached)
        }
    }

    // Media3's own next index honours shuffle order and REPEAT_ALL; currentIndex + 1 mixed into
    // the wrong song whenever shuffle was on. REPEAT_ONE is mapped to OFF for navigation, so guard it.
    private fun nextIndexOf(player: Player): Int =
        if (player.repeatMode == Player.REPEAT_MODE_ONE) C.INDEX_UNSET else player.nextMediaItemIndex

    private fun kickOffAnalysis(mediaItem: MediaItem?) {
        if (mediaItem == null) return
        val mediaId = mediaItem.mediaId.ifBlank { mediaItem.localConfiguration?.uri?.toString().orEmpty() }
        if (mediaId.isBlank()) return
        if (TrackAnalyzer.hasCached(mediaId) || TrackAnalyzer.shouldThrottleKickOff(mediaId)) return

        scope.launch(Dispatchers.IO) {
            val cached = TrackAnalyzer.getOrFetchCached(mediaId)
            if (cached != null) {
                applyAnalysisIfCurrent(mediaId, cached)
                return@launch
            }

            val app = host.context.applicationContext as? dev.lelonio.square.SquareApplication
            val isSubsonic = mediaId.startsWith("subsonic:track:")
            val offlineUri = if (isSubsonic) app?.offlineTrackStore?.getLocalUri(mediaId) else null
            if (isSubsonic && offlineUri == null) {
                // Streamed: decode the head and tail over HTTP so the outro (fade to silence) is
                // found, instead of mixing blind at duration - fade into a quiet tail.
                val durationSec = mediaItem.mediaMetadata.durationMs?.takeIf { it > 0 }?.div(1000.0)
                    ?: return@launch
                val url = app?.subsonicClient?.buildStreamUrl(mediaId.removePrefix("subsonic:track:"))
                    ?.takeIf { it.isNotBlank() } ?: return@launch
                val result = TrackAnalyzer.analyzeUrl(mediaId, url, durationSec)
                if (result != null) applyAnalysisIfCurrent(mediaId, result)
                return@launch
            }
            val uri = if (offlineUri != null) {
                offlineUri
            } else {
                // A phone file's item URI is its `local:track:` id, which no
                // resolver opens: the analysis failed for every one of them.
                (if (LocalLibrary.isLocal(mediaId)) LocalLibrary.contentUri(mediaId) else null)
                    ?: mediaItem.localConfiguration?.uri
                    ?: runCatching { android.net.Uri.parse(mediaId) }.getOrNull()
                    ?: return@launch
            }

            val durationSec = mediaItem.mediaMetadata.durationMs?.takeIf { it > 0 }?.toDouble()?.div(1000.0)

            val result = TrackAnalyzer.analyze(
                trackId = mediaId,
                context = host.context,
                uri = uri,
                durationSeconds = durationSec,
            )
            if (result != null) applyAnalysisIfCurrent(mediaId, result)
        }
    }

    /** The badge under the seek bar: the active deck's real audio format, Apple Music style. */
    private fun publishQuality() {
        val format = activeDeck.player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
        val label = format?.let(::qualityLabel)
        AutomixStateHolder.update { it.copy(quality = label) }
    }

    // ExoPlayer is single-threaded: analysis finishes on IO, the deck is read back on Main.
    private suspend fun applyAnalysisIfCurrent(mediaId: String, analysis: TrackAnalysisResult) =
        withContext(Dispatchers.Main) {
            if (mediaId == activeDeck.player.currentMediaItem?.mediaId) {
                updateAutomixStateForCurrentTrack(analysis)
            }
        }

    private fun updateAutomixStateForCurrentTrack(analysis: TrackAnalysisResult? = null) {
        val active = activeDeck.player
        val currentItem = active.currentMediaItem ?: return
        val currentTrackId = currentItem.mediaId
        val outgoingAnalysis = analysis ?: TrackAnalyzer.getCached(currentTrackId)
        val duration = active.duration.coerceAtLeast(0L)
        val crossfadeSec = AudioEffects.crossfadeSeconds.value
        val automixEnabled = AudioEffects.smartAutomix.value

        val defaultFadeMs = (crossfadeSec * 1000L).coerceAtLeast(3000L)
        val plan = if (automixEnabled && outgoingAnalysis != null) {
            TransitionPlanner.planSmartTransition(
                outgoingAnalysis = outgoingAnalysis,
                incomingAnalysis = null,
                currentDurationMs = duration,
                preferredDurationMs = defaultFadeMs,
                currentPositionMs = active.currentPosition,
            )
        } else null

        val triggerAtMs = plan?.triggerAtMs
            ?: outgoingAnalysis?.mixOutTime?.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() }
            ?: (if (duration > defaultFadeMs) duration - defaultFadeMs else null)

        AutomixStateHolder.update {
            it.copy(
                isEnabled = automixEnabled || crossfadeSec > 0,
                currentTrackAnalysis = outgoingAnalysis,
                triggerAtMs = triggerAtMs,
                durationMs = plan?.durationMs ?: defaultFadeMs,
                tier = plan?.tier ?: TransitionTier.PLAIN_CROSSFADE,
                isBassSwapped = plan?.enableBassSwap ?: AudioEffects.automixBassSwap.value,
            )
        }
    }

    private fun prepareStandby(targetIndex: Int, cueInMs: Long) {
        val active = activeDeck.player
        val count = active.mediaItemCount
        if (targetIndex !in 0 until count) return
        val targetItem = runCatching { active.getMediaItemAt(targetIndex) }.getOrNull() ?: return
        if (preparedStandbyIndex == targetIndex && preparedStandbyMediaId == targetItem.mediaId) {
            return
        }

        val queueItems = mutableListOf<MediaItem>()
        for (i in 0 until count) {
            runCatching { active.getMediaItemAt(i) }.getOrNull()?.let { queueItems.add(it) }
        }
        if (queueItems.isEmpty()) return

        val standby = standbyDeck.player
        standby.playWhenReady = false
        standby.volume = 0f
        // The standby becomes the active deck after handoff, so it must carry the user's modes.
        standby.repeatMode = active.repeatMode
        standby.shuffleModeEnabled = active.shuffleModeEnabled
        standby.setMediaItems(queueItems, targetIndex, cueInMs)
        // The same shuffled order, read off the active deck's timeline: a fresh one here skipped
        // and repeated songs after every handoff.
        standby.copyShuffleOrder(active)
        standby.pauseAtEndOfMediaItems = true
        standby.playbackParameters = userParams
        standby.prepare()

        preparedStandbyIndex = targetIndex
        preparedStandbyMediaId = targetItem.mediaId
        isStandbyPrimed = false
    }

    private fun primeStandby(cueInMs: Long) {
        isStandbyPrimed = true
    }

    private fun releaseStandby() {
        preparedStandbyIndex = C.INDEX_UNSET
        preparedStandbyMediaId = null
        isStandbyPrimed = false
        val standby = standbyDeck.player
        standby.playWhenReady = false
        standby.volume = 0f
        standby.stop()
        standby.clearMediaItems()
        standbyDeck.clearAutomation()
    }

    private fun startScheduler() {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (isActive) {
                delay(100)
                if (isCrossfading) continue
                val active = activeDeck.player
                if (!active.playWhenReady || !active.isPlaying) continue

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSeekTimestamp < 1500L) continue

                val currentPos = active.currentPosition
                val duration = active.duration
                if (duration <= 0L || currentPos < 0L) continue

                val currentIndex = active.currentMediaItemIndex
                val currentMediaItem: MediaItem = active.currentMediaItem ?: continue
                if (currentIndex == lastTriggeredIndex && currentMediaItem.mediaId == lastTriggeredMediaId) {
                    continue
                }

                val nextIndex = nextIndexOf(active)

                if (nextIndex == C.INDEX_UNSET) {
                    if (preparedStandbyIndex != C.INDEX_UNSET) releaseStandby()
                    continue
                }

                val nextMediaItem: MediaItem = runCatching { active.getMediaItemAt(nextIndex) }.getOrNull() ?: continue

                val crossfadeSec = AudioEffects.crossfadeSeconds.value
                val automixEnabled = AudioEffects.smartAutomix.value
                if (!automixEnabled && crossfadeSec <= 0) {
                    if (preparedStandbyIndex != C.INDEX_UNSET) releaseStandby()
                    continue
                }

                val currentTrackId = currentMediaItem.mediaId
                val nextTrackId = nextMediaItem.mediaId

                val outgoingAnalysis: TrackAnalysisResult? = TrackAnalyzer.getCached(currentTrackId)
                val incomingAnalysis: TrackAnalysisResult? = TrackAnalyzer.getCached(nextTrackId)

                if (automixEnabled) {
                    if (outgoingAnalysis == null) kickOffAnalysis(currentMediaItem)
                    if (incomingAnalysis == null) kickOffAnalysis(nextMediaItem)
                }

                val defaultFadeMs = (crossfadeSec * 1000L).coerceAtLeast(3000L)
                val pair = if (automixEnabled && outgoingAnalysis != null && incomingAnalysis != null) {
                    // Seeked past its exit: the one-sided planner re-anchors to where we are.
                    pairPlan(outgoingAnalysis, incomingAnalysis, duration, defaultFadeMs)
                        ?.takeIf { currentPos <= (it.triggerAtMs ?: 0L) + 1000L }
                } else null
                val automixPlan = if (pair != null) {
                    pair
                } else if (automixEnabled && outgoingAnalysis != null) {
                    TransitionPlanner.planSmartTransition(
                        outgoingAnalysis = outgoingAnalysis,
                        incomingAnalysis = incomingAnalysis,
                        currentDurationMs = duration,
                        preferredDurationMs = defaultFadeMs,
                        currentPositionMs = currentPos,
                    )
                } else if (automixEnabled) {
                    TransitionPlanner.planTransition(
                        currentDurationMs = duration,
                        preferredDurationMs = defaultFadeMs,
                        aggressiveness = "standard",
                    )
                } else {
                    AutomixPlan(
                        triggerOffsetMs = defaultFadeMs,
                        durationMs = defaultFadeMs,
                        incomingStartMs = 0L,
                        enableBassSwap = AudioEffects.automixBassSwap.value,
                        tier = TransitionTier.PLAIN_CROSSFADE,
                    )
                }

                val minRemainingForFade = (automixPlan.durationMs + 500L).coerceAtMost(duration)
                val planTriggerAt = automixPlan.triggerAtMs
                val triggerAtMs = if (planTriggerAt != null && planTriggerAt > 0L) {
                    planTriggerAt
                } else if (outgoingAnalysis != null && outgoingAnalysis.mixOutTime > 0.0) {
                    (outgoingAnalysis.mixOutTime * 1000.0).roundToLong()
                } else {
                    duration - defaultFadeMs
                }.coerceIn(1000L, (duration - minRemainingForFade).coerceAtLeast(1000L))

                val remainingToTrigger = triggerAtMs - currentPos
                val cueInMs = automixPlan.incomingStartMs.coerceAtLeast(0L)
                val prepareAheadMs = (automixPlan.prepareAheadMs ?: 6000L).coerceAtLeast(automixPlan.durationMs + 2000L)

                val nextTitle = nextMediaItem.mediaMetadata.title?.toString()
                val nextArtist = nextMediaItem.mediaMetadata.artist?.toString()
                val nextArt = nextMediaItem.mediaMetadata.artworkUri?.toString()

                AutomixStateHolder.update {
                    it.copy(
                        isEnabled = true,
                        isCrossfading = false,
                        currentTrackAnalysis = outgoingAnalysis,
                        nextTrackAnalysis = incomingAnalysis,
                        nextTrackTitle = nextTitle,
                        nextTrackArtist = nextArtist,
                        nextTrackArtworkUrl = nextArt,
                        triggerAtMs = triggerAtMs,
                        durationMs = automixPlan.durationMs,
                        tier = automixPlan.tier,
                        isBassSwapped = automixPlan.enableBassSwap,
                    )
                }

                // Stage 1: Prepare Standby Ahead of time
                if (remainingToTrigger <= prepareAheadMs && preparedStandbyIndex != nextIndex) {
                    prepareStandby(nextIndex, cueInMs)
                }

                // Stage 2: Prime standby close to trigger point
                if (remainingToTrigger <= 300L) {
                    primeStandby(cueInMs)
                }

                // Stage 3: Launch Crossfade Ramp. A beat-matched start lands on the downbeat, not
                // on the next 100 ms tick after it.
                if (remainingToTrigger <= 0L) {
                    startCrossfade(nextIndex, automixPlan)
                } else if (automixPlan.tier == TransitionTier.SMART_BEATMATCH && remainingToTrigger <= 120L &&
                    preparedStandbyIndex == nextIndex
                ) {
                    delay(remainingToTrigger)
                    val stillHere = active === activeDeck.player && !isCrossfading &&
                        active.currentMediaItem?.mediaId == currentTrackId &&
                        android.os.SystemClock.elapsedRealtime() - lastSeekTimestamp >= 1500L
                    if (stillHere) startCrossfade(nextIndex, automixPlan)
                }
            }
        }
    }

    private fun startCrossfade(nextIndex: Int, plan: AutomixPlan) {
        if (isCrossfading) return
        val outgoing = activeDeck
        val incoming = standbyDeck

        lastTriggeredIndex = outgoing.player.currentMediaItemIndex
        lastTriggeredMediaId = outgoing.player.currentMediaItem?.mediaId

        val cueInMs = plan.incomingStartMs.coerceAtLeast(0L)
        if (preparedStandbyIndex != nextIndex) {
            prepareStandby(nextIndex, cueInMs)
        }

        crossfadePlayRequested = outgoing.player.playWhenReady
        isCrossfading = true

        outgoing.partner = incoming
        incoming.partner = outgoing
        outgoing.isIncoming = false
        incoming.isIncoming = true
        // Each deck rides at its own loudness-matched level, so the overlap and the handoff
        // land at the same perceived volume instead of jumping by the tracks' LUFS gap.
        outgoing.baseVolume = outgoing.player.volume.takeIf { it > 0f } ?: restingVolumeFor(outgoing.player.currentMediaItem?.mediaId)
        incoming.baseVolume = restingVolumeFor(incoming.player.currentMediaItem?.mediaId)
        outgoing.maxGainFactor = 1f
        incoming.maxGainFactor = 1f

        tempoEaseJob?.cancel()
        outgoing.player.playbackParameters = userParams
        val matchedParams = if (plan.tier == TransitionTier.SMART_BEATMATCH && plan.incomingTempoRatio != 1f) {
            PlaybackParameters(userParams.speed * plan.incomingTempoRatio, userParams.pitch)
        } else {
            userParams
        }
        incoming.player.playbackParameters = matchedParams

        outgoing.applyAutomation(0f, plan)
        incoming.applyAutomation(0f, plan)

        // Hold at the end instead of auto-advancing; the incoming deck owns the next song.
        outgoing.player.pauseAtEndOfMediaItems = true

        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            // Mirrors YumaPlayer's CrossfadeRampEngine: the incoming deck must be READY with real
            // audio buffered before anything fades, or the outgoing dives while nothing replaces it.
            val requiredBufferMs = requiredStartBufferMs(plan.durationMs)
            val readyDeadline = android.os.SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
            while (true) {
                val p = incoming.player
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (p.playbackState == Player.STATE_READY &&
                    (p.bufferedPosition - p.currentPosition >= requiredBufferMs || p.isLoading.not())
                ) break
                if (android.os.SystemClock.elapsedRealtime() > readyDeadline) {
                    // Not ready: skip the fade, outgoing plays out and Media3 advances gaplessly.
                    cancelCrossfade()
                    return@launch
                }
                delay(25)
            }

            AutomixStateHolder.update {
                it.copy(
                    isCrossfading = true,
                    crossfadeProgress = 0f,
                    tier = plan.tier,
                    isBassSwapped = plan.enableBassSwap,
                )
            }

            val outgoingMediaId = outgoing.player.currentMediaItem?.mediaId
            val exitMs = plan.triggerAtMs
            var lastNudge = 0L
            var elapsed = 0L
            var lastTick = android.os.SystemClock.elapsedRealtime()
            var stallStart: Long? = null
            while (isActive && elapsed < plan.durationMs) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (outgoing.player.currentMediaItem?.mediaId != outgoingMediaId) {
                    cancelCrossfade()
                    return@launch
                }
                if (crossfadePlayRequested) {
                    incoming.player.playWhenReady = true
                    if (incoming.player.playerError != null || incoming.player.playbackState == Player.STATE_ENDED) {
                        cancelCrossfade()
                        return@launch
                    }
                    // Progress advances only while the incoming deck is actually producing audio:
                    // a stall holds the mix where it is rather than fading into silence.
                    if (incoming.player.playbackState == Player.STATE_READY && incoming.player.isPlaying) {
                        stallStart = null
                        elapsed = (elapsed + (now - lastTick)).coerceAtMost(plan.durationMs)
                        val progress = elapsed.toFloat() / plan.durationMs.toFloat()
                        outgoing.applyAutomation(progress, plan)
                        incoming.applyAutomation(progress, plan)
                        AutomixStateHolder.update { it.copy(crossfadeProgress = progress) }
                        if (plan.tier == TransitionTier.SMART_BEATMATCH && exitMs != null && now - lastNudge >= NUDGE_EVERY_MS) {
                            lastNudge = now
                            holdBeatPhase(outgoing, incoming, plan, exitMs, matchedParams)
                        }
                    } else if (now - (stallStart ?: now.also { stallStart = it }) >= READY_TIMEOUT_MS) {
                        cancelCrossfade()
                        return@launch
                    }
                } else {
                    // User paused: both decks hold, the ramp does not move.
                    incoming.player.playWhenReady = false
                    stallStart = null
                }
                lastTick = now
                delay(FRAME_MS)
            }
            if (!isActive) return@launch

            completeHandoff(incoming, outgoing, plan)
        }
    }

    private fun holdBeatPhase(
        outgoing: ExoDeck, incoming: ExoDeck, plan: AutomixPlan, exitMs: Long, matched: PlaybackParameters,
    ) {
        val out = outgoing.player
        val inc = incoming.player
        if (!out.isPlaying || !inc.isPlaying) return
        val expected = plan.incomingStartMs + (out.currentPosition - exitMs) * plan.incomingTempoRatio + STRETCH_LATENCY_MS
        val errorMs = inc.currentPosition - expected
        // Ahead slows it a touch, behind speeds it up.
        val trim = if (abs(errorMs) < PHASE_TOLERANCE_MS || abs(errorMs) > PHASE_GIVE_UP_MS) {
            1f
        } else {
            (1.0 - errorMs / NUDGE_HORIZON_MS).toFloat().coerceIn(1f - MAX_NUDGE, 1f + MAX_NUDGE)
        }
        val wanted = PlaybackParameters(matched.speed * trim, matched.pitch)
        if (inc.playbackParameters != wanted) inc.playbackParameters = wanted
    }

    /** From the matched tempo back to the listener's own, smoothly, as automix-engine does. */
    private fun easeTempoBack(player: ExoPlayer) {
        tempoEaseJob?.cancel()
        val from = player.playbackParameters.speed
        tempoEaseJob = scope.launch {
            val steps = (TEMPO_EASE_MS / EASE_STEP_MS).toInt()
            for (i in 1..steps) {
                delay(EASE_STEP_MS)
                if (player !== activeDeck.player) return@launch
                val f = i.toFloat() / steps
                val eased = f * f * (3f - 2f * f)
                player.playbackParameters = PlaybackParameters(from + (userParams.speed - from) * eased, userParams.pitch)
            }
            player.playbackParameters = userParams
        }
    }

    private fun stopTempoEase() {
        if (tempoEaseJob?.isActive != true) return
        tempoEaseJob?.cancel()
        activeDeck.player.playbackParameters = userParams
    }

    private fun completeHandoff(incoming: ExoDeck, outgoing: ExoDeck, plan: AutomixPlan) {
        outgoing.player.pauseAtEndOfMediaItems = false
        incoming.player.pauseAtEndOfMediaItems = false

        if (incoming.player.playbackParameters != userParams) easeTempoBack(incoming.player)

        // Hand audio focus over: release on the outgoing first, then claim on the incoming.
        val attrs = outgoing.player.audioAttributes
        outgoing.player.setAudioAttributes(attrs, false)
        incoming.player.setAudioAttributes(attrs, focusHandled)

        forwardingPlayer.attachPlayer(incoming.player)
        hardwareEffectsManager.attachSession(incoming.player.audioSessionId)

        activeDeck = incoming
        standbyDeck = outgoing

        outgoing.player.playWhenReady = false
        outgoing.player.volume = 0f
        outgoing.player.stop()
        outgoing.player.clearMediaItems()
        outgoing.clearAutomation()
        incoming.clearAutomation()

        preparedStandbyIndex = C.INDEX_UNSET
        preparedStandbyMediaId = null
        isStandbyPrimed = false
        isCrossfading = false

        lastTriggeredIndex = C.INDEX_UNSET
        lastTriggeredMediaId = null

        AutomixStateHolder.update {
            it.copy(
                isCrossfading = false,
                crossfadeProgress = 0f,
                nextTrackTitle = null,
                nextTrackArtist = null,
                nextTrackArtworkUrl = null,
            )
        }

        host.onActivePlayerChanged(this)
        publishQuality()
        triggerAnalysisForCurrentAndNext()
    }

    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfading = false

        activeDeck.player.pauseAtEndOfMediaItems = false
        applyRestingVolume()
        activeDeck.clearAutomation()

        standbyDeck.player.pauseAtEndOfMediaItems = false
        standbyDeck.player.playWhenReady = false
        standbyDeck.player.volume = 0f
        standbyDeck.player.stop()
        standbyDeck.player.clearMediaItems()
        standbyDeck.clearAutomation()

        preparedStandbyIndex = C.INDEX_UNSET
        preparedStandbyMediaId = null
        isStandbyPrimed = false

        forwardingPlayer.attachPlayer(activeDeck.player)

        AutomixStateHolder.update {
            it.copy(
                isCrossfading = false,
                crossfadeProgress = 0f,
                nextTrackTitle = null,
                nextTrackArtist = null,
                nextTrackArtworkUrl = null,
            )
        }
    }

    override fun play() = setPlayWhenReady(true)

    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!isCrossfading) {
            activeDeck.player.playWhenReady = playWhenReady
            return
        }
        // Mid-fade the ramp loop owns the incoming deck; it follows this flag.
        crossfadePlayRequested = playWhenReady
        val outgoing = activeDeck.player
        // An outgoing deck held at its end by pauseAtEndOfMediaItems would jump to the next
        // queue item if played, which cancels the fade. Leave it parked there.
        val outgoingAtEnd = outgoing.duration > 0L && outgoing.currentPosition >= outgoing.duration - 300L
        if (!playWhenReady || !outgoingAtEnd) outgoing.playWhenReady = playWhenReady
        standbyDeck.player.playWhenReady = playWhenReady
    }

    // Every position jump goes through here so the scheduler never acts on a stale position.
    private fun beforeSeek() {
        stopTempoEase()
        lastSeekTimestamp = android.os.SystemClock.elapsedRealtime()
        lastTriggeredIndex = C.INDEX_UNSET
        lastTriggeredMediaId = null
        if (isCrossfading) cancelCrossfade()
        activeDeck.player.pauseAtEndOfMediaItems = false
    }

    override fun seekBack() {
        beforeSeek()
        forwardingPlayer.seekBack()
    }

    override fun seekForward() {
        beforeSeek()
        forwardingPlayer.seekForward()
    }

    override fun seekTo(positionMs: Long) {
        beforeSeek()
        forwardingPlayer.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        beforeSeek()
        forwardingPlayer.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToDefaultPosition() {
        beforeSeek()
        forwardingPlayer.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        beforeSeek()
        forwardingPlayer.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekToNext() {
        beforeSeek()
        forwardingPlayer.seekToNext()
    }

    override fun seekToNextMediaItem() {
        beforeSeek()
        forwardingPlayer.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        beforeSeek()
        forwardingPlayer.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        beforeSeek()
        forwardingPlayer.seekToPreviousMediaItem()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        beforeSeek()
        forwardingPlayer.setMediaItems(mediaItems)
        activeDeck.player.shuffleFromCurrent()
        triggerAnalysisForCurrentAndNext()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        beforeSeek()
        forwardingPlayer.setMediaItems(mediaItems, resetPosition)
        activeDeck.player.shuffleFromCurrent()
        triggerAnalysisForCurrentAndNext()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        beforeSeek()
        forwardingPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        activeDeck.player.shuffleFromCurrent()
        triggerAnalysisForCurrentAndNext()
    }

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(mediaItemCount, listOf(mediaItem))

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, listOf(mediaItem))

    override fun addMediaItems(mediaItems: List<MediaItem>) = addMediaItems(mediaItemCount, mediaItems)

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val at = index.coerceIn(0, mediaItemCount)
        forwardingPlayer.addMediaItems(at, mediaItems)
        activeDeck.player.shuffleInAhead(at, mediaItems.size)
        // The standby holds a copy of the old queue; let the scheduler cue it again from this one.
        if (!isCrossfading) releaseStandby()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        userParams = playbackParameters
        tempoEaseJob?.cancel()
        playerA.playbackParameters = playbackParameters
        playerB.playbackParameters = playbackParameters
    }

    override fun setRepeatMode(repeatMode: Int) {
        standbyDeck.player.repeatMode = repeatMode
        forwardingPlayer.repeatMode = repeatMode
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        standbyDeck.player.shuffleModeEnabled = shuffleModeEnabled
        // From the song playing: everything else, before it in the list or after, is still to come.
        if (shuffleModeEnabled) activeDeck.player.shuffleFromCurrent()
        forwardingPlayer.shuffleModeEnabled = shuffleModeEnabled
        // The prepared standby was cued for the old next track; let the scheduler pick again.
        if (!isCrossfading) releaseStandby()
    }

    override fun stop() {
        cancelCrossfade()
        playerA.stop()
        playerB.stop()
    }

    override fun release() {
        AutomixStateHolder.update { it.copy(quality = null) }
        schedulerJob?.cancel()
        tempoEaseJob?.cancel()
        cancelCrossfade()
        hardwareEffectsManager.detachSession()
        playerA.release()
        playerB.release()
    }

    override fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        applyRestingVolume()
    }

    override fun getVolume(): Float = userVolume

    // Loudness matching. No ReplayGain tags on the server, so the analyzer's LUFS (head + tail
    // windows) drives it. Attenuate-only: ExoPlayer volume cannot exceed 1 and boosting a quiet
    // master would need a limiter, so a quiet track stays as loud as it is.
    // ponytail: applied at track boundaries only (start, handoff, cancel), never mid-song, so the
    // first play of an unanalysed track runs at full level. Add a short ramp if that matters.
    private fun restingVolumeFor(mediaId: String?): Float {
        val lufs = mediaId?.let { TrackAnalyzer.getCached(it)?.loudnessLufs }?.takeIf { it < 0.0 && it.isFinite() }
            ?: return userVolume
        val gain = Math.pow(10.0, (TARGET_LUFS - lufs) / 20.0).toFloat().coerceIn(MIN_NORM_GAIN, 1f)
        return userVolume * gain
    }

    private fun applyRestingVolume() {
        if (isCrossfading) return
        activeDeck.player.volume = restingVolumeFor(activeDeck.player.currentMediaItem?.mediaId)
    }

    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes, handleAudioFocus: Boolean) {
        focusHandled = handleAudioFocus
        standbyDeck.player.setAudioAttributes(audioAttributes, false)
        activeDeck.player.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        playerA.trackSelectionParameters = parameters
        playerB.trackSelectionParameters = parameters
    }
}
