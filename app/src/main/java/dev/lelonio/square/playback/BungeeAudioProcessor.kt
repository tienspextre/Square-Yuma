package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Speed and pitch for the players that are not the Spotify engine.
 *
 * Spotify's audio goes through the app's own sink, where a phase vocoder does
 * this work; a file on the phone goes through ExoPlayer, which has its own
 * stretcher built for speech. On music that one smears every attack and adds a
 * metallic edge, which is exactly what the same track sounds like on the two
 * paths at the same setting. This puts the same vocoder in ExoPlayer's chain,
 * so a song is altered the same way whatever it was read from.
 *
 * Sixteen-bit PCM only. Everything the decoders here hand over is that, and a
 * format this cannot take is passed through rather than refused: an untouched
 * song is a much better answer than silence.
 */
@OptIn(UnstableApi::class)
class BungeeAudioProcessor : BaseAudioProcessor() {

    private var stretcher: Stretcher? = null
    private var speed = 1f
    private var pitch = 1f

    /** Logged once rather than per packet, which would be several a second. */
    private var failureReported = false

    /**
     * Set from the sink's chain when the listener moves either slider.
     *
     * The stretcher takes them per packet, so nothing has to be rebuilt: what
     * is playing changes on the next few milliseconds of audio.
     */
    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        this.speed = speed
        this.pitch = pitch
    }

    val isAltering: Boolean get() = speed != 1f || pitch != 1f

    /**
     * Pulse automix tempo map, keyed on this deck's own track position (source ms). Each packet
     * runs at its rate × the user's speed, so a mix never changes playbackParameters (every change
     * flushes the sink). Null = off.
     */
    @Volatile
    var schedule: moe.rukamori.archivetune.audiodsp.pulse.TempoMap? = null

    /** Beat-phase hold: a small speed trim (±4 %) on top of the map. */
    @Volatile
    var trim = 1f

    /** Track frame the next input packet starts at. */
    @Volatile
    var sourceFrame = 0L
        private set

    /** Frames in vs frames out since the sink's last checkpoint, for the chain's media clock. */
    val playout = PlayoutMap()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 16-bit only. With float output off Media3 hands every chain 16-bit, so anything else
        // means a configuration this was never built for: pass it through untouched rather
        // than fail playback over an effect.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        sourceFrame = streamMetadata.positionOffsetUs * inputAudioFormat.sampleRate.coerceAtLeast(1) / 1_000_000L
        playout.reset()
        onFlush()
    }

    override fun onFlush() {
        // Kept, deliberately.
        //
        // The sink flushes its processors whenever the parameters change, and
        // throwing the vocoder away here meant the next packet arrived at a
        // stretcher with an empty window: it has to fill one before it can
        // resynthesise anything, and that silence is the hole heard when the
        // speed slider moves. What it carries over instead is a few tens of
        // milliseconds of the audio before the change, which after a seek is
        // the only cost — and one nobody can hear.
        //
        // The format is a different matter: a stretcher is built for a sample
        // rate and a channel count, and a track with either different needs a
        // new one. That is what [stretcher] checks.
    }

    override fun onReset() {
        stretcher?.release()
        stretcher = null
        stretched = false
        historyFrames = 0
        failureReported = false
        sourceFrame = 0L
        playout.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val fb = frameBytes
        val inFrames = size / fb
        val map = schedule
        val rate = if (map == null) 1f
        else map.rateAt(sourceFrame * 1000.0 / inputAudioFormat.sampleRate).toFloat()
        val speed = this.speed * rate * (if (map != null) trim else 1f)
        sourceFrame += inFrames
        if (map != null) playout.scheduled = true

        // Nothing to do at rest, and doing nothing is worth the check: this
        // runs on the audio thread for every packet of every track.
        if (speed == 1f && pitch == 1f) {
            val held = stretcher?.takeIf { stretched }?.latencyFrames() ?: 0
            if (stretcher != null) {
                // Back to speed one: the stretcher still holds what went in and was not
                // heard yet. Dropping it skipped that much of the song, the stumble at the
                // end of a tempo-matched mix; it is played from the history instead.
                stretcher?.release()
                stretcher = null
                stretched = false
            }
            val heldBytes = minOf(held, historyFrames) * frameBytes
            val out = replaceOutputBuffer(heldBytes + size)
            if (heldBytes > 0) copyHistoryTail(heldBytes, out)
            out.put(inputBuffer).flip()
            historyFrames = 0
            playout.add(inFrames.toLong(), (heldBytes + size).toLong() / fb, speed)
            return
        }

        val frames = size / (inputAudioFormat.channelCount * BYTES_PER_SAMPLE)
        val stretcher = stretcher(frames)
        remember(inputBuffer, size)
        val produced = stretcher?.process(inputBuffer, size, speed, pitch)
        if (produced != null) stretched = true
        if (produced == null) {
            if (!failureReported) {
                failureReported = true
                android.util.Log.w(TAG, "stretcher unavailable, playing untouched")
            }
            // Position is already past what was read on the failed attempt for
            // a partial read; take what is left rather than dropping the packet.
            val remaining = inputBuffer.remaining()
            if (remaining > 0) replaceOutputBuffer(remaining).put(inputBuffer).flip()
            playout.add(inFrames.toLong(), remaining.toLong() / fb, 1f)
            return
        }

        playout.add(inFrames.toLong(), produced.remaining().toLong() / fb, speed)
        replaceOutputBuffer(produced.remaining()).put(produced).flip()
    }

    /** One stretcher per format, sized for the largest packet seen so far. */
    private fun stretcher(frames: Int): Stretcher? {
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val existing = stretcher
        if (existing != null && frames <= maxFrames && rate == builtForRate &&
            channels == builtForChannels
        ) {
            return existing
        }

        existing?.release()
        maxFrames = maxOf(frames, maxFrames, MIN_PACKET_FRAMES)
        builtForRate = rate
        builtForChannels = channels
        return Stretcher.create(rate, channels, maxFrames).also { stretcher = it }
    }

    /** The last input that went into the stretcher, interleaved, as a ring. */
    private val history = ByteArray(HISTORY_FRAMES * MAX_CHANNELS * BYTES_PER_SAMPLE)
    private var historyEnd = 0
    private var historyFrames = 0
    private var stretched = false
    private val frameBytes get() = inputAudioFormat.channelCount * BYTES_PER_SAMPLE

    private fun remember(source: ByteBuffer, size: Int) {
        val fb = frameBytes
        if (fb * HISTORY_FRAMES > history.size) return
        val cap = fb * HISTORY_FRAMES
        val view = source.duplicate()
        var left = size
        // Only the newest [cap] bytes matter.
        if (left > cap) { view.position(view.position() + left - cap); left = cap }
        while (left > 0) {
            val n = minOf(left, cap - historyEnd)
            view.get(history, historyEnd, n)
            historyEnd = (historyEnd + n) % cap
            left -= n
        }
        historyFrames = minOf(HISTORY_FRAMES, historyFrames + size / fb)
    }

    private fun copyHistoryTail(bytes: Int, out: ByteBuffer) {
        val cap = frameBytes * HISTORY_FRAMES
        var start = (historyEnd - bytes + cap) % cap
        var left = bytes
        while (left > 0) {
            val n = minOf(left, cap - start)
            out.put(history, start, n)
            start = (start + n) % cap
            left -= n
        }
    }

    /** For the chain's media clock. */
    val outputSampleRate: Int get() = if (isActive) inputAudioFormat.sampleRate else 0

    private var maxFrames = 0
    private var builtForRate = 0
    private var builtForChannels = 0

    private companion object {
        const val TAG = "SquareStretch"
        const val BYTES_PER_SAMPLE = 2

        /** A decoder's packet, with room to spare, so the first one fits. */
        const val MIN_PACKET_FRAMES = 8192

        /** Far more than Bungee's window holds back, at any rate this plays. */
        const val HISTORY_FRAMES = 16384
        const val MAX_CHANNELS = 8
    }
}

/**
 * Output frames → input (media) frames, piecewise linear, one point per rate change. Lets the
 * chain report the media clock while a tempo map varies the rate inside a single checkpoint.
 */
class PlayoutMap {
    private val outAt = ArrayList<Long>()
    private val inAt = ArrayList<Long>()
    private var outTotal = 0L
    private var inTotal = 0L
    private var lastRate = Float.NaN

    /** Set once a tempo map ran since the last reset; until then the plain speed math holds. */
    @Volatile
    var scheduled = false

    @Synchronized
    fun reset() {
        outAt.clear(); inAt.clear()
        outTotal = 0L; inTotal = 0L
        lastRate = Float.NaN
        scheduled = false
    }

    @Synchronized
    fun add(inFrames: Long, outFrames: Long, rate: Float) {
        if (rate != lastRate) {
            outAt += outTotal; inAt += inTotal
            lastRate = rate
        }
        outTotal += outFrames
        inTotal += inFrames
    }

    /** Media frames behind [outFrames] of playout, or null when no tempo map ran. */
    @Synchronized
    fun mediaFrames(outFrames: Long): Long? {
        if (!scheduled || outAt.isEmpty()) return null
        var i = outAt.size - 1
        while (i > 0 && outAt[i] > outFrames) i--
        val o0 = outAt[i]
        val i0 = inAt[i]
        val o1 = if (i + 1 < outAt.size) outAt[i + 1] else outTotal
        val i1 = if (i + 1 < inAt.size) inAt[i + 1] else inTotal
        val ratio = if (o1 > o0) (i1 - i0).toDouble() / (o1 - o0) else lastRate.toDouble().takeIf { !it.isNaN() } ?: 1.0
        return i0 + ((outFrames - o0) * ratio).toLong()
    }
}

/**
 * The sink's chain, with the vocoder in it instead of the platform's stretcher.
 *
 * ExoPlayer asks the chain to apply speed and pitch, and asks it afterwards how
 * long the audio it has played was in media time. Both answers have to come
 * from whoever is doing the stretching, or the position on screen runs at a
 * different rate from the sound.
 */
/**
 * The same vocal removal, for the players that are not the Spotify engine.
 *
 * In the chain before the stretcher, because the trick rests on what the two
 * channels have in common and a resynthesised pair no longer has the same two.
 * See [CentreExtractor].
 */
@OptIn(UnstableApi::class)
class VocalAudioProcessor : BaseAudioProcessor() {

    private val vocals = CentreExtractor()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 16-bit only. With float output off Media3 hands every chain 16-bit, so anything else
        // means a configuration this was never built for: pass it through untouched rather
        // than fail playback over an effect.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val amount = AudioEffects.karaoke.value
        val out = replaceOutputBuffer(size)
        val start = out.position()
        out.put(inputBuffer)
        out.position(start)
        if (amount > 0f) {
            vocals.apply(
                out,
                size,
                inputAudioFormat.channelCount,
                inputAudioFormat.sampleRate,
                amount,
            )
        }
        out.position(start)
        out.limit(start + size)
    }
}

/**
 * The equaliser, for the players that are not the Spotify engine.
 *
 * After the stretcher, so the bands sit where they were set whatever the speed
 * and pitch: before it, a pitch shift would carry the bass boost up with the
 * bass. See [Equalizer].
 */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor : BaseAudioProcessor() {

    private val filter = EqualizerFilter()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 16-bit only. With float output off Media3 hands every chain 16-bit, so anything else
        // means a configuration this was never built for: pass it through untouched rather
        // than fail playback over an effect.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val start = out.position()
        out.put(inputBuffer)
        out.position(start)
        filter.apply(out, size, inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        out.position(start)
        out.limit(start + size)
    }
}

@OptIn(UnstableApi::class)
class BungeeProcessorChain(
    private val bungee: BungeeAudioProcessor = BungeeAudioProcessor(),
    /** Run after the vocoder, at speed one: YouTube Music's resampling to its mixer. */
    private val after: Array<AudioProcessor> = emptyArray(),
    /** Run first, on the song's own frames: the automix loop. */
    private val before: Array<AudioProcessor> = emptyArray(),
) : DefaultAudioSink.AudioProcessorChain {

    private var parameters = PlaybackParameters.DEFAULT

    private val vocals = VocalAudioProcessor()

    private val equalizer = EqualizerAudioProcessor()

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(*before, vocals, bungee, equalizer, *after)

    override fun applyPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): PlaybackParameters {
        parameters = playbackParameters
        bungee.playout.reset()
        bungee.setSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    /** How much media a stretch of playing covers, which is the speed itself. */
    override fun getMediaDuration(playoutDuration: Long): Long {
        // Under a tempo map the rate varied inside this checkpoint: read it off the frame counts.
        // ponytail: assumes the stretcher keeps the sample rate (it does; pitch is separate).
        val rate = bungee.outputSampleRate
        if (rate > 0) bungee.playout.mediaFrames(playoutDuration * rate / 1_000_000L)?.let { return it * 1_000_000L / rate }
        return Util.getMediaDurationForPlayoutDuration(playoutDuration, parameters.speed)
    }

    /** Nothing is dropped here; silence is skipped by nobody in this chain. */
    override fun getSkippedOutputFrameCount(): Long = 0
}
