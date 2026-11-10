package moe.rukamori.archivetune.audiodsp

// Copied from core/audio-dsp LoopAudioProcessor.kt, whose processor half is media3.

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
