package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.Lyrics
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Word-timed lyrics from Better Lyrics, the YouTube Music extension's service.
 *
 * One request, by title, artist and length, answered with an Apple-style TTML
 * document timed to the word, so the parsing, the word timings and any
 * translation come from [Ttml] as they do for the archive in [Lossless]. A
 * different archive from that one: where one misses a song the other often
 * has it, which is why both are asked.
 *
 * A miss is ordinary and answers null; so does anything that is not a
 * document this can read.
 */
object BetterLyrics {

    private val http = OkHttpClient()

    private val ENDPOINTS = listOf(
        "getLyrics",
        "kugou/getLyrics",
        "qq/getLyrics",
    )

    suspend fun lyrics(title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleaned = title.songTitle()
            if (cleaned.isBlank()) return@withContext null
            val primary = artist.primaryArtist()

            for (endpoint in ENDPOINTS) {
                val url = buildString {
                    append(BASE_URL).append(endpoint).append("?s=").append(encode(cleaned))
                    append("&a=").append(encode(primary))
                    if (durationMs > 0) append("&d=").append(durationMs / 1000)
                }
                val body = get(url) ?: continue
                val ttml = runCatching { JSONObject(body).optString("ttml") }.getOrNull()
                    ?.takeIf { it.startsWith("<") } ?: continue
                val parsed = Ttml.parse(ttml)
                if (parsed != null && parsed.lines.isNotEmpty()) {
                    android.util.Log.i(
                        TAG,
                        "[$endpoint] $cleaned: ${parsed.lines.size} lines, " +
                            "${parsed.lines.count { it.words.isNotEmpty() }} timed by word",
                    )
                    return@withContext parsed
                }
            }
            null
        }

    /** Null on anything but a 200, which includes the 404 for "no match". */
    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private const val TAG = "BetterLyrics"

    private const val BASE_URL = "https://lyrics-api.boidu.dev/"

    private const val USER_AGENT = "Square (Android music player)"
}
