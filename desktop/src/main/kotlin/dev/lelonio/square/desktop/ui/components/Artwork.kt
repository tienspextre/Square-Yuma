package dev.lelonio.square.desktop.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.lelonio.square.data.local.AudioMetadataParser
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val bitmaps = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
}
private val colours = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 500
}

fun clearArtworkCaches() {
    synchronized(bitmaps) { bitmaps.clear() }
    synchronized(colours) { colours.clear() }
}

fun artworkCacheSize(): Int = synchronized(bitmaps) { bitmaps.size }

fun seedArtworkCacheForTest(key: String, bitmap: ImageBitmap) {
    synchronized(bitmaps) { bitmaps[key] = bitmap }
}
private val artCacheDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "art_cache")

/** Subsonic hands out a fresh salt per URL, so the cache keys on the cover id and size, not the URL. */
private fun artKey(url: String): String =
    if (url.contains("getCoverArt.view")) {
        url.substringAfter("id=").substringBefore('&') + "@" + url.substringAfter("size=").substringBefore('&')
    } else {
        url
    }

suspend fun loadArtworkBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        when {
            url.startsWith(LocalCatalog.LOCAL_ART_PREFIX) -> {
                val trackPath = url.removePrefix(LocalCatalog.LOCAL_ART_PREFIX)
                val sha = MessageDigest.getInstance("SHA-1")
                    .digest(trackPath.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val cached = artCacheDir.resolve("$sha.img")
                if (Files.isRegularFile(cached)) {
                    return@runCatching Files.readAllBytes(cached)
                }
                val embedded = AudioMetadataParser.extractArtwork(File(trackPath)) ?: return@runCatching null
                runCatching {
                    Files.createDirectories(artCacheDir)
                    Files.write(cached, embedded.bytes)
                }
                embedded.bytes
            }
            url.startsWith("file:") -> {
                val p = Paths.get(URI(url))
                if (Files.isRegularFile(p)) Files.readAllBytes(p) else null
            }
            else -> {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body.bytes() else null
                }
            }
        }
    }.getOrNull()
}

suspend fun loadArtwork(url: String): ImageBitmap? {
    val k = artKey(url)
    synchronized(bitmaps) { bitmaps[k] }?.let { return it }
    val bytes = loadArtworkBytes(url) ?: return null
    return withContext(Dispatchers.IO) {
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }?.also { synchronized(bitmaps) { bitmaps[k] = it } }
}

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    val cached = remember(url) { url?.let { u -> synchronized(bitmaps) { bitmaps[artKey(u)] } } }
    val image by produceState(cached, url) { value = url?.let { loadArtwork(it) } }
    Box(modifier.background(Ink.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        Crossfade(image, animationSpec = tween(250)) { img ->
            if (img != null) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Ink.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
fun rememberArtworkColor(url: String?): State<Color?> {
    val state = remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            state.value = null
            return@LaunchedEffect
        }
        val k = artKey(url)
        synchronized(colours) { colours[k] }?.let {
            state.value = it
            return@LaunchedEffect
        }
        val img = loadArtwork(url) ?: return@LaunchedEffect
        state.value = withContext(Dispatchers.Default) { dominant(img) }.also {
            synchronized(colours) { colours[k] = it }
        }
    }
    return state
}

private fun dominant(image: ImageBitmap): Color {
    val bmp = Bitmap().apply { allocN32Pixels(48, 48) }
    org.jetbrains.skia.Canvas(bmp).drawImageRect(
        Image.makeFromBitmap(image.asSkiaBitmap()),
        org.jetbrains.skia.Rect.makeWH(48f, 48f),
    )
    var x = 0.0
    var y = 0.0
    var sat = 0f
    var weight = 0f
    var value = 0f
    var count = 0
    val hsb = FloatArray(3)
    for (py in 0 until 48) {
        for (px in 0 until 48) {
            val c = bmp.getColor(px, py)
            java.awt.Color.RGBtoHSB((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsb)
            val w = hsb[1] * hsb[2] + 0.05f
            val rad = hsb[0] * 2 * Math.PI
            x += cos(rad) * w
            y += sin(rad) * w
            sat += hsb[1] * w
            weight += w
            value += hsb[2]
            count++
        }
    }
    val hue = ((atan2(y, x) / (2 * Math.PI)).toFloat() + 1f) % 1f
    return Color(java.awt.Color.HSBtoRGB(hue, (sat / weight).coerceIn(0.2f, 0.9f), (value / count).coerceIn(0.35f, 0.9f)))
}

object Http {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
