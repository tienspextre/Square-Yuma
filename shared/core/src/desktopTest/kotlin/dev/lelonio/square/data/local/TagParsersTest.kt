package dev.lelonio.square.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

class TagParsersTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val dummyJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun parsesFlacTagsPictureAndDuration() {
        val flac = tmp.newFile("test.flac")
        flac.writeBytes(buildSyntheticFlac())

        val meta = AudioMetadataParser.parse(flac, includeArtwork = true)
        assertNotNull(meta)
        meta!!
        assertEquals("Midnight City", meta.title)
        assertEquals("M83", meta.artist)
        assertEquals("Hurry Up, We're Dreaming", meta.album)
        assertEquals("M83", meta.albumArtist)
        assertEquals(2, meta.trackNumber)
        assertEquals(1, meta.discNumber)
        assertEquals("2011", meta.year)
        assertEquals("Electronic", meta.genre)
        assertEquals(48000, meta.sampleRate)
        assertEquals(24, meta.bitDepth)
        assertEquals(2, meta.channels)
        assertEquals(10_000L, meta.durationMs)
        assertEquals(-7.5f, meta.replayGainTrackDb!!, 0.01f)
        assertTrue(meta.hasEmbeddedArtwork)
        assertEquals("image/jpeg", meta.artwork?.mimeType)
        assertArrayEquals(dummyJpeg, meta.artwork?.bytes)
    }

    @Test
    fun parsesId3v23WithApicUsltAndXingDuration() {
        val mp3 = tmp.newFile("v23.mp3")
        mp3.writeBytes(buildSyntheticMp3V23())

        val meta = AudioMetadataParser.parse(mp3, includeArtwork = true)
        assertNotNull(meta)
        meta!!
        assertEquals("Blue Monday", meta.title)
        assertEquals("New Order", meta.artist)
        assertEquals("Power, Corruption & Lies", meta.album)
        assertEquals(3, meta.trackNumber)
        assertEquals("1983", meta.year)
        assertEquals("Synthpop", meta.genre)
        assertEquals(44100, meta.sampleRate)
        assertEquals(2, meta.channels)
        assertTrue("expected Xing duration > 0, got ${meta.durationMs}", meta.durationMs > 2000L)
        assertTrue(meta.hasEmbeddedArtwork)
        assertEquals("image/jpeg", meta.artwork?.mimeType)
        assertArrayEquals(dummyJpeg, meta.artwork?.bytes)

        val lyrics = EmbeddedLyricsParser { File(it).inputStream() }.extract(mp3.absolutePath, mp3.name)
        assertEquals("[00:01.00]How does it feel", lyrics)
    }

    @Test
    fun parsesId3v24Utf8AndId3v1Fallback() {
        val mp3 = tmp.newFile("v24_v1.mp3")
        mp3.writeBytes(buildSyntheticMp3V24WithV1Fallback())

        val meta = AudioMetadataParser.parse(mp3, includeArtwork = false)
        assertNotNull(meta)
        meta!!
        assertEquals("Tiếng Việt UTF-8", meta.title)
        assertEquals("Artist v2.4", meta.artist)
        // Album & track come from ID3v1.1 fallback
        assertEquals("Fallback Album", meta.album)
        assertEquals(7, meta.trackNumber)
        assertEquals("2024", meta.year)
        assertEquals("Rock", meta.genre)
        assertEquals(-5.25f, meta.replayGainTrackDb!!, 0.01f)
    }

    @Test
    fun parsesOggVorbisAndOpusWithVorbisCommentsAndPicture() {
        val ogg = tmp.newFile("vorbis.ogg")
        ogg.writeBytes(buildSyntheticOgg(isOpus = false))

        val vorbisMeta = AudioMetadataParser.parse(ogg, includeArtwork = true)
        assertNotNull(vorbisMeta)
        vorbisMeta!!
        assertEquals("Vorbis Song", vorbisMeta.title)
        assertEquals("Ogg Artist", vorbisMeta.artist)
        assertEquals("Ogg Album", vorbisMeta.album)
        assertEquals(44100, vorbisMeta.sampleRate)
        assertEquals(2, vorbisMeta.channels)
        assertEquals(5000L, vorbisMeta.durationMs)
        assertTrue(vorbisMeta.hasEmbeddedArtwork)
        assertArrayEquals(dummyJpeg, vorbisMeta.artwork?.bytes)

        val opus = tmp.newFile("opus.opus")
        opus.writeBytes(buildSyntheticOgg(isOpus = true))

        val opusMeta = AudioMetadataParser.parse(opus, includeArtwork = false)
        assertNotNull(opusMeta)
        opusMeta!!
        assertEquals("Opus Song", opusMeta.title)
        assertEquals("Opus Artist", opusMeta.artist)
        assertEquals(48000, opusMeta.sampleRate)
        assertEquals(2, opusMeta.channels)
        assertEquals(5000L, opusMeta.durationMs)
    }

    @Test
    fun parsesMp4AtomsAndCoverArt() {
        val m4a = tmp.newFile("track.m4a")
        m4a.writeBytes(buildSyntheticM4a())

        val meta = AudioMetadataParser.parse(m4a, includeArtwork = true)
        assertNotNull(meta)
        meta!!
        assertEquals("M4A Title", meta.title)
        assertEquals("M4A Artist", meta.artist)
        assertEquals("M4A Album Artist", meta.albumArtist)
        assertEquals("M4A Album", meta.album)
        assertEquals("2022", meta.year)
        assertEquals("Indie", meta.genre)
        assertEquals(4, meta.trackNumber)
        assertEquals(2, meta.discNumber)
        assertEquals(12_000L, meta.durationMs)
        assertEquals("Line one\nLine two", meta.embeddedLyrics)
        assertTrue(meta.hasEmbeddedArtwork)
        assertEquals("image/jpeg", meta.artwork?.mimeType)
        assertArrayEquals(dummyJpeg, meta.artwork?.bytes)
    }

    // --- Synthetic file builders ---

    private fun buildSyntheticFlac(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))

        // STREAMINFO (48000 Hz, 2 ch, 24-bit, 480000 samples = 10s)
        val si = ByteArray(34)
        val sr = 48000
        val chMinus1 = 1
        val bpsMinus1 = 23
        val totalSamples = 480_000L
        si[10] = ((sr ushr 12) and 0xFF).toByte()
        si[11] = ((sr ushr 4) and 0xFF).toByte()
        si[12] = (((sr and 0x0F) shl 4) or (chMinus1 shl 1) or (bpsMinus1 ushr 4)).toByte()
        si[13] = (((bpsMinus1 and 0x0F) shl 4) or ((totalSamples ushr 32) and 0x0F).toInt()).toByte()
        si[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        si[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        si[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        si[17] = (totalSamples and 0xFF).toByte()
        writeFlacBlock(out, isLast = false, type = 0, payload = si)

        val vc = buildVorbisCommentPayload(
            listOf(
                "TITLE=Midnight City",
                "ARTIST=M83",
                "ALBUM=Hurry Up, We're Dreaming",
                "ALBUMARTIST=M83",
                "TRACKNUMBER=2/10",
                "DISCNUMBER=1/2",
                "DATE=2011-10-18",
                "GENRE=Electronic",
                "REPLAYGAIN_TRACK_GAIN=-7.50 dB",
            ),
        )
        writeFlacBlock(out, isLast = false, type = 4, payload = vc)

        val pic = buildFlacPictureBlock(dummyJpeg, "image/jpeg")
        writeFlacBlock(out, isLast = true, type = 6, payload = pic)
        return out.toByteArray()
    }

    private fun writeFlacBlock(out: ByteArrayOutputStream, isLast: Boolean, type: Int, payload: ByteArray) {
        out.write((if (isLast) 0x80 else 0x00) or (type and 0x7F))
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }

    private fun buildFlacPictureBlock(img: ByteArray, mime: String): ByteArray {
        val b = ByteArrayOutputStream()
        writeBeInt(b, 3) // Front cover
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        writeBeInt(b, mimeBytes.size)
        b.write(mimeBytes)
        writeBeInt(b, 0) // description
        writeBeInt(b, 100)
        writeBeInt(b, 100)
        writeBeInt(b, 24)
        writeBeInt(b, 0)
        writeBeInt(b, img.size)
        b.write(img)
        return b.toByteArray()
    }

    private fun buildSyntheticMp3V23(): ByteArray {
        val frames = ByteArrayOutputStream()
        fun addTextFrame(id: String, value: String) {
            val payload = ByteArrayOutputStream().apply {
                write(0) // ISO-8859-1
                write(value.toByteArray(StandardCharsets.ISO_8859_1))
            }.toByteArray()
            frames.write(id.toByteArray(StandardCharsets.US_ASCII))
            writeBeInt(frames, payload.size)
            frames.write(0); frames.write(0)
            frames.write(payload)
        }
        addTextFrame("TIT2", "Blue Monday")
        addTextFrame("TPE1", "New Order")
        addTextFrame("TALB", "Power, Corruption & Lies")
        addTextFrame("TRCK", "3/8")
        addTextFrame("TYER", "1983")
        addTextFrame("TCON", "Synthpop")

        // USLT frame
        val uslt = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write("eng".toByteArray(StandardCharsets.US_ASCII))
            write(0) // empty descriptor terminator
            write("[00:01.00]How does it feel".toByteArray(StandardCharsets.ISO_8859_1))
        }.toByteArray()
        frames.write("USLT".toByteArray(StandardCharsets.US_ASCII))
        writeBeInt(frames, uslt.size)
        frames.write(0); frames.write(0)
        frames.write(uslt)

        // APIC frame
        val apic = ByteArrayOutputStream().apply {
            write(0)
            write("image/jpeg".toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(3) // front cover
            write(0) // empty description
            write(dummyJpeg)
        }.toByteArray()
        frames.write("APIC".toByteArray(StandardCharsets.US_ASCII))
        writeBeInt(frames, apic.size)
        frames.write(0); frames.write(0)
        frames.write(apic)

        val tagBytes = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
        out.write(3); out.write(0); out.write(0)
        writeSynchsafe(out, tagBytes.size)
        out.write(tagBytes)

        // Minimal MPEG1 Layer III 128 kbps 44100 Hz stereo frame + Xing header with 100 frames (~2.61s)
        val mpegFrame = ByteArray(417)
        mpegFrame[0] = 0xFF.toByte()
        mpegFrame[1] = 0xFB.toByte() // MPEG1, Layer III, no CRC
        mpegFrame[2] = 0x90.toByte() // 128 kbps, 44100 Hz
        mpegFrame[3] = 0x00.toByte() // Stereo
        System.arraycopy("Xing".toByteArray(StandardCharsets.US_ASCII), 0, mpegFrame, 36, 4)
        mpegFrame[43] = 0x01 // flags = frames count present
        mpegFrame[47] = 100  // 100 frames * 1152 / 44100 = 2612 ms
        out.write(mpegFrame)
        return out.toByteArray()
    }

    private fun buildSyntheticMp3V24WithV1Fallback(): ByteArray {
        val frames = ByteArrayOutputStream()
        fun addUtf8Frame(id: String, value: String) {
            val payload = ByteArrayOutputStream().apply {
                write(3) // UTF-8
                write(value.toByteArray(StandardCharsets.UTF_8))
            }.toByteArray()
            frames.write(id.toByteArray(StandardCharsets.US_ASCII))
            writeSynchsafe(frames, payload.size)
            frames.write(0); frames.write(0)
            frames.write(payload)
        }
        addUtf8Frame("TIT2", "Tiếng Việt UTF-8")
        addUtf8Frame("TPE1", "Artist v2.4")
        addUtf8Frame("TDRC", "2024-05-01")
        val txxx = ByteArrayOutputStream().apply {
            write(3)
            write("REPLAYGAIN_TRACK_GAIN".toByteArray(StandardCharsets.UTF_8))
            write(0)
            write("-5.25 dB".toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()
        frames.write("TXXX".toByteArray(StandardCharsets.US_ASCII))
        writeSynchsafe(frames, txxx.size)
        frames.write(0); frames.write(0)
        frames.write(txxx)

        val tagBytes = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
        out.write(4); out.write(0); out.write(0)
        writeSynchsafe(out, tagBytes.size)
        out.write(tagBytes)

        // ID3v1.1 128-byte footer
        val v1 = ByteArray(128)
        System.arraycopy("TAG".toByteArray(StandardCharsets.ISO_8859_1), 0, v1, 0, 3)
        System.arraycopy("Ignored Title".toByteArray(StandardCharsets.ISO_8859_1), 0, v1, 3, 13)
        System.arraycopy("Fallback Album".toByteArray(StandardCharsets.ISO_8859_1), 0, v1, 63, 14)
        System.arraycopy("1999".toByteArray(StandardCharsets.ISO_8859_1), 0, v1, 93, 4)
        v1[125] = 0
        v1[126] = 7 // track 7
        v1[127] = 17 // Rock
        out.write(v1)
        return out.toByteArray()
    }

    private fun buildSyntheticOgg(isOpus: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (!isOpus) {
            val id = ByteArray(30)
            id[0] = 0x01
            System.arraycopy("vorbis".toByteArray(StandardCharsets.US_ASCII), 0, id, 1, 6)
            id[11] = 2
            writeLeIntInto(id, 12, 44100)
            writeOggPage(out, 0L, 0, id)

            val picB64 = Base64.getEncoder().encodeToString(buildFlacPictureBlock(dummyJpeg, "image/jpeg"))
            val commentBody = buildVorbisCommentPayload(
                listOf("TITLE=Vorbis Song", "ARTIST=Ogg Artist", "ALBUM=Ogg Album", "METADATA_BLOCK_PICTURE=$picB64"),
            )
            val pkt1 = ByteArray(7 + commentBody.size)
            pkt1[0] = 0x03
            System.arraycopy("vorbis".toByteArray(StandardCharsets.US_ASCII), 0, pkt1, 1, 6)
            System.arraycopy(commentBody, 0, pkt1, 7, commentBody.size)
            writeOggPage(out, 0L, 1, pkt1)
            writeOggPage(out, 44100L * 5L, 2, byteArrayOf(0x00))
        } else {
            val id = ByteArray(19)
            System.arraycopy("OpusHead".toByteArray(StandardCharsets.US_ASCII), 0, id, 0, 8)
            id[8] = 1
            id[9] = 2
            id[10] = 0x38; id[11] = 0x01 // preSkip = 312
            writeLeIntInto(id, 12, 48000)
            writeOggPage(out, 0L, 0, id)

            val commentBody = buildVorbisCommentPayload(listOf("TITLE=Opus Song", "ARTIST=Opus Artist"))
            val pkt1 = "OpusTags".toByteArray(StandardCharsets.US_ASCII) + commentBody
            writeOggPage(out, 0L, 1, pkt1)
            writeOggPage(out, 48000L * 5L + 312L, 2, byteArrayOf(0x00))
        }
        return out.toByteArray()
    }

    private fun writeOggPage(out: ByteArrayOutputStream, granule: Long, seq: Int, payload: ByteArray) {
        out.write("OggS".toByteArray(StandardCharsets.US_ASCII))
        out.write(0) // version
        out.write(0) // flags
        for (i in 0 until 8) out.write(((granule ushr (i * 8)) and 0xFF).toInt())
        writeLeInt(out, 1) // serial
        writeLeInt(out, seq)
        writeLeInt(out, 0) // crc
        val segs = mutableListOf<Int>()
        var rem = payload.size
        while (rem >= 255) {
            segs += 255
            rem -= 255
        }
        segs += rem
        out.write(segs.size)
        segs.forEach { out.write(it) }
        out.write(payload)
    }

    private fun buildSyntheticM4a(): ByteArray {
        fun atom(type: ByteArray, payload: ByteArray): ByteArray {
            val b = ByteArrayOutputStream()
            writeBeInt(b, 8 + payload.size)
            b.write(type)
            b.write(payload)
            return b.toByteArray()
        }
        fun atomStr(type: String, payload: ByteArray) = atom(type.toByteArray(StandardCharsets.ISO_8859_1), payload)
        fun dataAtom(dataType: Int, payload: ByteArray): ByteArray {
            val b = ByteArrayOutputStream()
            writeBeInt(b, dataType)
            writeBeInt(b, 0) // locale
            b.write(payload)
            return atomStr("data", b.toByteArray())
        }
        fun textIlst(type: ByteArray, text: String) =
            atom(type, dataAtom(1, text.toByteArray(StandardCharsets.UTF_8)))

        val ftyp = atomStr("ftyp", "M4A ".toByteArray(StandardCharsets.US_ASCII) + ByteArray(8))

        val mvhdPayload = ByteArray(100)
        writeBeIntInto(mvhdPayload, 12, 1000) // timescale = 1000
        writeBeIntInto(mvhdPayload, 16, 12000) // duration = 12000 ms
        val mvhd = atomStr("mvhd", mvhdPayload)

        val trkn = ByteArray(8).apply { this[3] = 4; this[5] = 10 }
        val disk = ByteArray(8).apply { this[3] = 2; this[5] = 2 }
        val ilstBody = ByteArrayOutputStream().apply {
            write(textIlst(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), "M4A Title"))
            write(textIlst(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), "M4A Artist"))
            write(textIlst("aART".toByteArray(StandardCharsets.ISO_8859_1), "M4A Album Artist"))
            write(textIlst(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), "M4A Album"))
            write(textIlst(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), "2022"))
            write(textIlst(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte()), "Indie"))
            write(textIlst(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), "Line one\nLine two"))
            write(atomStr("trkn", dataAtom(0, trkn)))
            write(atomStr("disk", dataAtom(0, disk)))
            write(atomStr("covr", dataAtom(13, dummyJpeg)))
        }.toByteArray()
        val ilst = atomStr("ilst", ilstBody)
        val meta = atomStr("meta", ByteArray(4) + ilst)
        val udta = atomStr("udta", meta)
        val moov = atomStr("moov", mvhd + udta)
        return ftyp + moov
    }

    private fun buildVorbisCommentPayload(comments: List<String>): ByteArray {
        val b = ByteArrayOutputStream()
        val vendor = "SquareTest".toByteArray(StandardCharsets.UTF_8)
        writeLeInt(b, vendor.size)
        b.write(vendor)
        writeLeInt(b, comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(StandardCharsets.UTF_8)
            writeLeInt(b, bytes.size)
            b.write(bytes)
        }
        return b.toByteArray()
    }

    private fun writeBeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeBeIntInto(arr: ByteArray, off: Int, v: Int) {
        arr[off] = ((v ushr 24) and 0xFF).toByte()
        arr[off + 1] = ((v ushr 16) and 0xFF).toByte()
        arr[off + 2] = ((v ushr 8) and 0xFF).toByte()
        arr[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeSynchsafe(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F)
        out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F)
        out.write(v and 0x7F)
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeLeIntInto(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v ushr 8) and 0xFF).toByte()
        arr[off + 2] = ((v ushr 16) and 0xFF).toByte()
        arr[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
