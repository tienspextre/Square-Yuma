package dev.lelonio.square.playback

import dev.lelonio.square.data.CatalogTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.audiodsp.FILTER_OPEN_HZ
import moe.rukamori.archivetune.audiodsp.incomingBassGainDb
import moe.rukamori.archivetune.audiodsp.incomingStageGain
import moe.rukamori.archivetune.audiodsp.outgoingBassGainDb
import moe.rukamori.archivetune.audiodsp.outgoingLowPassHz
import moe.rukamori.archivetune.audiodsp.outgoingMidDuckGain
import moe.rukamori.archivetune.audiodsp.outgoingStageGain
import org.kc7bfi.jflac.FLACDecoder
import org.kc7bfi.jflac.PCMProcessor
import org.kc7bfi.jflac.metadata.StreamInfo
import org.kc7bfi.jflac.util.ByteData
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AutomixUiState(
    val enabled: Boolean = true,
    val isMixing: Boolean = false,
    val progress: Float = 0f,
    val outgoingTrack: CatalogTrack? = null,
    val incomingTrack: CatalogTrack? = null,
    val outgoingGain: Float = 1f,
    val incomingGain: Float = 0f,
    val filterCutoffHz: Float = 20000f,
    val crossfadeDurationSec: Float = 8f,
    val djBassSwapEnabled: Boolean = true,
)

enum class DesktopDspPreset(
    val label: String,
    val subtitle: String,
    val speed: Float,
    val pitch: Float,
    val reverb: Float,
    val bassDb: Float,
    val vocalRemove: Float,
) {
    OFF("Bit-Perfect Direct", "Untouched lossless signal path", 1.0f, 1.0f, 0.0f, 0.0f, 0.0f),
    SLOWED_REVERB("Slowed + Reverb", "Dreamy 0.86x tape speed with lush hall decay", 0.86f, 0.88f, 0.48f, 2.0f, 0.0f),
    NIGHTCORE("Nightcore", "Energetic 1.22x tempo & pitch lift", 1.22f, 1.22f, 0.10f, 1.5f, 0.0f),
    VAPORWAVE("Late Night Vinyl", "Warm analog low-end & 0.80x drift", 0.80f, 0.82f, 0.35f, 4.0f, 0.0f),
    AUDIOPHILE_WARM("Audiophile Tube", "Subtle harmonic warmth & +3.5 dB sub-bass", 1.0f, 1.0f, 0.12f, 3.5f, 0.0f),
    KARAOKE("Karaoke Stage", "Real-time center vocal cancellation", 1.0f, 1.0f, 0.20f, 1.0f, 0.88f),
}

data class AudioDspState(
    val preset: DesktopDspPreset = DesktopDspPreset.OFF,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val reverb: Float = 0.0f,
    val bassBoostDb: Float = 0.0f,
    val vocalRemoval: Float = 0.0f,
)

/**
 * 2nd-order Biquad stereo filter (Low-pass + Low-shelf Bass Swap) for Yuma's
 * Underwater DJ Automix transition and Bass Boost DSP.
 */
private class StereoBiquadFilter(private val sampleRate: Double = 48000.0) {
    // Low-pass state
    private var lpB0 = 1.0; private var lpB1 = 0.0; private var lpB2 = 0.0
    private var lpA1 = 0.0; private var lpA2 = 0.0
    private var lpX1L = 0.0; private var lpX2L = 0.0; private var lpY1L = 0.0; private var lpY2L = 0.0
    private var lpX1R = 0.0; private var lpX2R = 0.0; private var lpY1R = 0.0; private var lpY2R = 0.0
    private var lpBypass = true

    // Low-shelf bass state
    private var lsB0 = 1.0; private var lsB1 = 0.0; private var lsB2 = 0.0
    private var lsA1 = 0.0; private var lsA2 = 0.0
    private var lsX1L = 0.0; private var lsX2L = 0.0; private var lsY1L = 0.0; private var lsY2L = 0.0
    private var lsX1R = 0.0; private var lsX2R = 0.0; private var lsY1R = 0.0; private var lsY2R = 0.0
    private var lsBypass = true

    fun updateLowPass(cutoffHz: Double, q: Double = 0.707) {
        if (cutoffHz >= 19500.0) {
            lpBypass = true
            return
        }
        lpBypass = false
        val freq = cutoffHz.coerceIn(120.0, sampleRate * 0.45)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)
        val a0 = 1.0 + alpha
        lpB0 = ((1.0 - cosW0) / 2.0) / a0
        lpB1 = (1.0 - cosW0) / a0
        lpB2 = ((1.0 - cosW0) / 2.0) / a0
        lpA1 = (-2.0 * cosW0) / a0
        lpA2 = (1.0 - alpha) / a0
    }

    fun updateLowShelf(gainDb: Double, freqHz: Double = 120.0) {
        if (abs(gainDb) < 0.15) {
            lsBypass = true
            return
        }
        lsBypass = false
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        lsB0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        lsB1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        lsB2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        lsA1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        lsA2 = ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun processLeft(x: Double): Double {
        var out = x
        if (!lpBypass) {
            val y = lpB0 * out + lpB1 * lpX1L + lpB2 * lpX2L - lpA1 * lpY1L - lpA2 * lpY2L
            lpX2L = lpX1L; lpX1L = out; lpY2L = lpY1L; lpY1L = y
            out = y
        }
        if (!lsBypass) {
            val y = lsB0 * out + lsB1 * lsX1L + lsB2 * lsX2L - lsA1 * lsY1L - lsA2 * lsY2L
            lsX2L = lsX1L; lsX1L = out; lsY2L = lsY1L; lsY1L = y
            out = y
        }
        return out
    }

    fun processRight(x: Double): Double {
        var out = x
        if (!lpBypass) {
            val y = lpB0 * out + lpB1 * lpX1R + lpB2 * lpX2R - lpA1 * lpY1R - lpA2 * lpY2R
            lpX2R = lpX1R; lpX1R = out; lpY2R = lpY1R; lpY1R = y
            out = y
        }
        if (!lsBypass) {
            val y = lsB0 * out + lsB1 * lsX1R + lsB2 * lsX2R - lsA1 * lsY1R - lsA2 * lsY2R
            lsX2R = lsX1R; lsX1R = out; lsY2R = lsY1R; lsY1R = y
            out = y
        }
        return out
    }
}

/**
 * Stereo Schroeder/Freeverb-style comb + allpass reverb for Desktop DSP.
 */
private class SimpleStereoReverb(sampleRate: Int = 48000) {
    private val combDelays = intArrayOf(
        (sampleRate * 0.0297).toInt(),
        (sampleRate * 0.0371).toInt(),
        (sampleRate * 0.0411).toInt(),
        (sampleRate * 0.0437).toInt(),
    )
    private val buffersL = Array(combDelays.size) { FloatArray(combDelays[it]) }
    private val buffersR = Array(combDelays.size) { FloatArray(combDelays[it] + 23) }
    private val idxL = IntArray(combDelays.size)
    private val idxR = IntArray(combDelays.size)

    fun process(left: Float, right: Float, wetAmount: Float, out: FloatArray) {
        if (wetAmount <= 0.01f) {
            out[0] = left
            out[1] = right
            return
        }
        val feedback = (0.72f + wetAmount * 0.16f).coerceAtMost(0.88f)
        var sumL = 0f
        var sumR = 0f
        for (i in combDelays.indices) {
            val bL = buffersL[i]
            val pL = idxL[i]
            val delayedL = bL[pL]
            bL[pL] = left + delayedL * feedback
            idxL[i] = (pL + 1) % bL.size
            sumL += delayedL

            val bR = buffersR[i]
            val pR = idxR[i]
            val delayedR = bR[pR]
            bR[pR] = right + delayedR * feedback
            idxR[i] = (pR + 1) % bR.size
            sumR += delayedR
        }
        val wet = wetAmount * 0.25f
        out[0] = left * (1f - wetAmount * 0.25f) + sumL * wet
        out[1] = right * (1f - wetAmount * 0.25f) + sumR * wet
    }
}

/**
 * One independent hardware-backed Audio Deck (Deck A or Deck B).
 */
class DesktopAudioDeck(
    val name: String,
    private val scope: CoroutineScope,
    private val onSpectrumSample: ((FloatArray, Float) -> Unit)? = null,
    private val onDeckCompleted: (DesktopAudioDeck) -> Unit,
) {
    @Volatile var currentTrack: CatalogTrack? = null
        private set
    @Volatile var streamUrlOrPath: String? = null
        private set

    val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val positionMsAtomic = AtomicLong(0L)
    val positionMs: Long get() = positionMsAtomic.get()

    @Volatile var deckVolume: Float = 1.0f
    @Volatile var masterVolume: Float = 0.85f
    @Volatile var lowPassCutoffHz: Double = FILTER_OPEN_HZ
    @Volatile var bassGainDb: Double = 0.0
    @Volatile var dspState: AudioDspState = AudioDspState()

    private var decodeJob: Job? = null
    private var activeProcess: Process? = null
    private var activeLine: SourceDataLine? = null

    val isRunning: Boolean
        get() = isPlaying.get()

    fun play(
        track: CatalogTrack,
        resolvedUrlOrPath: String,
        startPositionMs: Long = 0L,
        initialDeckVolume: Float = 1.0f,
    ) {
        stopInternal()
        currentTrack = track
        streamUrlOrPath = resolvedUrlOrPath
        deckVolume = initialDeckVolume
        lowPassCutoffHz = FILTER_OPEN_HZ
        bassGainDb = 0.0
        positionMsAtomic.set(startPositionMs)
        stopRequested.set(false)
        isPaused.set(false)
        isPlaying.set(true)

        decodeJob = scope.launch(Dispatchers.IO) {
            val targetRate = 48000
            val outFormat = AudioFormat(targetRate.toFloat(), 16, 2, true, false)
            val line = runCatching {
                val info = DataLine.Info(SourceDataLine::class.java, outFormat)
                (AudioSystem.getLine(info) as SourceDataLine).apply {
                    open(outFormat, 16384)
                    start()
                }
            }.getOrNull()
            activeLine = line

            val biquad = StereoBiquadFilter(targetRate.toDouble())
            val reverb = SimpleStereoReverb(targetRate)
            val reverbPair = FloatArray(2)

            var framesPlayed = 0L
            val startSec = startPositionMs / 1000.0

            val playedWithFfmpeg = runCatching {
                val spd = dspState.speed.coerceIn(0.5f, 1.5f)
                val ptc = dspState.pitch.coerceIn(0.5f, 1.5f)
                val filterChain = if (abs(spd - 1f) > 0.01f || abs(ptc - 1f) > 0.01f) {
                    val asetrate = (targetRate * ptc).toInt()
                    val atempo = (spd / ptc).coerceIn(0.5f, 2.0f)
                    "asetrate=$asetrate,aresample=$targetRate,atempo=$atempo"
                } else {
                    "anull"
                }
                val cmd = buildList {
                    add("ffmpeg")
                    add("-v"); add("error")
                    if (startSec > 0.05) {
                        add("-ss"); add(String.format(java.util.Locale.US, "%.3f", startSec))
                    }
                    add("-i"); add(resolvedUrlOrPath.removePrefix("file://"))
                    add("-vn")
                    add("-af"); add(filterChain)
                    add("-f"); add("s16le")
                    add("-ac"); add("2")
                    add("-ar"); add(targetRate.toString())
                    add("pipe:1")
                }
                val proc = ProcessBuilder(cmd).start()
                activeProcess = proc
                val input = BufferedInputStream(proc.inputStream, 32768)
                val rawBuf = ByteArray(4096)
                val outBuf = ByteArray(4096)
                val shortIn = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN)
                val shortOut = ByteBuffer.wrap(outBuf).order(ByteOrder.LITTLE_ENDIAN)

                while (isActive && !stopRequested.get()) {
                    if (isPaused.get()) {
                        delay(20)
                        continue
                    }
                    val read = input.read(rawBuf)
                    if (read <= 0) break
                    val aligned = read and 0xFFFC
                    if (aligned <= 0) continue

                    val dsp = dspState
                    biquad.updateLowPass(lowPassCutoffHz)
                    biquad.updateLowShelf(bassGainDb + dsp.bassBoostDb.toDouble())
                    val totalGain = (deckVolume * masterVolume).coerceIn(0f, 1.5f)
                    val vocalCancel = dsp.vocalRemoval.coerceIn(0f, 1f)
                    val revWet = dsp.reverb.coerceIn(0f, 1f)

                    val numFrames = aligned / 4
                    var bassAccum = 0f
                    val bands = FloatArray(32)

                    for (f in 0 until numFrames) {
                        val byteIdx = f * 4
                        var l = shortIn.getShort(byteIdx) / 32768.0
                        var r = shortIn.getShort(byteIdx + 2) / 32768.0

                        // Karaoke center vocal cancellation (mid-side subtraction keeping sub-bass)
                        if (vocalCancel > 0.01f) {
                            val side = (l - r) * 0.75
                            l = l * (1.0 - vocalCancel) + side * vocalCancel
                            r = r * (1.0 - vocalCancel) - side * vocalCancel
                        }

                        l = biquad.processLeft(l)
                        r = biquad.processRight(r)

                        if (revWet > 0.01f) {
                            reverb.process(l.toFloat(), r.toFloat(), revWet, reverbPair)
                            l = reverbPair[0].toDouble()
                            r = reverbPair[1].toDouble()
                        }

                        val outL = (l * totalGain * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                        val outR = (r * totalGain * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                        shortOut.putShort(byteIdx, outL)
                        shortOut.putShort(byteIdx + 2, outR)

                        val mag = ((abs(l) + abs(r)) * 0.5).toFloat()
                        bassAccum += mag
                        bands[(f * 32) / numFrames] += mag
                    }

                    line?.write(outBuf, 0, aligned)
                    framesPlayed += numFrames
                    val currentPos = startPositionMs + ((framesPlayed * 1000L * dsp.speed) / targetRate).toLong()
                    positionMsAtomic.set(currentPos)

                    if (onSpectrumSample != null && deckVolume > 0.25f) {
                        val normFactor = (numFrames / 32f).coerceAtLeast(1f)
                        for (b in 0 until 32) {
                            bands[b] = (bands[b] / normFactor * 3.2f).coerceIn(0.04f, 1f)
                        }
                        val bassPulse = (bassAccum / numFrames.coerceAtLeast(1) * 2.8f).coerceIn(0f, 1f)
                        onSpectrumSample.invoke(bands, bassPulse)
                    }
                }
                framesPlayed > 0
            }.getOrDefault(false)

            if (!playedWithFfmpeg && !stopRequested.get()) {
                // Pure JVM jFLAC fallback when FFmpeg is not installed on host OS
                runCatching {
                    val stream: InputStream = if (resolvedUrlOrPath.startsWith("http://") || resolvedUrlOrPath.startsWith("https://")) {
                        URL(resolvedUrlOrPath).openStream()
                    } else {
                        FileInputStream(File(resolvedUrlOrPath.removePrefix("file://")))
                    }
                    stream.use { raw ->
                        val decoder = FLACDecoder(BufferedInputStream(raw, 65536))
                        decoder.addPCMProcessor(object : PCMProcessor {
                            private var flacRate = 44100
                            private var flacLine: SourceDataLine? = null
                            override fun processStreamInfo(info: StreamInfo) {
                                flacRate = info.sampleRate
                                val fmt = AudioFormat(info.sampleRate.toFloat(), info.bitsPerSample, info.channels, true, false)
                                flacLine = runCatching {
                                    (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, fmt)) as SourceDataLine).apply {
                                        open(fmt, 16384)
                                        start()
                                    }
                                }.getOrNull()
                            }

                            override fun processPCM(pcm: ByteData) {
                                if (stopRequested.get()) throw InterruptedException()
                                while (isPaused.get() && !stopRequested.get()) {
                                    Thread.sleep(20)
                                }
                                flacLine?.write(pcm.data, 0, pcm.len)
                                framesPlayed += pcm.len / 4
                                positionMsAtomic.set(startPositionMs + (framesPlayed * 1000L) / flacRate.coerceAtLeast(1))
                            }
                        })
                        decoder.decode()
                    }
                }
            }

            runCatching {
                line?.drain()
                line?.close()
            }
            activeLine = null
            activeProcess = null

            val finishedNaturally = !stopRequested.get()
            isPlaying.set(false)
            if (finishedNaturally) {
                onDeckCompleted(this@DesktopAudioDeck)
            }
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        isPlaying.set(!paused && currentTrack != null)
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        stopRequested.set(true)
        isPlaying.set(false)
        isPaused.set(false)
        runCatching { activeProcess?.destroyForcibly() }
        runCatching { activeLine?.stop(); activeLine?.close() }
        decodeJob?.cancel()
        activeProcess = null
        activeLine = null
    }
}

/**
 * Dual-Deck Lossless & Automix Controller for Square-Yuma Desktop.
 * Coordinates Deck A and Deck B, Yuma's Underwater DJ Filter & Bass-Swap crossfade,
 * debounced seeking, queue management, and real-time spectrum/bass glow telemetry.
 */
class DesktopDualDeckPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val streamUrlResolver: (CatalogTrack) -> String,
) {
    private val _queue = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val queue: StateFlow<List<CatalogTrack>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<CatalogTrack?>(null)
    val currentTrack: StateFlow<CatalogTrack?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _masterVolume = MutableStateFlow(0.85f)
    val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()

    private val _automixState = MutableStateFlow(AutomixUiState())
    val automixState: StateFlow<AutomixUiState> = _automixState.asStateFlow()

    private val _dspState = MutableStateFlow(AudioDspState())
    val dspState: StateFlow<AudioDspState> = _dspState.asStateFlow()

    private val _liveSpectrum = MutableStateFlow(FloatArray(32) { 0.08f })
    val liveSpectrum: StateFlow<FloatArray> = _liveSpectrum.asStateFlow()

    private val _bassPulse = MutableStateFlow(0f)
    val bassPulse: StateFlow<Float> = _bassPulse.asStateFlow()

    private lateinit var deckA: DesktopAudioDeck
    private lateinit var deckB: DesktopAudioDeck
    @Volatile private var primaryDeck: DesktopAudioDeck
    @Volatile private var secondaryDeck: DesktopAudioDeck

    private var crossfadeJob: Job? = null
    @Volatile private var lastSeekTimestampMs: Long = 0L

    init {
        deckA = DesktopAudioDeck(
            name = "Deck A",
            scope = scope,
            onSpectrumSample = { bands, bass ->
                _liveSpectrum.value = bands
                _bassPulse.value = bass
            },
            onDeckCompleted = ::onDeckFinishedNaturally,
        )
        deckB = DesktopAudioDeck(
            name = "Deck B",
            scope = scope,
            onSpectrumSample = { bands, bass ->
                _liveSpectrum.value = bands
                _bassPulse.value = bass
            },
            onDeckCompleted = ::onDeckFinishedNaturally,
        )
        primaryDeck = deckA
        secondaryDeck = deckB

        // 30 Hz telemetry & Automix scheduler loop
        scope.launch {
            while (isActive) {
                delay(33)
                if (!_isPlaying.value) continue
                val pos = primaryDeck.positionMs
                _positionMs.value = pos

                val track = _currentTrack.value ?: continue
                val dur = track.durationMs.takeIf { it > 0L } ?: _durationMs.value
                if (dur <= 0L) continue

                val mixCfg = _automixState.value
                val fadeMs = (mixCfg.crossfadeDurationSec * 1000L).toLong().coerceIn(2000L, 16000L)
                val sinceSeek = System.currentTimeMillis() - lastSeekTimestampMs

                if (mixCfg.enabled &&
                    !mixCfg.isMixing &&
                    sinceSeek > 1500L &&
                    dur > fadeMs + 4000L &&
                    pos >= dur - fadeMs &&
                    hasNextTrack()
                ) {
                    startAutomixCrossfade(fadeMs)
                }
            }
        }
    }

    private fun hasNextTrack(): Boolean {
        val q = _queue.value
        return q.isNotEmpty() && _currentIndex.value + 1 < q.size
    }

    fun playTrackList(tracks: List<CatalogTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        _queue.value = tracks
        _currentIndex.value = idx
        startTrackOnPrimary(tracks[idx], 0L)
    }

    fun playSingle(track: CatalogTrack) {
        val existingIdx = _queue.value.indexOfFirst { it.uri == track.uri }
        if (existingIdx >= 0) {
            _currentIndex.value = existingIdx
            startTrackOnPrimary(_queue.value[existingIdx], 0L)
        } else {
            val newQueue = listOf(track) + _queue.value
            _queue.value = newQueue
            _currentIndex.value = 0
            startTrackOnPrimary(track, 0L)
        }
    }

    private fun startTrackOnPrimary(track: CatalogTrack, startMs: Long) {
        cancelCrossfade()
        secondaryDeck.stop()
        _currentTrack.value = track
        _durationMs.value = track.durationMs.coerceAtLeast(1000L)
        _positionMs.value = startMs
        _isPlaying.value = true

        val resolved = streamUrlResolver(track)
        primaryDeck.masterVolume = _masterVolume.value
        primaryDeck.dspState = _dspState.value
        primaryDeck.play(
            track = track,
            resolvedUrlOrPath = resolved,
            startPositionMs = startMs,
            initialDeckVolume = 1.0f,
        )
    }

    private fun startAutomixCrossfade(fadeDurationMs: Long) {
        val q = _queue.value
        val nextIdx = _currentIndex.value + 1
        if (nextIdx >= q.size) return

        val outgoing = _currentTrack.value ?: return
        val incoming = q[nextIdx]
        val incomingUrl = streamUrlResolver(incoming)

        val outDeck = primaryDeck
        val inDeck = secondaryDeck

        inDeck.masterVolume = _masterVolume.value
        inDeck.dspState = _dspState.value
        inDeck.play(
            track = incoming,
            resolvedUrlOrPath = incomingUrl,
            startPositionMs = 0L,
            initialDeckVolume = 0f,
        )

        // Advance UI metadata at 35% into the transition (when incoming track begins rising)
        var uiSwitchedToIncoming = false

        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            val startWallMs = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startWallMs
                val progress = (elapsed.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)

                val outGain = (outgoingStageGain(progress) * outgoingMidDuckGain(progress).toFloat()).coerceIn(0f, 1f)
                val inGain = incomingStageGain(progress).coerceIn(0f, 1f)
                val lpHz = outgoingLowPassHz(progress)
                val djSwap = _automixState.value.djBassSwapEnabled

                outDeck.deckVolume = outGain
                outDeck.lowPassCutoffHz = if (djSwap) lpHz else FILTER_OPEN_HZ
                outDeck.bassGainDb = if (djSwap) outgoingBassGainDb(progress) else 0.0

                inDeck.deckVolume = inGain
                inDeck.lowPassCutoffHz = FILTER_OPEN_HZ
                inDeck.bassGainDb = if (djSwap) incomingBassGainDb(progress) else 0.0

                if (!uiSwitchedToIncoming && progress >= 0.35f) {
                    uiSwitchedToIncoming = true
                    _currentIndex.value = nextIdx
                    _currentTrack.value = incoming
                    _durationMs.value = incoming.durationMs.coerceAtLeast(1000L)
                    // Swap primary and secondary deck references so position tracking follows the incoming deck!
                    primaryDeck = inDeck
                    secondaryDeck = outDeck
                }

                _automixState.value = _automixState.value.copy(
                    isMixing = true,
                    progress = progress,
                    outgoingTrack = outgoing,
                    incomingTrack = incoming,
                    outgoingGain = outGain,
                    incomingGain = inGain,
                    filterCutoffHz = lpHz.toFloat(),
                )

                if (progress >= 1.0f) break
                delay(25)
            }

            // Finish crossfade cleanly without pausing/restarting the incoming deck
            outDeck.stop()
            inDeck.deckVolume = 1.0f
            inDeck.lowPassCutoffHz = FILTER_OPEN_HZ
            inDeck.bassGainDb = 0.0

            if (!uiSwitchedToIncoming) {
                _currentIndex.value = nextIdx
                _currentTrack.value = incoming
                _durationMs.value = incoming.durationMs.coerceAtLeast(1000L)
                primaryDeck = inDeck
                secondaryDeck = outDeck
            }

            _automixState.value = _automixState.value.copy(
                isMixing = false,
                progress = 0f,
                outgoingTrack = null,
                incomingTrack = null,
                outgoingGain = 1f,
                incomingGain = 0f,
                filterCutoffHz = 20000f,
            )
        }
    }

    private fun onDeckFinishedNaturally(deck: DesktopAudioDeck) {
        if (deck !== primaryDeck) return
        if (_automixState.value.isMixing) return
        scope.launch {
            if (hasNextTrack()) {
                skipNext()
            } else {
                _isPlaying.value = false
                _bassPulse.value = 0f
            }
        }
    }

    fun cueTrackList(tracks: List<CatalogTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty() || _currentTrack.value != null) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        _queue.value = tracks
        _currentIndex.value = idx
        _currentTrack.value = tracks[idx]
        _durationMs.value = tracks[idx].durationMs.coerceAtLeast(1000L)
        _positionMs.value = 0L
        _isPlaying.value = false
    }

    fun togglePlayPause() {
        val track = _currentTrack.value
        if (track == null) {
            if (_queue.value.isNotEmpty()) {
                playTrackList(_queue.value, 0)
            }
            return
        }
        if (!_isPlaying.value && !primaryDeck.isRunning) {
            startTrackOnPrimary(track, _positionMs.value)
            return
        }
        val nextPlaying = !_isPlaying.value
        _isPlaying.value = nextPlaying
        primaryDeck.setPaused(!nextPlaying)
        if (_automixState.value.isMixing) {
            secondaryDeck.setPaused(!nextPlaying)
        }
        if (!nextPlaying) {
            _bassPulse.value = 0f
        }
    }

    fun seekTo(targetMs: Long) {
        val track = _currentTrack.value ?: return
        val clamped = targetMs.coerceIn(0L, _durationMs.value.coerceAtLeast(1000L))
        lastSeekTimestampMs = System.currentTimeMillis()
        cancelCrossfade()
        secondaryDeck.stop()
        _positionMs.value = clamped
        _isPlaying.value = true
        primaryDeck.play(
            track = track,
            resolvedUrlOrPath = streamUrlResolver(track),
            startPositionMs = clamped,
            initialDeckVolume = 1.0f,
        )
    }

    fun skipNext() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIdx = (_currentIndex.value + 1) % q.size
        _currentIndex.value = nextIdx
        startTrackOnPrimary(q[nextIdx], 0L)
    }

    fun skipPrevious() {
        if (_positionMs.value > 4000L) {
            seekTo(0L)
            return
        }
        val q = _queue.value
        if (q.isEmpty()) return
        val prevIdx = if (_currentIndex.value - 1 >= 0) _currentIndex.value - 1 else q.lastIndex
        _currentIndex.value = prevIdx
        startTrackOnPrimary(q[prevIdx], 0L)
    }

    fun setMasterVolume(vol: Float) {
        val v = vol.coerceIn(0f, 1f)
        _masterVolume.value = v
        deckA.masterVolume = v
        deckB.masterVolume = v
    }

    fun setAutomixEnabled(enabled: Boolean) {
        if (!enabled) cancelCrossfade()
        _automixState.value = _automixState.value.copy(enabled = enabled)
    }

    fun setCrossfadeDurationSec(seconds: Float) {
        _automixState.value = _automixState.value.copy(crossfadeDurationSec = seconds.coerceIn(2f, 15f))
    }

    fun setDjBassSwapEnabled(enabled: Boolean) {
        _automixState.value = _automixState.value.copy(djBassSwapEnabled = enabled)
    }

    fun applyDspPreset(preset: DesktopDspPreset) {
        val newState = AudioDspState(
            preset = preset,
            speed = preset.speed,
            pitch = preset.pitch,
            reverb = preset.reverb,
            bassBoostDb = preset.bassDb,
            vocalRemoval = preset.vocalRemove,
        )
        updateDspState(newState, restartForTempo = abs(newState.speed - _dspState.value.speed) > 0.01f || abs(newState.pitch - _dspState.value.pitch) > 0.01f)
    }

    fun updateDspState(newState: AudioDspState, restartForTempo: Boolean = false) {
        _dspState.value = newState
        deckA.dspState = newState
        deckB.dspState = newState
        if (restartForTempo && _isPlaying.value && _currentTrack.value != null) {
            seekTo(_positionMs.value)
        }
    }

    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        primaryDeck.deckVolume = 1.0f
        primaryDeck.lowPassCutoffHz = FILTER_OPEN_HZ
        primaryDeck.bassGainDb = 0.0
        _automixState.value = _automixState.value.copy(
            isMixing = false,
            progress = 0f,
            outgoingTrack = null,
            incomingTrack = null,
            outgoingGain = 1f,
            incomingGain = 0f,
            filterCutoffHz = 20000f,
        )
    }

    fun release() {
        cancelCrossfade()
        deckA.stop()
        deckB.stop()
    }
}
