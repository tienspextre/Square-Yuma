package moe.rukamori.archivetune.audiodsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import moe.rukamori.archivetune.audiodsp.pulse.StemMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjFilterStemBusTest {
    private val rate = 44100

    /** Stereo 16-bit: [l] and [r] sample functions over [n] frames. */
    private fun run(stems: StemMix?, n: Int, l: (Int) -> Double, r: (Int) -> Double): Pair<DoubleArray, DoubleArray> {
        val p = DjFilterAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata(0))
        p.applyStems(stems)
        val outL = ArrayList<Double>()
        val outR = ArrayList<Double>()
        val block = 1024
        var i = 0
        while (i < n) {
            val m = minOf(block, n - i)
            val b = ByteBuffer.allocateDirect(m * 4).order(ByteOrder.nativeOrder())
            for (k in 0 until m) { b.putShort((l(i + k) * 32767).toInt().toShort()); b.putShort((r(i + k) * 32767).toInt().toShort()) }
            b.flip()
            p.queueInput(b)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.remaining() >= 4) { outL += o.short / 32768.0; outR += o.short / 32768.0 }
            i += m
        }
        return outL.toDoubleArray() to outR.toDoubleArray()
    }

    private fun rms(v: DoubleArray, from: Int) = sqrt(v.drop(from).sumOf { it * it } / (v.size - from))
    private fun tone(hz: Double, a: Double = 0.4) = { i: Int -> a * sin(2 * PI * hz * i / rate) }

    @Test
    fun unityStemsKeepTheLevelAndNoStemsPassAudioUnchanged() {
        for (hz in listOf(60.0, 250.0, 1000.0, 5000.0, 9000.0)) {
            val t = tone(hz)
            val (l, _) = run(StemMix.FULL, 16384, t, t)
            assertEquals("$hz Hz", rms(DoubleArray(8192) { t(it) }, 0), rms(l, 8192), 0.01)
        }
        val t = tone(1000.0)
        val (l, _) = run(null, 4096, t, t)
        for (i in 0 until 4096) assertEquals(t(i), l[i], 1e-4)
    }

    @Test
    fun vocalDuckCutsTheCentreAndKeepsTheSide() {
        val t = tone(1000.0)
        val duck = StemMix(1f, 1f, 1f, 0f)
        // Centre (same in both channels): ducked.
        val (centre, _) = run(duck, 16384, t, t)
        assertTrue("centre rms ${rms(centre, 8192)}", rms(centre, 8192) < 0.05 * rms(DoubleArray(8192) { t(it) }, 0))
        // Side (opposite polarity): untouched.
        val (side, _) = run(duck, 16384, t) { -t(it) }
        assertEquals(rms(DoubleArray(8192) { t(it) }, 0), rms(side, 8192), 0.02)
    }

    @Test
    fun lowBandGainLeavesTheMidsAlone() {
        val bass = tone(60.0)
        val mid = tone(1000.0)
        val (noBass, _) = run(StemMix(0f, 1f, 1f, 1f), 32768, bass, bass)
        assertTrue(rms(noBass, 16384) < 0.25 * rms(DoubleArray(8192) { bass(it) }, 0))
        val (mids, _) = run(StemMix(0f, 1f, 1f, 1f), 32768, mid, mid)
        assertEquals(rms(DoubleArray(8192) { mid(it) }, 0), rms(mids, 16384), 0.03)
    }
}
