package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.MusicNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

data class LoadedArtwork(
    val bitmap: ImageBitmap,
    val dominantColor: Color,
    val secondaryColor: Color,
)

object DesktopArtworkCache {
    private val cache = ConcurrentHashMap<String, LoadedArtwork>()

    suspend fun load(url: String?): LoadedArtwork? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        cache[url]?.let { return@withContext it }

        val buffered: BufferedImage? = runCatching {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6_000
                    conn.readTimeout = 8_000
                    conn.setRequestProperty("User-Agent", "SquareYumaDesktop/1.0.0")
                    conn.inputStream.use { stream ->
                        val bytes = stream.readBytes()
                        ImageIO.read(ByteArrayInputStream(bytes))
                    }
                }
                url.startsWith("file://") -> {
                    ImageIO.read(File(URI(url)))
                }
                else -> {
                    val f = File(url)
                    if (f.exists()) ImageIO.read(f) else null
                }
            }
        }.getOrNull()

        if (buffered == null) return@withContext null
        val (primary, secondary) = extractPalette(buffered)
        val loaded = LoadedArtwork(
            bitmap = buffered.toComposeImageBitmap(),
            dominantColor = primary,
            secondaryColor = secondary,
        )
        cache[url] = loaded
        loaded
    }

    private fun extractPalette(img: BufferedImage): Pair<Color, Color> {
        val w = img.width.coerceAtLeast(1)
        val h = img.height.coerceAtLeast(1)
        val stepX = (w / 24).coerceAtLeast(1)
        val stepY = (h / 24).coerceAtLeast(1)

        var r1 = 0L; var g1 = 0L; var b1 = 0L; var w1 = 0L
        var r2 = 0L; var g2 = 0L; var b2 = 0L; var w2 = 0L

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val sat = if (maxC == 0) 0 else ((maxC - minC) * 255) / maxC
                val weight = (sat + 32).toLong()
                if (x + y < (w + h) / 2) {
                    r1 += r * weight; g1 += g * weight; b1 += b * weight; w1 += weight
                } else {
                    r2 += r * weight; g2 += g * weight; b2 += b * weight; w2 += weight
                }
                x += stepX
            }
            y += stepY
        }

        val c1 = if (w1 > 0) Color((r1 / w1).toInt(), (g1 / w1).toInt(), (b1 / w1).toInt()) else Color(0xFF7C5CE6)
        val c2 = if (w2 > 0) Color((r2 / w2).toInt(), (g2 / w2).toInt(), (b2 / w2).toInt()) else Color(0xFF3B82F6)
        return c1 to c2
    }
}

@Composable
fun DesktopArtworkImage(
    artworkUrl: String?,
    titleFallback: String = "",
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onPaletteLoaded: ((Color, Color) -> Unit)? = null,
) {
    var artwork by remember(artworkUrl) { mutableStateOf<LoadedArtwork?>(null) }

    LaunchedEffect(artworkUrl) {
        val loaded = DesktopArtworkCache.load(artworkUrl)
        artwork = loaded
        if (loaded != null) {
            onPaletteLoaded?.invoke(loaded.dominantColor, loaded.secondaryColor)
        }
    }

    val loaded = artwork
    if (loaded != null) {
        Image(
            bitmap = loaded.bitmap,
            contentDescription = titleFallback,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        val seedHash = titleFallback.hashCode()
        val hue1 = ((seedHash and 0xFFFF) % 360).toFloat()
        val hue2 = (hue1 + 48f) % 360f
        val grad1 = Color.hsv(hue1, 0.55f, 0.42f)
        val grad2 = Color.hsv(hue2, 0.65f, 0.22f)
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    colors = listOf(grad1, grad2),
                    start = Offset.Zero,
                    end = Offset(300f, 300f),
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Fill.MusicNotes,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}
