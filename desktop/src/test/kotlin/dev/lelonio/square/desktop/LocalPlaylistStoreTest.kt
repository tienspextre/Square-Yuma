package dev.lelonio.square.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LocalPlaylistStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun m3u8RoundTripWithRelativePathsUnicodeAndCrud() {
        val base = tmp.newFolder("user_data").toPath()
        val playlistsDir = base.resolve("playlists")
        val musicDir = base.resolve("music").resolve("Nghệ Sĩ Việt")
        Files.createDirectories(musicDir)

        val song1 = musicDir.resolve("01 Mưa Tháng Sáu.flac")
        val song2 = musicDir.resolve("02 日本語トラック.mp3")
        val song3 = musicDir.resolve("03 Café Track.opus")
        Files.write(song1, byteArrayOf(1))
        Files.write(song2, byteArrayOf(2))
        Files.write(song3, byteArrayOf(3))

        val store = LocalPlaylistStore(playlistsDir)
        val created = store.create("Nhạc Yêu Thích ❤️")
        store.addTracks(
            created.id,
            listOf(
                LocalPlaylistEntry(song1.toString(), "Mưa Tháng Sáu", "Nghệ Sĩ Việt", 215),
                LocalPlaylistEntry(song2.toString(), "日本語トラック", "Artist B", 180),
                LocalPlaylistEntry(song3.toString(), "Café Track", "Artist C", 200),
            ),
        )

        // Verify UTF-8 + relative paths on disk (never absolute paths on same root)
        val rawText = Files.readString(created.file, StandardCharsets.UTF_8)
        assertTrue(rawText.startsWith("#EXTM3U"))
        assertTrue(rawText.contains("#PLAYLIST:Nhạc Yêu Thích ❤️"))
        assertTrue(rawText.contains(".."))
        assertFalse(rawText.contains(base.toAbsolutePath().toString()))

        // Reorder: move index 2 -> 0
        store.reorder(created.id, 2, 0)
        val reordered = store.read(created.id)!!
        assertEquals(
            listOf(song3.toString(), song1.toString(), song2.toString()),
            reordered.entries.map { it.path },
        )

        // Remove middle item
        store.removeAt(created.id, 1)
        val afterRemove = store.read(created.id)!!
        assertEquals(listOf(song3.toString(), song2.toString()), afterRemove.entries.map { it.path })

        // Rename & re-read from a fresh store instance
        val renamed = store.rename(created.id, "Đêm Khuya")
        val reloaded = LocalPlaylistStore(playlistsDir).read(renamed.id)!!
        assertEquals("Đêm Khuya", reloaded.name)
        assertEquals(listOf(song3.toString(), song2.toString()), reloaded.entries.map { it.path })

        // Delete
        assertTrue(store.delete(renamed.id))
        assertTrue(store.list().isEmpty())
    }
}
