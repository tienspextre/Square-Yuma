package dev.lelonio.square.desktop

import com.sun.net.httpserver.HttpServer
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DownloadStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val payloadById = mutableMapOf<String, ByteArray>()
    private val requestCounts = mutableMapOf<String, AtomicInteger>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/download.view") { ex ->
            val id = ex.requestURI.rawQuery.orEmpty().substringAfter("id=").substringBefore('&')
            requestCounts.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
            val data = payloadById[id]
            if (data == null) {
                ex.sendResponseHeaders(404, -1)
                return@createContext
            }
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                val slice = data.copyOfRange(start, data.size)
                ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun deduplicatesConcurrentAndRepeatedDownloadsByTrackId() = runBlocking {
        val dataDir = tmp.newFolder("dl_idx").toPath()
        val rootDir = tmp.newFolder("dl_root").toPath()
        val store = DownloadStore(indexPath = dataDir.resolve("downloads.json"), rootPath = rootDir)

        val bytes = ByteArray(16_384) { (it % 251).toByte() }
        payloadById["track-42"] = bytes

        val songFromAlbum = SubsonicChildSong(id = "track-42", title = "Same Song", artist = "Artist", album = "Album", suffix = "flac")
        val songFromPlaylist = SubsonicChildSong(id = "track-42", title = "Same Song", artist = "Artist", album = "Album", suffix = "flac")
        val url = "http://127.0.0.1:${server.address.port}/rest/download.view?id=track-42"

        val results = listOf(
            async { store.download(songFromAlbum, url) },
            async { store.download(songFromPlaylist, url) },
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, store.kept.size)
        assertEquals(1, requestCounts["track-42"]?.get())
        assertArrayEquals(bytes, Files.readAllBytes(store.fileFor("track-42")!!.toPath()))
    }

    @Test
    fun resumesPartialDownloadAndEnforcesLruSizeLimitAndOfflineList() = runBlocking {
        val dataDir = tmp.newFolder("dl2_idx").toPath()
        val rootDir = tmp.newFolder("dl2_root").toPath()
        val store = DownloadStore(indexPath = dataDir.resolve("downloads.json"), rootPath = rootDir)

        val fullA = ByteArray(10_000) { (it % 127).toByte() }
        val fullB = ByteArray(10_000) { ((it + 33) % 127).toByte() }
        payloadById["song-a"] = fullA
        payloadById["song-b"] = fullB

        val songA = SubsonicChildSong(id = "song-a", title = "Alpha", artist = "Art", album = "Alb", suffix = "flac")
        val songB = SubsonicChildSong(id = "song-b", title = "Beta", artist = "Art", album = "Alb", suffix = "flac")

        // Pre-seed a 4000-byte .part file for song-a to verify HTTP Range resume
        val partPath = rootDir.resolve("Art").resolve("Alb").resolve("Alpha [song-a].flac.part")
        Files.createDirectories(partPath.parent)
        Files.write(partPath, fullA.copyOfRange(0, 4000))

        val base = "http://127.0.0.1:${server.address.port}/rest/download.view"
        val resA = store.download(songA, "$base?id=song-a")
        assertTrue(resA.isSuccess)
        assertArrayEquals(fullA, Files.readAllBytes(resA.getOrThrow().toPath()))

        // Set cache limit to 12_000 bytes -> downloading song-b (10_000 B) evicts older song-a
        store.setCacheLimitBytes(12_000L)
        Thread.sleep(5)
        val resB = store.download(songB, "$base?id=song-b")
        assertTrue(resB.isSuccess)

        assertFalse("song-a should be evicted by LRU cache limit", store.has("song-a"))
        assertTrue(store.has("song-b"))
        assertEquals(listOf("song-b"), store.cachedSongs().map { it.id })
        assertEquals(listOf("subsonic:track:song-b"), store.allCatalogTracks().map { it.uri })
    }
}
