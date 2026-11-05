package dev.lelonio.square.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioInspectionTest {
    @Test
    fun readsStreamInfoTagsAndBlocks() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO: 4096 blocks, 96 kHz, 2 channels, 24-bit, 960000 samples.
        val info = ByteArray(34)
        info[0] = 0x10; info[2] = 0x10
        val sr = 96_000
        info[10] = (sr shr 12).toByte()
        info[11] = (sr shr 4).toByte()
        info[12] = (((sr and 0xf) shl 4) or ((2 - 1) shl 1) or ((24 - 1) shr 4)).toByte()
        val samples = 960_000L
        info[13] = ((((24 - 1) and 0xf) shl 4) or ((samples shr 32).toInt() and 0xf)).toByte()
        for (i in 0 until 4) info[14 + i] = (samples shr (24 - 8 * i)).toByte()
        info[18] = 0xab.toByte()
        block(out, 0, info, last = false)
        val vendor = "reference libFLAC 1.4.3".toByteArray()
        val tag = "ARTIST=Doja Cat".toByteArray()
        val comment = ByteBuffer.allocate(4 + vendor.size + 4 + 4 + tag.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(vendor.size).put(vendor).putInt(1).putInt(tag.size).put(tag).array()
        block(out, 4, comment, last = false)
        block(out, 1, ByteArray(100), last = true)
        out.write(ByteArray(10)) // the first audio frame

        val flac = AudioInspection.readFlac(out.toByteArray().inputStream())!!
        assertEquals(96_000, flac.sampleRate)
        assertEquals(2, flac.channels)
        assertEquals(24, flac.bitsPerSample)
        assertEquals(960_000L, flac.totalSamples)
        assertEquals(10_000L, flac.durationMs)
        assertEquals(4096, flac.minBlock)
        assertTrue(flac.md5.startsWith("ab"))
        assertEquals("reference libFLAC 1.4.3", flac.vendor)
        assertEquals(listOf("ARTIST" to "Doja Cat"), flac.tags)
        assertEquals(listOf("STREAMINFO" to 34, "VORBIS_COMMENT" to comment.size, "PADDING" to 100), flac.blocks)
        assertEquals((4 + 4 + 34 + 4 + comment.size + 4 + 100).toLong(), flac.audioOffset)
    }

    @Test
    fun notFlacIsNull() {
        assertEquals(null, AudioInspection.readFlac("OggS0000".toByteArray().inputStream()))
    }

    @Test
    fun wallAt16kIsLossyAndAFadeIsNot() {
        val bins = 2048
        val hz = 22_050f / bins
        val mp3 = FloatArray(bins) { if (it * hz < 16_000) -40f - it * hz / 1000 else -140f }
        val verdict = AudioInspection.verdict(mp3, 44_100)
        assertTrue(verdict.toString(), verdict is AudioInspection.Verdict.Lossy && verdict.kbps == "≤128")

        val real = FloatArray(bins) { -40f - it * hz / 400 } // a steady roll-off to the top
        assertTrue(AudioInspection.verdict(real, 44_100) is AudioInspection.Verdict.Lossless)

        val upsampled = FloatArray(bins) { if (it * 48_000f / bins < 22_000) -40f else -140f }
        assertTrue(AudioInspection.verdict(upsampled, 96_000) is AudioInspection.Verdict.Upsampled)
    }

    private fun block(out: ByteArrayOutputStream, type: Int, body: ByteArray, last: Boolean) {
        out.write((if (last) 0x80 else 0) or type)
        out.write(body.size shr 16); out.write(body.size shr 8); out.write(body.size)
        out.write(body)
    }
}
