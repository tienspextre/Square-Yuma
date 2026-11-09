package moe.rukamori.archivetune.audiodsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LoopAudioProcessorTest {

    private val rate = 1000 // 1 frame = 1 ms, so a 10 ms seam is 10 frames

    /** Mono frames whose value is their own track frame number. */
    private fun block(from: Int, frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) b.putShort((from + i).toShort())
        b.flip()
        return b
    }

    private fun drain(p: LoopAudioProcessor): ShortArray {
        val out = p.output
        val s = ShortArray(out.remaining() / 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().get(s)
        return s
    }

    @Test
    fun loopsSampleExactAndStopsReadingTheSong() {
        val p = LoopAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata(0))
        // Loop 1.0 s .. 1.5 s: frames 1000..1499.
        p.arm = LoopAudioProcessor.Arm(1_000_000, 1_500_000)

        val heard = ArrayList<Short>()
        var next = 0
        val input = block(0, 3000)
        while (heard.size < 2600) {
            p.queueInput(input)
            heard.addAll(drain(p).toList())
            next = 3000 - input.remaining() / 2
        }
        // The song stopped being read exactly at the loop end.
        assertEquals(1500, next)
        assertTrue(p.looping)
        // Untouched before the seam, then the loop, again and again.
        for (t in 0 until 1490) assertEquals(t.toShort(), heard[t])
        for (t in 1500 until 2490) {
            val q = (t - 1500) % 500
            if (q < 490) assertEquals("frame $t", (1000 + q).toShort(), heard[t])
        }
        // The seam leans from the loop's last frames into the lead-in before its start (990..999).
        val w = (5 + 0.5) / 10
        assertEquals((1495 * (1 - w) + 995 * w).toInt().toShort(), heard[1495])
        assertEquals((1495 * (1 - w) + 995 * w).toInt().toShort(), heard[1995])
    }

    @Test
    fun armedTooLateLeavesTheSongAlone() {
        val p = LoopAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata(1_200_000)) // already inside the loop
        p.arm = LoopAudioProcessor.Arm(1_000_000, 1_500_000)
        val input = block(1200, 1000)
        p.queueInput(input)
        assertEquals(0, input.remaining())
        assertEquals(1200.toShort(), drain(p)[0])
        assertTrue(!p.looping)
    }

    @Test
    fun disarmingHandsBackToTheSongAtTheLoopEnd() {
        val p = LoopAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata(0))
        p.arm = LoopAudioProcessor.Arm(1_000_000, 1_500_000)
        val input = block(0, 3000)
        repeat(4) { p.queueInput(input); drain(p) }
        assertTrue(p.looping)
        p.arm = null
        p.queueInput(input)
        assertEquals(1500.toShort(), drain(p)[0])
    }
}
