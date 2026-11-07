package dev.lelonio.square.data

import dev.lelonio.square.SquareApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Spotify's Canvas for a song that is not Spotify's: the song is found on
 * Spotify by title and artist, and its Canvas asked for.
 *
 * With the account's OAuth token alone, straight over HTTP: no engine, no
 * player, nothing that needs Premium, so a free account is enough. Null when
 * nobody is signed in, the song is not found or it has no Canvas; the caller
 * falls back to [MotionCanvas].
 */
object SpotifyCanvas {
    private const val SEARCH = "https://api.spotify.com/v1/search"
    private const val CANVAS = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    // ponytail: per-process memory only, like MotionCanvas.
    private val found = ConcurrentHashMap<String, Result<CanvasClip?>>()

    suspend fun find(app: SquareApplication, title: String, artist: String): CanvasClip? {
        if (title.isBlank() || !app.tokenStore.isLoggedIn) return null
        val key = "${title.lowercase()}\u0000${artist.lowercase()}"
        found[key]?.let { return it.getOrNull() }
        val result = runCatching {
            val uri = trackUri(app, key, title, artist) ?: return@runCatching null
            val token = app.tokenStore.validAccessToken()
            withContext(Dispatchers.IO) { canvas(token, uri) }
        }.onFailure { android.util.Log.i(TAG, "spotify canvas for $title: ${it.message}") }
        // A failure is not remembered: a lapsed token or a dropped connection
        // is worth another try on the next song.
        if (result.isSuccess) found[key] = result
        android.util.Log.i(TAG, "spotify canvas for $title: ${result.getOrNull()?.url ?: "none"}")
        return result.getOrNull()
    }

    fun forget() = found.clear()

    // ------------------------------------------------- the listener's own app

    /*
     * Finding a song is a Web API search, and the client id the app signs in with is Spotify's
     * own, shared by every librespot-based app: its search quota is spent, and every search
     * answers 429. The Canvas request itself is not limited. So the search goes through an app
     * the listener registers on developer.spotify.com, by client credentials (no sign-in, no
     * redirect), and only the Canvas uses the signed-in account.
     */

    private fun ownPrefs(app: android.content.Context) = app.getSharedPreferences("spotify_canvas_app", 0)

    // ponytail: the secret sits in the app's private prefs, unencrypted, like the NAS password.
    fun ownApp(app: android.content.Context): Pair<String, String>? {
        val p = ownPrefs(app)
        val id = p.getString("id", "").orEmpty()
        val secret = p.getString("secret", "").orEmpty()
        return (id to secret).takeIf { id.isNotBlank() && secret.isNotBlank() }
    }

    fun setOwnApp(app: android.content.Context, id: String, secret: String) {
        ownPrefs(app).edit().putString("id", id.trim()).putString("secret", secret.trim()).apply()
        appToken = null
    }

    /** Whether Spotify accepts the pair; said in Settings right after saving. */
    suspend fun checkOwnApp(id: String, secret: String): Boolean =
        runCatching { withContext(Dispatchers.IO) { requestAppToken(id.trim(), secret.trim()) } }.isSuccess

    @Volatile private var appToken: Pair<String, Long>? = null

    private fun ownToken(app: android.content.Context): String? {
        val (id, secret) = ownApp(app) ?: return null
        appToken?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.let { return it.first }
        return requestAppToken(id, secret).also { appToken = it }.first
    }

    private fun requestAppToken(id: String, secret: String): Pair<String, Long> {
        val body = okhttp3.FormBody.Builder().add("grant_type", "client_credentials").build()
        val answer = call(
            Request.Builder().url("https://accounts.spotify.com/api/token")
                .header("Authorization", okhttp3.Credentials.basic(id, secret))
                .post(body).build(),
        )
        val json = JSONObject(String(answer))
        return json.getString("access_token") to System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
    }

    /**
     * The song's track on Spotify: searched for once, then remembered on disk, so a song costs
     * one search ever. A failed search (429, no connection) is not remembered.
     */
    private suspend fun trackUri(app: SquareApplication, key: String, title: String, artist: String): String? {
        val saved = app.getSharedPreferences("spotify_canvas_tracks", 0)
        if (saved.contains(key)) return saved.getString(key, null)?.ifEmpty { null }
        val uri = withContext(Dispatchers.IO) {
            val token = ownToken(app) ?: app.tokenStore.validAccessToken()
            match(token, title, artist)
        }
        saved.edit().putString(key, uri.orEmpty()).apply()
        return uri
    }

    /** The first track by the artist asked for, else the first track. */
    private fun match(token: String, title: String, artist: String): String? {
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist".trim())
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", "5")
            .build()
        val body = call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        val items = JSONObject(String(body)).getJSONObject("tracks").getJSONArray("items")
        val tracks = (0 until items.length()).map { items.getJSONObject(it) }
        val wanted = artist.lowercase().split(',', '&', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val artistsOf = { t: JSONObject ->
            val a = t.getJSONArray("artists")
            (0 until a.length()).joinToString(",") { a.getJSONObject(it).optString("name") }.lowercase()
        }
        return (tracks.firstOrNull { t -> wanted.any { artistsOf(t).contains(it) } } ?: tracks.firstOrNull())
            ?.optString("uri")?.takeIf { it.isNotEmpty() }
    }

    private fun canvas(token: String, trackUri: String): CanvasClip? {
        // EntityCanvazRequest { repeated Entity entities = 1 }, Entity { string entity_uri = 1 }
        val request = field(1, field(1, trackUri.toByteArray()))
        val body = call(
            Request.Builder().url(CANVAS)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/protobuf")
                .post(request.toRequestBody("application/x-protobuf".toMediaType()))
                .build(),
        )
        return parse(body)
    }

    private fun call(request: Request): ByteArray = http.newCall(request).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code} from ${request.url.host}" }
        it.body!!.bytes()
    }

    /** The first Canvaz { string url = 2; Type type = 4 } in EntityCanvazResponse { repeated Canvaz = 1 }. */
    internal fun parse(bytes: ByteArray): CanvasClip? {
        val canvas = fields(bytes).firstOrNull { it.first == 1 }?.second as? ByteArray ?: return null
        var url: String? = null
        var kind = 0L
        for ((number, value) in fields(canvas)) {
            if (number == 2 && value is ByteArray) url = String(value)
            if (number == 4 && value is Long) kind = value
        }
        // 0 is IMAGE; everything above it is some kind of video.
        return url?.takeIf { it.isNotBlank() }?.let { CanvasClip(it, isVideo = kind != 0L) }
    }

    private fun field(number: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        varint((number shl 3 or 2).toLong()); varint(value.size.toLong()); write(value)
    }.toByteArray()

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v >= 0x80) { write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        write(v.toInt())
    }

    /** Top-level fields of a message: varints as Long, length-delimited as ByteArray. */
    private fun fields(bytes: ByteArray): List<Pair<Int, Any>> {
        val out = mutableListOf<Pair<Int, Any>>()
        var at = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = bytes[at++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (at < bytes.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += number to varint()
                1 -> at += 8
                2 -> { val n = varint().toInt(); out += number to bytes.copyOfRange(at, at + n); at += n }
                5 -> at += 4
                else -> return out
            }
        }
        return out
    }

    private const val TAG = "SquareCanvas"
}
