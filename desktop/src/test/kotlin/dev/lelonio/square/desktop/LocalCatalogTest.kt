package dev.lelonio.square.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class LocalCatalogTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun incrementalScanAndArtworkAndLyrics() = runBlocking {
        val dataDir = tmp.newFolder("data").toPath()
        val root1 = tmp.newFolder("music1").toPath()
        val root2 = tmp.newFolder("music2").toPath()

        val albumDir = root1.resolve("ArtistA").resolve("AlbumA")
        Files.createDirectories(albumDir)
        val flac1 = albumDir.resolve("01 Song One.flac")
        Files.write(flac1, tinyFlac("Song One", "ArtistA", "AlbumA"))
        Files.write(albumDir.resolve("COVER.JPG"), byteArrayOf(1, 2, 3, 4))
        Files.writeString(albumDir.resolve("01 Song One.lrc"), "[00:05.00]Hello from sidecar\n", StandardCharsets.UTF_8)

        val flac2 = root2.resolve("02 Song Two.flac")
        Files.write(flac2, tinyFlac("Song Two", "ArtistB", "AlbumB"))

        val catalog = LocalCatalog(dataDir = dataDir, enableWatcher = false)
        catalog.addFolder(root1)
        catalog.addFolder(root2)

        assertEquals(2, catalog.tracks.value.size)
        val t1 = catalog.tracks.value.first { it.title == "Song One" }
        assertNotNull(t1.folderArtworkPath)
        assertTrue(t1.artworkUrl!!.startsWith("file:"))

        val lyr = catalog.lyrics(t1.id, t1.title, t1.artist, t1.durationMs)
        assertNotNull(lyr)
        assertEquals("Embedded", lyr!!.source)
        assertEquals("Hello from sidecar", lyr.lines.single().text)

        // Restart / new LocalCatalog loads persisted index and skips unchanged files
        val restarted = LocalCatalog(dataDir = dataDir, enableWatcher = false)
        val summary = restarted.scan()
        assertEquals(2, summary.total)
        assertEquals(2, summary.skippedUnchanged)
        assertEquals(0, summary.parsed)

        // Modifying a file re-parses only that file
        Files.write(flac2, tinyFlac("Song Two Updated", "ArtistB", "AlbumB"))
        val summary2 = restarted.scan()
        assertEquals(1, summary2.parsed)
        assertEquals(1, summary2.skippedUnchanged)
        assertTrue(restarted.tracks.value.any { it.title == "Song Two Updated" })

        restarted.close()
        catalog.close()
    }

    @Test
    fun watchServiceDetectsCreateSubdirAndDelete() = runBlocking {
        val dataDir = tmp.newFolder("watch_data").toPath()
        val root = tmp.newFolder("watch_music").toPath()

        val catalog = LocalCatalog(dataDir = dataDir, enableWatcher = true)
        try {
            catalog.addFolder(root)
            assertEquals(0, catalog.tracks.value.size)

            // Create a new subdirectory and write a FLAC file inside it
            val sub = root.resolve("LiveBand").resolve("LiveAlbum")
            Files.createDirectories(sub)
            Thread.sleep(150) // allow directory registration
            val trackPath = sub.resolve("live_track.flac")
            Files.write(trackPath, tinyFlac("Live Track", "LiveBand", "LiveAlbum"))

            val appeared = waitUntil(5000) { catalog.tracks.value.any { it.title == "Live Track" } }
            assertTrue("Expected WatchService to index newly created file", appeared)

            // Delete the file and verify removal
            Files.deleteIfExists(trackPath)
            val removed = waitUntil(5000) { catalog.tracks.value.none { it.title == "Live Track" } }
            assertTrue("Expected WatchService to remove deleted file", removed)
        } finally {
            catalog.close()
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun tinyFlac(title: String, artist: String, album: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        val si = ByteArray(34)
        si[10] = (44100 ushr 12).toByte()
        si[11] = (44100 ushr 4).toByte()
        si[12] = (((44100 and 0x0F) shl 4) or (1 shl 1)).toByte()
        si[13] = (15 shl 4).toByte()
        si[16] = 0x01; si[17] = 0x58 // ~88k samples
        out.write(0); out.write(0); out.write(0); out.write(34); out.write(si)

        val vc = ByteArrayOutputStream()
        val vendor = "Test".toByteArray(StandardCharsets.UTF_8)
        writeLeInt(vc, vendor.size); vc.write(vendor)
        val comments = listOf("TITLE=$title", "ARTIST=$artist", "ALBUM=$album")
        writeLeInt(vc, comments.size)
        for (c in comments) {
            val b = c.toByteArray(StandardCharsets.UTF_8)
            writeLeInt(vc, b.size); vc.write(b)
        }
        val vcBytes = vc.toByteArray()
        out.write(0x84) // last block, VORBIS_COMMENT (4)
        out.write((vcBytes.size ushr 16) and 0xFF)
        out.write((vcBytes.size ushr 8) and 0xFF)
        out.write(vcBytes.size and 0xFF)
        out.write(vcBytes)
        return out.toByteArray()
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
