/*
 * The outgoing side of a loop mix, after BitChord's LoopMixPort
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0): at the frame where the song reaches
 * the end of an instrumental loop, it stops reading the song and plays the loop instead,
 * sample-exact, because the loop is the song's own audio counted in the same frames.
 */
package moe.rukamori.archivetune.audiodsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * First in a deck's chain, so it works in the song's own frames, before any stretch. [arm] it
 * with a loop on the track clock before playback reaches it: the processor records the loop as
 * it plays, and from its end on repeats it, leaving the song's input unread so the player
 * neither advances into the rest of the song nor ends. Disarm (null) and it reads the song
 * again from the loop's end.
 *
 * The seam is crossfaded against the audio just before the loop start, which is what follows
 * the loop end musically, so a whole-bar loop repeats without a click.
 */
@UnstableApi
class LoopAudioProcessor : BaseAudioProcessor() {

    data class Arm(val startUs: Long, val endUs: Long)

    /** Set from the controller's thread; read once per block. */
    @Volatile var arm: Arm? = null

    /** True once the loop has taken over from the song. */
    @Volatile var looping = false
        private set

    private var channels = 0
    private var rate = 0
    private var seen: Arm? = null
    /** Track frame of the next input frame. */
    private var position = 0L
    private var loop: PcmLoop? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        position = streamMetadata.positionOffsetUs * rate / 1_000_000L
        // A flush mid-loop is a parameter change, not a seek (a seek disarms first): keep looping.
        if (!looping) seen = null
    }

    override fun onReset() {
        seen = null
        loop = null
        looping = false
        position = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val wanted = arm
        if (wanted != seen) {
            seen = wanted
            // Disarmed mid-loop, the song carries on from the loop's end, where its input stopped.
            // ponytail: the player's clock is then ahead by the time looped; the deck is being
            // retired or seeked whenever that happens, so nothing reads it.
            looping = false
            loop = wanted?.let {
                PcmLoop.of(channels, it.startUs * rate / 1_000_000L, it.endUs * rate / 1_000_000L, rate)
                    ?.takeIf { l -> l.canStartAt(position) }
            }
        }
        val l = loop
        if (l != null && l.looping) {
            looping = true
            val frames = LOOP_BLOCK
            val out = replaceOutputBuffer(frames * channels * 2).order(ByteOrder.nativeOrder())
            val block = ShortArray(frames * channels)
            l.render(block, frames)
            out.asShortBuffer().put(block)
            out.position(frames * channels * 2)
            out.flip()
            return
        }
        val bytesPerFrame = channels * 2
        var frames = inputBuffer.remaining() / bytesPerFrame
        if (frames == 0) return
        if (l != null) frames = minOf(frames.toLong(), l.framesUntilLoop(position)).toInt()
        val samples = ShortArray(frames * channels)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
        inputBuffer.position(inputBuffer.position() + frames * bytesPerFrame)
        l?.pass(samples, frames, position)
        position += frames
        val out = replaceOutputBuffer(frames * bytesPerFrame).order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(samples)
        out.position(frames * bytesPerFrame)
        out.flip()
    }

    companion object {
        private const val LOOP_BLOCK = 1024
    }
}

/**
 * The loop itself, on interleaved 16-bit frames: records track frames [start - fade, end) as
 * they pass, then plays [start, end) forever. Frames in the last [fade] of the loop are mixed
 * toward the recorded lead-in before [start], on the first pass as on every repeat.
 */
class PcmLoop private constructor(
    private val channels: Int,
    private val start: Long,
    private val end: Long,
    private val fade: Int,
) {
    private val length = (end - start).toInt()
    /** Frames [start - fade, end), interleaved. */
    private val recorded = ShortArray((length + fade) * channels)
    var looping = false
        private set
    private var read = 0

    /** Recording needs the lead-in, so playback must not be past it yet. */
    fun canStartAt(position: Long) = position <= start - fade

    /** Input frames that may still pass before the loop takes over (all of them before it is due). */
    fun framesUntilLoop(position: Long) = (end - position).coerceAtLeast(0L)

    /** Passes [frames] input frames starting at track frame [position]: recorded, seam-faded in place. */
    fun pass(samples: ShortArray, frames: Int, position: Long) {
        for (i in 0 until frames) {
            val p = position + i
            if (p < start - fade || p >= end) continue
            val r = (p - (start - fade)).toInt()
            for (c in 0 until channels) recorded[r * channels + c] = samples[i * channels + c]
            if (p >= start) {
                val q = (p - start).toInt()
                for (c in 0 until channels) samples[i * channels + c] = at(q, c)
            }
        }
        if (position + frames >= end) looping = true
    }

    /** The loop's next [frames] frames. */
    fun render(into: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            for (c in 0 until channels) into[i * channels + c] = at(read, c)
            read = (read + 1) % length
        }
    }

    /** Loop frame [q] on channel [c], crossfaded into the lead-in over the last [fade] frames. */
    private fun at(q: Int, c: Int): Short {
        val body = recorded[(fade + q) * channels + c].toInt()
        val k = q - (length - fade)
        if (k < 0) return body.toShort()
        val w = (k + 0.5) / fade
        val lead = recorded[k * channels + c].toInt()
        return (body * (1 - w) + lead * w).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    companion object {
        /** Long enough to hide a seam, short enough not to smear the downbeat it lands on. */
        const val FADE_SECONDS = 0.01
        /** Four bars at 60 BPM; anything longer is not a loop. */
        const val MAX_SECONDS = 16.5

        fun of(channels: Int, start: Long, end: Long, rate: Int): PcmLoop? {
            val fade = (rate * FADE_SECONDS).toInt().coerceAtLeast(1)
            val length = end - start
            if (channels < 1 || rate <= 0 || length <= fade * 2 || length > rate * MAX_SECONDS || start < fade) return null
            return PcmLoop(channels, start, end, fade)
        }
    }
}
