package dev.lelonio.square.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.R
import dev.lelonio.square.data.AudioInspection
import dev.lelonio.square.data.AudioInspection.Verdict
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a song's file is: its spectrum, with a guess at whether it is the
 * lossless it says it is, and everything its FLAC header carries.
 *
 * In the player's ⓘ slot for the song playing, and on its own page from a
 * song's menu.
 */
@Composable
fun InspectView(uri: String, localFile: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember(uri, localFile) { AudioInspection.sourceFor(context, uri, localFile) }
    if (source == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.inspect_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
        return
    }

    var header by remember(source) { mutableStateOf<AudioInspection.Header?>(null) }
    var spectrum by remember(source) { mutableStateOf<AudioInspection.Spectrum?>(null) }
    var image by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var progress by remember(source) { mutableFloatStateOf(0f) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        header = runCatching { AudioInspection.header(context, source) }
            .getOrElse { if (it is CancellationException) throw it; AudioInspection.Header(null, null, null) }
    }
    LaunchedEffect(source) {
        try {
            val result = AudioInspection.spectrum(context, source) { progress = it }
            image = result.toImage()
            spectrum = result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Section(stringResource(R.string.inspect_spectrum)) {
            val shown = spectrum
            val picture = image
            when {
                failed -> Text(stringResource(R.string.inspect_failed), color = InkDim)
                shown == null || picture == null -> {
                    Text(
                        stringResource(R.string.inspect_analysing, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        color = Ink,
                        trackColor = InkDim.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                else -> {
                    val verdict = remember(shown) { AudioInspection.verdict(shown.average, shown.sampleRate) }
                    Spectrogram(picture, shown.sampleRate, (verdict as? Verdict.Lossy)?.cutoffHz ?: (verdict as? Verdict.Upsampled)?.cutoffHz)
                    Text(
                        verdict.describe(),
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        stringResource(R.string.inspect_estimate),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                    )
                }
            }
        }

        val read = header ?: return@Column
        val flac = read.flac
        Section(stringResource(R.string.inspect_file)) {
            Field(R.string.inspect_format, if (flac != null) "FLAC" else read.mime ?: "?")
            if (flac != null) {
                Field(R.string.inspect_sample_rate, "%.1f kHz".format(flac.sampleRate / 1000f))
                Field(R.string.inspect_bit_depth, "${flac.bitsPerSample}-bit")
                Field(R.string.inspect_channels, flac.channels.toString())
                Field(R.string.inspect_duration, clock(flac.durationMs))
                Field(R.string.inspect_samples, "%,d".format(flac.totalSamples))
            }
            read.fileBytes?.let { bytes ->
                Field(R.string.inspect_size, "%.2f MB".format(bytes / 1_048_576f))
                if (flac != null && flac.durationMs > 0) {
                    val audio = bytes - flac.audioOffset
                    Field(R.string.inspect_bitrate, "${audio * 8 / flac.durationMs} kbps")
                    val raw = flac.totalSamples * flac.channels * flac.bitsPerSample / 8
                    if (raw > 0) Field(R.string.inspect_compression, "%.1f%%".format(audio * 100f / raw))
                }
            }
            if (flac != null) {
                Field(R.string.inspect_block_size, if (flac.minBlock == flac.maxBlock) "${flac.minBlock}" else "${flac.minBlock}–${flac.maxBlock}")
                Field(R.string.inspect_encoder, flac.vendor ?: "?")
                Field("MD5", if (flac.md5.all { it == '0' }) "—" else flac.md5, mono = true)
                flac.pictures.forEach {
                    // Some taggers (Mutagen) leave the size as 0×0.
                    val size = if (it.width > 0) " · ${it.width}×${it.height}" else ""
                    Field(R.string.inspect_cover, "${it.mime}$size · ${it.bytes / 1024} KB")
                }
            }
        }
        if (flac != null && flac.tags.isNotEmpty()) {
            Section(stringResource(R.string.inspect_tags)) {
                flac.tags.forEach { (key, value) -> Field(key, value) }
            }
        }
        if (flac != null) {
            Section(stringResource(R.string.inspect_blocks)) {
                flac.blocks.forEach { (name, bytes) -> Field(name, "%,d B".format(bytes), mono = true) }
            }
        }
    }
}

/** A song that is a file Square can open: the NAS, or the phone's own. */
internal fun isFileTrack(uri: String) = uri.startsWith("subsonic:track:") || dev.lelonio.square.data.LocalLibrary.isLocal(uri)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Field(label: Int, value: String, mono: Boolean = false) = Field(stringResource(label), value, mono)

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = InkDim, modifier = Modifier.weight(0.4f))
        SelectionContainer(Modifier.weight(0.6f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else it },
                color = Ink,
            )
        }
    }
}

/** The picture, with a kHz scale down its left side and [markHz] dashed across. */
@Composable
private fun Spectrogram(image: ImageBitmap, sampleRate: Int, markHz: Int?) {
    val measurer = rememberTextMeasurer()
    val label = InkDim
    val style = MaterialTheme.typography.labelSmall.copy(color = label)
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val gutter = 30.dp.toPx()
        val width = size.width - gutter
        drawImage(
            image,
            dstOffset = IntOffset(gutter.toInt(), 0),
            dstSize = IntSize(width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
        val nyquist = sampleRate / 2f
        val step = if (nyquist > 30_000) 10_000 else 5_000
        var hz = 0
        while (hz <= nyquist) {
            val y = size.height * (1 - hz / nyquist)
            drawLine(label.copy(alpha = 0.35f), Offset(gutter - 4, y), Offset(gutter, y))
            val text = measurer.measure("${hz / 1000}k", style)
            drawText(text, topLeft = Offset(0f, (y - text.size.height / 2).coerceIn(0f, size.height - text.size.height)))
            hz += step
        }
        markHz?.let {
            val y = size.height * (1 - it / nyquist)
            drawLine(
                Color.White.copy(alpha = 0.8f),
                Offset(gutter, y),
                Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        drawRect(label.copy(alpha = 0.3f), Offset(gutter, 0f), androidx.compose.ui.geometry.Size(width, size.height), style = Stroke(1f))
    }
}

@Composable
private fun Verdict.describe(): String = when (this) {
    Verdict.Lossless -> stringResource(R.string.verdict_lossless)
    is Verdict.Upsampled -> stringResource(R.string.verdict_upsampled, khz(cutoffHz))
    is Verdict.Lossy -> stringResource(R.string.verdict_lossy, khz(cutoffHz), kbps)
    Verdict.TooQuiet -> stringResource(R.string.verdict_quiet)
}

private fun khz(hz: Int) = "%.1f".format(hz / 1000f)

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Spek's palette: silence black, through blue and purple and red, to yellow
 * and white at the loudest; the bottom 120 dB below the peak shown.
 */
private fun AudioInspection.Spectrum.toImage(): ImageBitmap {
    val rows = AudioInspection.ROWS
    val peak = db.maxOf { it.max() }
    val pixels = IntArray(columns * rows)
    for (c in 0 until columns) for (r in 0 until rows) {
        val t = ((db[c][r] - (peak - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
        pixels[(rows - 1 - r) * columns + c] = palette(t)
    }
    return android.graphics.Bitmap.createBitmap(pixels, columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private val STOPS = intArrayOf(0x000000, 0x1a0a4a, 0x5a1080, 0xb01060, 0xf04020, 0xffa000, 0xffff60, 0xffffff)

private fun palette(t: Float): Int {
    val x = t * (STOPS.size - 1)
    val i = x.toInt().coerceAtMost(STOPS.size - 2)
    val f = x - i
    fun mix(shift: Int) = ((STOPS[i] shr shift and 0xff) * (1 - f) + (STOPS[i + 1] shr shift and 0xff) * f).toInt()
    return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

private const val RANGE_DB = 120f
