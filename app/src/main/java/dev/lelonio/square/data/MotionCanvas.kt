package dev.lelonio.square.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A Canvas for songs that are not Spotify's: the album's moving artwork from
 * Apple Music, found by title and artist through the lookup Yuma uses.
 *
 * Apple serves it as HLS, but each rendition is one fragmented MP4 addressed by
 * byte ranges, so the whole file plays as an ordinary looping video and can be
 * kept beside a download like Spotify's clips are. The rendition is the
 * smallest H.264 one at least [MIN_WIDTH] wide: around 8 MB for a 30-second
 * loop, where the master file Apple also links is 80 MB.
 */
object MotionCanvas {
    private const val LOOKUP = "https://artwork.boidu.dev/"
    private const val MIN_WIDTH = 600

    private val http by lazy {
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    // ponytail: per-session memory only; downloads keep theirs through DownloadExtras.
    private val found = ConcurrentHashMap<String, Result<CanvasClip?>>()

    suspend fun find(title: String, artist: String): CanvasClip? {
        if (title.isBlank() || artist.isBlank()) return null
        val key = "${title.lowercase()}\u0000${artist.lowercase()}"
        found[key]?.let { return it.getOrNull() }
        return withContext(Dispatchers.IO) {
            val answer = runCatching { lookUp(title, artist) }
            // A failure (no network, a timeout) is not filed: the next play asks again.
            if (answer.isSuccess) found[key] = answer
            answer.getOrNull()
        }
    }

    private fun lookUp(title: String, artist: String): CanvasClip? {
        val url = LOOKUP.toHttpUrl().newBuilder()
            .addQueryParameter("s", title.substringBefore(" (feat.").substringBefore(" feat.").trim())
            .addQueryParameter("a", artist.split(", ", " & ").first().trim())
            .addQueryParameter("storefront", "us")
            .build()
        val row = get(url.toString())?.let { json.parseToJsonElement(it) as? JsonObject } ?: return null
        val master = listOf("animatedVertical", "animated")
            .firstNotNullOfOrNull { row[it]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
            ?: return null
        return CanvasClip(url = rendition(master) ?: return null, isVideo = true)
    }

    /** The file behind the chosen rendition of an HLS master playlist. */
    private fun rendition(masterUrl: String): String? {
        val lines = get(masterUrl)?.lines() ?: return null
        val variant = lines.withIndex()
            .filter { (_, line) -> line.startsWith("#EXT-X-STREAM-INF:") && "avc1" in line }
            .mapNotNull { (i, line) ->
                val width = Regex("RESOLUTION=(\\d+)x").find(line)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                val bandwidth = Regex("[,:]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: return@mapNotNull null
                val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
                Triple(width, bandwidth, uri)
            }
            .filter { it.first >= MIN_WIDTH }
            .minByOrNull { it.second }
            ?: return null
        val variantUrl = masterUrl.toHttpUrl().resolve(variant.third) ?: return null
        val map = get(variantUrl.toString())?.lines()
            ?.firstOrNull { it.startsWith("#EXT-X-MAP:") }
            ?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: return null
        return variantUrl.resolve(map)?.toString()
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
}
