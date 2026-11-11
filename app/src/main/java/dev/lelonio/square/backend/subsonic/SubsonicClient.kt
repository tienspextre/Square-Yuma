package dev.lelonio.square.backend.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class SubsonicClient(
    private val httpClient: OkHttpClient,
    val preferences: SubsonicPreferences,
    /** True on mobile data. The transcode switch is for 4G only; on Wi-Fi the original file streams. */
    private val isMetered: () -> Boolean = { false },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun buildUrl(endpoint: String, extraParams: Map<String, String> = emptyMap()): HttpUrl? {
        val rawBase = preferences.serverUrl.value.trim().removeSuffix("/")
        if (rawBase.isBlank()) return null
        val fullUrl = if (rawBase.endsWith("/rest")) "$rawBase/$endpoint" else "$rawBase/rest/$endpoint"
        val builder = fullUrl.toHttpUrlOrNull()?.newBuilder() ?: return null

        preferences.buildAuthParams().forEach { (k, v) ->
            builder.addQueryParameter(k, v)
        }
        extraParams.forEach { (k, v) ->
            builder.addQueryParameter(k, v)
        }
        return builder.build()
    }

    suspend fun ping(): Result<SubsonicServerInfo> = withContext(Dispatchers.IO) {
        val url = buildUrl("ping.view") ?: return@withContext Result.failure(IOException("Invalid Subsonic URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body).optJSONObject("subsonic-response")
                    ?: return@withContext Result.failure(IOException("Missing subsonic-response"))
                val status = root.optString("status", "failed")
                if (status != "ok") {
                    val err = root.optJSONObject("error")?.optString("message") ?: "Subsonic error"
                    return@withContext Result.failure(IOException(err))
                }
                val type = root.optString("type", "navidrome")
                val serverVersion = root.optString("serverVersion", root.optString("version", "1.16.1"))
                val openSubsonic = root.optBoolean("openSubsonic", true)
                Result.success(SubsonicServerInfo(status, type, serverVersion, openSubsonic))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylists(): Result<List<SubsonicPlaylistEntry>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getPlaylists.view") ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicPlaylistsContainer.serializer()), body)
                val entries = parsed.`subsonic-response`.playlists?.playlist ?: emptyList()
                Result.success(entries)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylist(id: String): Result<List<SubsonicChildSong>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getPlaylist.view", mapOf("id" to id))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicPlaylistDetail.serializer()), body)
                val songs = parsed.`subsonic-response`.playlist?.entry ?: emptyList()
                Result.success(songs)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGenres(): Result<List<SubsonicGenre>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getGenres.view")
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicGenresContainer.serializer()), body)
                Result.success(parsed.`subsonic-response`.genres?.genre ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSongsByGenre(genre: String, count: Int = 500): Result<List<SubsonicChildSong>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getSongsByGenre.view", mapOf("genre" to genre, "count" to count.toString()))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicRandomSongsContainer.serializer()), body)
                Result.success(parsed.`subsonic-response`.songsByGenre?.song ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRandomSongs(size: Int = 30): Result<List<SubsonicChildSong>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getRandomSongs.view", mapOf("size" to size.toString()))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicRandomSongsContainer.serializer()), body)
                val songs = parsed.`subsonic-response`.randomSongs?.song ?: emptyList()
                Result.success(songs)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbumList(type: String, size: Int = 30, offset: Int = 0, genre: String? = null): Result<List<SubsonicAlbumEntry>> = withContext(Dispatchers.IO) {
        val params = mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()) +
            (genre?.let { mapOf("genre" to it) } ?: emptyMap())
        val url = buildUrl("getAlbumList2.view", params)
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicAlbumList2Container.serializer()), body)
                val albums = parsed.`subsonic-response`.albumList2?.album ?: emptyList()
                Result.success(albums)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbum(id: String): Result<List<SubsonicChildSong>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getAlbum.view", mapOf("id" to id))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicAlbumDetail.serializer()), body)
                val songs = parsed.`subsonic-response`.album?.song ?: emptyList()
                Result.success(songs)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtists(): Result<List<SubsonicArtistItem>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getArtists.view") ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicArtistsContainer.serializer()), body)
                val artists = parsed.`subsonic-response`.artists?.index?.flatMap { it.artist } ?: emptyList()
                Result.success(artists)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtist(id: String): Result<SubsonicArtistDetail> = withContext(Dispatchers.IO) {
        val url = buildUrl("getArtist.view", mapOf("id" to id))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicArtistDetail.serializer()), body)
                val artist = parsed.`subsonic-response`.artist
                    ?: return@withContext Result.failure(IOException("Artist not found"))
                Result.success(artist)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** An empty [query] lists the whole library on Navidrome, paged with [songOffset]. */
    suspend fun search(
        query: String,
        songCount: Int = 50,
        songOffset: Int = 0,
        albumCount: Int = 20,
        artistCount: Int = 20,
    ): Result<SubsonicSearchResult3> = withContext(Dispatchers.IO) {
        val url = buildUrl(
            "search3.view",
            mapOf(
                "query" to query,
                "songCount" to "$songCount",
                "songOffset" to "$songOffset",
                "albumCount" to "$albumCount",
                "artistCount" to "$artistCount",
            ),
        ) ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicSearchResult3.serializer()), body)
                val results = parsed.`subsonic-response`.searchResult3 ?: SubsonicSearchResult3()
                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLyrics(songId: String): Result<List<SubsonicLyricsLine>> = withContext(Dispatchers.IO) {
        val url = buildUrl("getLyricsBySongId.view", mapOf("id" to songId))
            ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.success(emptyList())
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SubsonicResponseWrapper.serializer(SubsonicLyricsListContainer.serializer()), body)
                val lyrics = parsed.`subsonic-response`.lyricsList?.structuredLyrics?.firstOrNull()?.line ?: emptyList()
                Result.success(lyrics)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startScan(): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = buildUrl("startScan.view") ?: return@withContext Result.failure(IOException("Invalid URL"))
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Takes a song out of a playlist's .m3u8, or deletes its file from the NAS
     * (and so from every playlist), through lossless-bridge beside Navidrome.
     * Subsonic has no call for either, and Navidrome re-imports playlist files,
     * so an API-only edit would come back on the next scan.
     */
    suspend fun bridgeRemove(songId: String, playlistId: String?, deleteFile: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            // ponytail: the bridge is assumed on Navidrome's host at :8092; add a setting if it ever moves.
            val base = preferences.serverUrl.value.trim().toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("Invalid URL"))
            val url = base.newBuilder().scheme("http").port(BRIDGE_PORT)
                .encodedPath("/api/navidrome/remove").query(null).build()
            val auth = preferences.buildAuthParams()
            val body = JSONObject()
                .put("u", auth["u"]).put("t", auth["t"]).put("s", auth["s"])
                .put("song_id", songId)
                .put("playlist_id", playlistId ?: JSONObject.NULL)
                .put("delete_file", deleteFile)
                .toString()
                .toRequestBody("application/json".toMediaType())
            runCatching {
                httpClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        val detail = runCatching { JSONObject(r.body?.string().orEmpty()).optString("detail") }.getOrNull()
                        throw IOException(detail?.ifBlank { null } ?: "Bridge HTTP ${r.code}")
                    }
                }
            }
        }

    fun buildStreamUrl(songId: String): String {
        val params = mutableMapOf("id" to songId)
        if (preferences.transcodeCellular.value && isMetered()) {
            params["maxBitRate"] = preferences.transcodeBitrate.value.toString()
            params["format"] = "opus"
        } else {
            params["format"] = "raw"
        }
        return buildUrl("stream.view", params)?.toString() ?: ""
    }

    fun buildDownloadUrl(songId: String): String =
        buildUrl("download.view", mapOf("id" to songId))?.toString() ?: ""

    fun buildCoverArtUrl(coverId: String?, size: Int = 600): String? {
        if (coverId.isNullOrBlank()) return null
        return buildUrl("getCoverArt.view", mapOf("id" to coverId, "size" to size.toString()))?.toString()
    }

    private companion object {
        const val BRIDGE_PORT = 8092
    }
}
