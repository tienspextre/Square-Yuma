package dev.lelonio.square.backend.subsonic

import com.sun.net.httpserver.HttpServer
import dev.lelonio.square.common.KeyValueStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class SubsonicPlaylistSyncTest {

    private class MemoryKv : KeyValueStore {
        private val map = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
        override fun getInt(key: String, default: Int): Int = (map[key] as? Int) ?: default
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private data class FakePlaylist(
        val id: String,
        var name: String,
        var comment: String = "",
        var public: Boolean = false,
        val songs: MutableList<String> = mutableListOf(),
    )

    private lateinit var server: HttpServer
    private lateinit var catalog: SubsonicCatalog
    private val playlists = linkedMapOf<String, FakePlaylist>()
    private val calls = mutableListOf<String>()
    private var nextId = 1

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/") { ex ->
            val path = ex.requestURI.path.substringAfterLast('/')
            val params = parseQuery(ex.requestURI.rawQuery.orEmpty())
            synchronized(this) { calls += "$path?${ex.requestURI.rawQuery.orEmpty()}" }

            val jsonBody = when (path) {
                "createPlaylist.view" -> {
                    val name = params["name"]?.firstOrNull().orEmpty()
                    val id = "pl-${nextId++}"
                    val songs = params["songId"].orEmpty().toMutableList()
                    playlists[id] = FakePlaylist(id, name, songs = songs)
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{"id":"$id","name":"$name","songCount":${songs.size}}}}"""
                }
                "updatePlaylist.view" -> {
                    val id = params["playlistId"]?.firstOrNull().orEmpty()
                    val pl = playlists[id]
                    if (pl == null) {
                        """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"""
                    } else {
                        params["name"]?.firstOrNull()?.let { pl.name = it }
                        params["comment"]?.firstOrNull()?.let { pl.comment = it }
                        params["public"]?.firstOrNull()?.toBooleanStrictOrNull()?.let { pl.public = it }
                        params["songIndexToRemove"].orEmpty().mapNotNull { it.toIntOrNull() }
                            .sortedDescending()
                            .forEach { idx -> if (idx in pl.songs.indices) pl.songs.removeAt(idx) }
                        params["songIdToAdd"].orEmpty().forEach { pl.songs.add(it) }
                        """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
                    }
                }
                "deletePlaylist.view" -> {
                    val id = params["id"]?.firstOrNull().orEmpty()
                    playlists.remove(id)
                    """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
                }
                "getPlaylists.view" -> {
                    val items = playlists.values.joinToString(",") { pl ->
                        """{"id":"${pl.id}","name":"${pl.name}","owner":"tester","public":${pl.public},"songCount":${pl.songs.size}}"""
                    }
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlists":{"playlist":[$items]}}}"""
                }
                "getPlaylist.view" -> {
                    val id = params["id"]?.firstOrNull().orEmpty()
                    val pl = playlists[id]
                    val entries = pl?.songs.orEmpty().joinToString(",") { sid ->
                        """{"id":"$sid","title":"Song $sid","artist":"Artist","album":"Album","duration":180}"""
                    }
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{"id":"$id","name":"${pl?.name.orEmpty()}","entry":[$entries]}}}"""
                }
                else -> """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
            }

            val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        val prefs = SubsonicPreferences(MemoryKv(), "TestClient").apply {
            save("http://127.0.0.1:${server.address.port}", "tester", "secret")
        }
        val client = SubsonicClient(OkHttpClient(), prefs)
        catalog = SubsonicCatalog(client)
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun playlistCreateRenameAddRemoveDeleteAgainstFakeHttpServer() = runBlocking {
        val created = catalog.createPlaylist("Road Trip", description = "Lossless", public = true)
        assertEquals("subsonic:playlist:pl-1", created.uri)
        assertEquals("Road Trip", created.name)

        catalog.addAllToPlaylist(created.uri, listOf("subsonic:track:s1", "subsonic:track:s2", "subsonic:track:s3"))
        assertEquals(listOf("subsonic:track:s1", "subsonic:track:s2", "subsonic:track:s3"), catalog.tracksOf(created.uri).map { it.uri })

        catalog.removeFromPlaylist(created.uri, "subsonic:track:s2")
        assertEquals(listOf("subsonic:track:s1", "subsonic:track:s3"), catalog.tracksOf(created.uri).map { it.uri })
        assertTrue(calls.any { it.startsWith("updatePlaylist.view?") && it.contains("songIndexToRemove=1") })

        catalog.renamePlaylist(created.uri, "Night Drive")
        val listed = catalog.playlists()
        assertEquals(1, listed.size)
        assertEquals("Night Drive", listed.single().name)

        catalog.deletePlaylist(created.uri)
        assertTrue(catalog.playlists().isEmpty())
    }

    private fun parseQuery(query: String): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        if (query.isBlank()) return map
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8")
            val v = URLDecoder.decode(if (eq >= 0) pair.substring(eq + 1) else "", "UTF-8")
            map.getOrPut(k) { mutableListOf() }.add(v)
        }
        return map
    }
}
