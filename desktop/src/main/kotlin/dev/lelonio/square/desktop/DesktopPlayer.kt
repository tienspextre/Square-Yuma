package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.audiodsp.AutomixPlan
import moe.rukamori.archivetune.audiodsp.TransitionTier
import moe.rukamori.archivetune.audiodsp.pulse.VocalDuckMode
import kotlin.math.abs

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Plays a queue of Subsonic/local songs through the native C++20 dual-deck audio engine
 * (`square_win_native`: miniaudio WASAPI/ALSA/Pulse output, Bungee stretch, DJ filter,
 * MixStyle curves, EQ, reverb, karaoke, EBU R128 loudness, sleep fade, limiter).
 */
class DesktopPlayer(
    private val streamUrl: (SubsonicChildSong) -> String,
    private val coverBytes: (suspend (SubsonicChildSong) -> ByteArray?)? = null,
    private val durationMsOf: (SubsonicChildSong) -> Long = { it.duration * 1000L },
    initEngine: Boolean = true,
) {
    val queue = MutableStateFlow<List<SubsonicChildSong>>(emptyList())
    val index = MutableStateFlow(-1)
    val current = MutableStateFlow<SubsonicChildSong?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val volume = MutableStateFlow(0.8f)
    val shuffle = MutableStateFlow(false)
    val smartShuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(RepeatMode.OFF)
    val error = MutableStateFlow<String?>(null)
    val stopAfterCurrent = MutableStateFlow(false)
    val signalChain = MutableStateFlow(AudioSignalChain())

    @Volatile var crossfadeSeconds = 6
    @Volatile var speed = 1f
    @Volatile var trimSilence = true
    @Volatile var skipFade = true
    @Volatile var autoplayAtEnd = false
    @Volatile var planner: ((SubsonicChildSong, SubsonicChildSong) -> AutomixPlan?)? = null
    @Volatile var outputMode: String = "shared"
    @Volatile var outputDeviceId: String? = null
    @Volatile var loudnessResolver: ((SubsonicChildSong, (String, Float) -> Unit) -> Float?)? = null
    @Volatile var onQueueStateChanged: (() -> Unit)? = null
    @Volatile var onPositionChanged: ((Long) -> Unit)? = null
    @Volatile var onAutoplayNeeded: (suspend (SubsonicChildSong) -> List<SubsonicChildSong>)? = null

    private var unshuffled: List<SubsonicChildSong> = emptyList()
    private val lock = Object()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeDeck = 0
    private var preparedStandbyIndex = -1
    private var preparedStandbySong: SubsonicChildSong? = null
    private var armedPlan: AutomixPlan? = null
    private var mixScheduled = false
    private var planTriedAtMs = Long.MIN_VALUE / 2
    private var planForSong: SubsonicChildSong? = null
    private var cachedPlan: AutomixPlan? = null

    private var currentFx = Fx()
    private var currentEq = FloatArray(Eq.BANDS.size)
    private var currentLoudnessDb = 0f
    private var smtcJob: Job? = null
    private var engineReady = false
    @Volatile var smtcUpdateCount: Int = 0
        private set

    fun shutdown() {
        smtcJob?.cancel()
        scope.cancel()
        if (engineReady) {
            engineReady = false
            NativeEngine.setCallback(null)
            NativeEngine.shutdown()
        }
    }

    init {
        if (initEngine) {
            engineReady = runCatching {
                val ok = NativeEngine.init()
                if (ok) {
                    NativeEngine.setCallback(object : NativeEngine.Callback {
                        override fun onEnded(deck: Int) {
                            handleNativeEnded(deck)
                        }

                        override fun onDeviceLost(reason: String) {
                            synchronized(lock) { isPlaying.value = false }
                        }

                        override fun onSmtcButton(button: String) {
                            when (button) {
                                "play" -> if (!isPlaying.value) toggle()
                                "pause", "stop" -> pause()
                                "play_pause" -> toggle()
                                "next" -> next()
                                "previous" -> previous()
                            }
                        }

                        override fun onError(deck: Int, message: String) {
                            synchronized(lock) {
                                if (deck == activeDeck) error.value = message
                            }
                        }
                    })
                    pushDsp()
                }
                ok
            }.onFailure {
                error.value = "Native audio engine unavailable: ${it.message}"
            }.getOrDefault(false)

            scope.launch { pollLoop() }
        }
    }

    fun snapshotQueueState(): PersistedQueueState? = synchronized(lock) {
        val q = queue.value
        if (q.isEmpty()) return null
        PersistedQueueState(
            queue = q.map { PersistedQueueItem.fromDesktopSong(it, durationMsOf(it)) },
            unshuffled = if (shuffle.value) unshuffled.map { PersistedQueueItem.fromDesktopSong(it, durationMsOf(it)) } else null,
            index = index.value.coerceIn(0, q.lastIndex),
            positionMs = positionMs.value.coerceAtLeast(0L),
            shuffle = shuffle.value,
            repeat = repeat.value,
        )
    }

    fun restoreQueueState(
        saved: PersistedQueueState,
        resolveSong: (PersistedQueueItem) -> SubsonicChildSong = { it.toDesktopSong() },
    ) {
        val restoredQueue = saved.queue.map(resolveSong)
        if (restoredQueue.isEmpty()) return
        val restoredUnshuffled = saved.unshuffled?.map(resolveSong) ?: restoredQueue
        val idx = saved.index.coerceIn(0, restoredQueue.lastIndex)
        val song = restoredQueue[idx]
        val pos = saved.positionMs.coerceAtLeast(0L)

        synchronized(lock) {
            dropStandby()
            unshuffled = restoredUnshuffled
            queue.value = restoredQueue
            shuffle.value = saved.shuffle
            repeat.value = saved.repeat
            index.value = idx
            current.value = song
            positionMs.value = pos
            isPlaying.value = false
            error.value = null
        }
        refreshTrackLoudness(song)
        if (engineReady) {
            val url = streamUrl(song)
            if (url.isNotBlank()) {
                NativeEngine.deckLoad(activeDeck, url, null, pos)
                NativeEngine.deckPause(activeDeck)
            }
            pushDsp()
        }
        publishSmtc(song, playing = false, posMs = pos)
    }

    fun playQueue(songs: List<SubsonicChildSong>, start: Int = 0) {
        unshuffled = songs
        shuffle.value = false
        queue.value = songs
        play(start)
        onQueueStateChanged?.invoke()
    }

    fun playNext(song: SubsonicChildSong) {
        synchronized(lock) {
            val at = (index.value + 1).coerceIn(0, queue.value.size)
            queue.value = queue.value.toMutableList().apply { add(at, song) }
            unshuffled = unshuffled + song
        }
        onQueueStateChanged?.invoke()
    }

    fun addToQueue(song: SubsonicChildSong) {
        synchronized(lock) {
            queue.value = queue.value + song
            unshuffled = unshuffled + song
        }
        onQueueStateChanged?.invoke()
    }

    fun removeAt(i: Int) {
        synchronized(lock) {
            if (i == index.value || i !in queue.value.indices) return
            val gone = queue.value[i]
            queue.value = queue.value.toMutableList().apply { removeAt(i) }
            unshuffled = unshuffled.toMutableList().apply { remove(gone) }
            if (i < index.value) index.value -= 1
            dropStandby()
        }
        onQueueStateChanged?.invoke()
    }

    /** Everything but the song playing. */
    fun clearQueue() {
        synchronized(lock) {
            val now = current.value ?: return
            queue.value = listOf(now)
            unshuffled = listOf(now)
            index.value = 0
            dropStandby()
        }
        onQueueStateChanged?.invoke()
    }

    fun move(from: Int, to: Int) {
        synchronized(lock) {
            val q = queue.value
            val (reordered, newIndex) = moveQueueItem(q, index.value, from, to)
            if (reordered === q) return
            queue.value = reordered
            index.value = newIndex
            dropStandby()
        }
        onQueueStateChanged?.invoke()
    }

    /** Updates DSP parameters in real time without restarting playback. */
    fun applyDsp(fx: Fx, eq: FloatArray, normalizeGainDb: Float = currentLoudnessDb) {
        currentFx = fx
        currentEq = eq.copyOf()
        currentLoudnessDb = normalizeGainDb
        speed = fx.speed
        if (fx.loudness) {
            current.value?.let { refreshTrackLoudness(it) }
        }
        pushDsp()
    }

    fun setVolumeLevel(v: Float) {
        volume.value = v.coerceIn(0f, 1f)
        pushDsp()
    }

    fun setSleepTimer(ms: Long, fadeMs: Long, endOfTrack: Boolean) {
        if (!engineReady) return
        NativeEngine.setSleepTimer(ms, fadeMs, endOfTrack)
    }

    fun setOutput(mode: String, deviceId: String? = null): Boolean {
        outputMode = mode
        outputDeviceId = deviceId
        if (!engineReady) return false
        return NativeEngine.setOutput(mode, deviceId)
    }

    fun statusJson(): String = if (engineReady) NativeEngine.status() else "{}"

    fun reapply() {
        pushDsp()
    }

    fun play(i: Int, atMs: Long = 0L, fade: Boolean = true) {
        val song: SubsonicChildSong
        val deckToUse: Int
        val wasCrossfade: Boolean
        synchronized(lock) {
            song = queue.value.getOrNull(i) ?: return
            dropStandby()
            val oldDeck = activeDeck
            wasCrossfade = fade && skipFade && isPlaying.value && current.value != null && engineReady
            deckToUse = if (wasCrossfade) 1 - oldDeck else oldDeck
            activeDeck = deckToUse
            index.value = i
            current.value = song
            positionMs.value = atMs
            error.value = null
            planForSong = null
            cachedPlan = null
            planTriedAtMs = Long.MIN_VALUE / 2
            isPlaying.value = true
        }

        if (!engineReady) {
            error.value = "Native audio engine not initialized"
            isPlaying.value = false
            return
        }

        val url = streamUrl(song)
        if (!NativeEngine.deckLoad(deckToUse, url, null, atMs)) {
            error.value = "Couldn't play “${song.title}”"
            isPlaying.value = false
            return
        }

        if (wasCrossfade) {
            val skipPlan = AutomixPlan(
                triggerOffsetMs = SKIP_FADE_MS,
                durationMs = SKIP_FADE_MS,
                incomingStartMs = atMs,
                enableBassSwap = false,
                triggerAtMs = 0L,
                tier = TransitionTier.PLAIN_CROSSFADE,
            )
            NativeEngine.scheduleMix(skipPlan, incomingDeck = deckToUse)
        }
        NativeEngine.deckPlay(deckToUse)
        refreshTrackLoudness(song)
        pushDsp()
        publishSmtc(song, playing = true, posMs = atMs)
        onQueueStateChanged?.invoke()
    }

    private fun refreshTrackLoudness(song: SubsonicChildSong) {
        val resolver = loudnessResolver ?: return
        val cached = resolver(song) { trackId, gainDb ->
            if (current.value?.id == trackId) {
                currentLoudnessDb = gainDb
                if (currentFx.loudness) pushDsp()
            }
        }
        currentLoudnessDb = cached ?: 0f
    }

    fun toggle() = synchronized(lock) {
        when {
            current.value != null -> setPlaying(!isPlaying.value)
            queue.value.isNotEmpty() -> play(index.value.coerceAtLeast(0), positionMs.value)
        }
    }

    fun pause() = synchronized(lock) {
        if (isPlaying.value) setPlaying(false)
    }

    fun seek(ms: Long) {
        val target = ms.coerceIn(0L, durationMs())
        synchronized(lock) {
            if (current.value == null) return
            dropStandby()
            positionMs.value = target
            if (engineReady) {
                NativeEngine.deckSeek(activeDeck, target)
                if (!isPlaying.value) NativeEngine.deckPause(activeDeck)
            }
        }
        current.value?.let { publishSmtc(it, isPlaying.value, target) }
        onPositionChanged?.invoke(target)
    }

    fun next() = nextIndex(auto = false)?.let { play(it) } ?: Unit

    fun previous() {
        if (positionMs.value > 3_000 || index.value <= 0) seek(0) else play(index.value - 1)
    }

    fun toggleShuffle() {
        synchronized(lock) {
            val now = current.value
            val on = !shuffle.value
            val list = if (on) {
                unshuffled = queue.value
                listOfNotNull(now) + queue.value.filter { it !== now }.shuffled()
            } else {
                smartShuffle.value = false
                unshuffled
            }
            queue.value = list
            shuffle.value = on
            index.value = list.indexOfFirst { it === now }
            dropStandby()
        }
        onQueueStateChanged?.invoke()
    }

    fun toggleSmartShuffle() {
        synchronized(lock) {
            val now = current.value
            val nextSmart = !smartShuffle.value
            if (nextSmart) {
                if (!shuffle.value) unshuffled = queue.value
                val list = smartShuffleOrder(queue.value, index.value, { it.artist })
                queue.value = list
                shuffle.value = true
                smartShuffle.value = true
                index.value = list.indexOfFirst { it === now }
            } else {
                smartShuffle.value = false
                shuffle.value = false
                queue.value = unshuffled
                index.value = unshuffled.indexOfFirst { it === now }
            }
            dropStandby()
        }
        onQueueStateChanged?.invoke()
    }

    fun cycleRepeat() {
        repeat.value = RepeatMode.entries[(repeat.value.ordinal + 1) % RepeatMode.entries.size]
        onQueueStateChanged?.invoke()
    }

    fun durationMs(): Long = current.value?.let(durationMsOf) ?: 0L

    private fun setPlaying(on: Boolean) {
        isPlaying.value = on
        if (engineReady) {
            if (on) {
                if (NativeEngine.position(activeDeck) == 0L && positionMs.value > 0L) {
                    current.value?.let { NativeEngine.deckLoad(activeDeck, streamUrl(it), null, positionMs.value) }
                }
                NativeEngine.deckPlay(activeDeck)
            } else {
                NativeEngine.deckPause(activeDeck)
            }
        }
        current.value?.let { publishSmtc(it, on, positionMs.value) }
    }

    private fun pushDsp() {
        if (!engineReady) return
        val v = volume.value.coerceIn(0f, 1f)
        val linearGain = v * v
        NativeEngine.setDsp(
            NativeEngine.DspPayload(
                speed = currentFx.speed,
                pitch = currentFx.pitch,
                eq = currentEq.toList(),
                preampDb = 0f,
                reverb = currentFx.reverb,
                karaoke = currentFx.karaoke,
                loudness = currentFx.loudness,
                normalizeGainDb = if (currentFx.loudness) currentLoudnessDb else 0f,
                volume = linearGain,
                bitPerfect = (outputMode == "exclusive"),
            ),
        )
    }

    private fun dropStandby() {
        preparedStandbyIndex = -1
        preparedStandbySong = null
        armedPlan = null
        mixScheduled = false
    }

    private fun nextIndex(auto: Boolean): Int? {
        val i = index.value
        return when {
            auto && repeat.value == RepeatMode.ONE -> i
            i + 1 < queue.value.size -> i + 1
            repeat.value != RepeatMode.OFF && queue.value.isNotEmpty() -> 0
            else -> null
        }
    }

    private fun handleNativeEnded(deck: Int) {
        synchronized(lock) {
            if (mixScheduled && deck != activeDeck) {
                // Outgoing deck finished crossfade; incoming is already active.
                activeDeck = 1 - deck
                mixScheduled = false
                armedPlan = null
                preparedStandbyIndex = -1
                preparedStandbySong = null
                return
            }
            if (mixScheduled && deck == activeDeck) {
                val nextIdx = preparedStandbyIndex
                val nextSong = preparedStandbySong
                activeDeck = 1 - deck
                mixScheduled = false
                armedPlan = null
                preparedStandbyIndex = -1
                preparedStandbySong = null
                if (nextIdx >= 0 && nextSong != null) {
                    index.value = nextIdx
                    current.value = nextSong
                    planForSong = null
                    cachedPlan = null
                    refreshTrackLoudness(nextSong)
                    pushDsp()
                    publishSmtc(nextSong, true, 0L)
                    onQueueStateChanged?.invoke()
                    return
                }
            }
        }

        val n = if (error.value == null && !stopAfterCurrent.value) nextIndex(auto = true) else null
        val endedSong = current.value
        val wasStoppedAfterCurrent = stopAfterCurrent.value
        if (stopAfterCurrent.value) stopAfterCurrent.value = false
        if (n == null) {
            if (!wasStoppedAfterCurrent && error.value == null && autoplayAtEnd && endedSong != null) {
                val fetcher = onAutoplayNeeded
                if (fetcher != null) {
                    scope.launch {
                        val more = runCatching { fetcher(endedSong) }.getOrDefault(emptyList())
                        if (more.isNotEmpty()) {
                            val startAt: Int
                            synchronized(lock) {
                                startAt = queue.value.size
                                queue.value = queue.value + more
                                unshuffled = unshuffled + more
                            }
                            play(startAt, atMs = 0L, fade = false)
                            onQueueStateChanged?.invoke()
                            return@launch
                        }
                        finishStoppedPlayback(endedSong)
                    }
                    return
                }
            }
            finishStoppedPlayback(endedSong)
        } else {
            play(n, atMs = 0L, fade = false)
        }
    }

    private fun finishStoppedPlayback(endedSong: SubsonicChildSong?) {
        synchronized(lock) {
            isPlaying.value = false
            positionMs.value = 0L
        }
        endedSong?.let { publishSmtc(it, false, 0L) }
        onQueueStateChanged?.invoke()
    }

    private suspend fun pollLoop() {
        var statusTicks = 0
        while (true) {
            delay(100)
            if (!engineReady) continue
            if (++statusTicks % 4 == 0 || isPlaying.value) {
                signalChain.value = AudioSignalChain.fromJson(NativeEngine.status())
            }
            if (!isPlaying.value) continue
            val song = current.value ?: continue
            val posMs = NativeEngine.position(activeDeck).coerceAtLeast(0L)
            if (abs(posMs - positionMs.value) >= 40L) {
                positionMs.value = posMs
                onPositionChanged?.invoke(posMs)
            }
            maybePrepareAndScheduleMix(song, posMs)
        }
    }

    private fun maybePrepareAndScheduleMix(song: SubsonicChildSong, posMs: Long) {
        if (stopAfterCurrent.value || repeat.value == RepeatMode.ONE || mixScheduled) return
        val nextIdx = nextIndex(auto = true) ?: return
        val nextSong = queue.value.getOrNull(nextIdx) ?: return
        val durMs = durationMsOf(song)
        if (durMs <= 0L || durMs - posMs > 45_000L) return

        val plan0 = planner
        var plan = cachedPlan
        if (plan0 != null && planForSong !== nextSong && posMs >= planTriedAtMs + 2_000L) {
            planTriedAtMs = posMs
            plan = runCatching { plan0(song, nextSong) }.getOrNull()
            planForSong = nextSong
            cachedPlan = plan
        }

        val effectivePlan = plan ?: run {
            val fadeMs = crossfadeSeconds * 1000L
            if (fadeMs <= 0L || durMs < fadeMs * 3L) return
            AutomixPlan(
                triggerOffsetMs = fadeMs,
                durationMs = fadeMs,
                incomingStartMs = 0L,
                enableBassSwap = false,
                triggerAtMs = durMs - fadeMs,
                prepareAheadMs = maxOf(fadeMs + 2_000L, 8_000L),
                tier = TransitionTier.PLAIN_CROSSFADE,
            )
        }

        val triggerMs = effectivePlan.triggerAtMs ?: (durMs - effectivePlan.durationMs)
        val prepareAheadMs = effectivePlan.prepareAheadMs ?: 8_000L
        val standbyDeck = 1 - activeDeck

        if (preparedStandbyIndex != nextIdx && posMs >= triggerMs - prepareAheadMs) {
            val uri = streamUrl(nextSong)
            if (NativeEngine.deckLoad(standbyDeck, uri, null, effectivePlan.incomingStartMs)) {
                preparedStandbyIndex = nextIdx
                preparedStandbySong = nextSong
                armedPlan = effectivePlan
            }
        }

        if (preparedStandbyIndex == nextIdx && !mixScheduled && posMs >= triggerMs - 1_000L) {
            val duckMode = if (effectivePlan.vocalClash > 0.25) VocalDuckMode.MID_SIDE else VocalDuckMode.NONE
            if (NativeEngine.scheduleMix(
                    plan = effectivePlan,
                    incomingDeck = standbyDeck,
                    vocalDuckMode = duckMode,
                    outgoingEnergyFill = (duckMode == VocalDuckMode.MID_SIDE),
                )
            ) {
                mixScheduled = true
            }
        }
    }

    private fun publishSmtc(song: SubsonicChildSong, playing: Boolean, posMs: Long) {
        if (!engineReady) return
        smtcJob?.cancel()
        val payload = NativeEngine.SmtcPayload(
            title = song.title,
            artist = song.artist.orEmpty(),
            album = song.album.orEmpty(),
            status = if (playing) "playing" else "paused",
            positionMs = posMs,
            durationMs = song.duration * 1000L,
        )
        smtcUpdateCount++
        smtcJob = scope.launch {
            val art = runCatching { coverBytes?.invoke(song) }.getOrNull()
            NativeEngine.smtcUpdate(payload, art)
        }
    }

    companion object {
        private const val SKIP_FADE_MS = 600L
    }
}
