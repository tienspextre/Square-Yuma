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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.data.DesktopAudioInspection
import dev.lelonio.square.data.DesktopAudioInspection.Verdict
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import java.awt.image.BufferedImage
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun DesktopInspectView(
    sourceUrlOrPath: String?,
    expectedDurationMs: Long = 180_000L,
    expectedSampleRate: Int = 44100,
    modifier: Modifier = Modifier,
) {
    if (sourceUrlOrPath.isNullOrBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Stream inspection is available for NAS Subsonic and Local Lossless files.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
        return
    }

    var header by remember(sourceUrlOrPath) { mutableStateOf<DesktopAudioInspection.Header?>(null) }
    var spectrum by remember(sourceUrlOrPath) { mutableStateOf<DesktopAudioInspection.Spectrum?>(null) }
    var image by remember(sourceUrlOrPath) { mutableStateOf<ImageBitmap?>(null) }
    var progress by remember(sourceUrlOrPath) { mutableFloatStateOf(0f) }
    var failed by remember(sourceUrlOrPath) { mutableStateOf(false) }

    LaunchedEffect(sourceUrlOrPath) {
        header = runCatching { DesktopAudioInspection.header(sourceUrlOrPath) }
            .getOrElse { if (it is CancellationException) throw it; DesktopAudioInspection.Header(null, null, null) }
    }
    LaunchedEffect(sourceUrlOrPath) {
        try {
            val result = DesktopAudioInspection.spectrum(
                sourceUrlOrPath = sourceUrlOrPath,
                expectedDurationMs = expectedDurationMs,
                expectedSampleRate = expectedSampleRate,
            ) { progress = it }
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
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Section("SPECTRUM & LOSSLESS VERIFICATION") {
            val shown = spectrum
            val picture = image
            when {
                failed -> Text("Could not decode audio stream for spectrum analysis.", color = InkDim)
                shown == null || picture == null -> {
                    Text(
                        "Analysing full-track FFT spectrum… ${(progress * 100).toInt()}%",
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
                    val verdict = remember(shown) { DesktopAudioInspection.verdict(shown.average, shown.sampleRate) }
                    Spectrogram(
                        image = picture,
                        sampleRate = shown.sampleRate,
                        markHz = (verdict as? Verdict.Lossy)?.cutoffHz ?: (verdict as? Verdict.Upsampled)?.cutoffHz,
                    )
                    Text(
                        verdict.describe(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "4096-point Hann-windowed FFT across the full file (-120 dB floor, Spek palette).",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                    )
                }
            }
        }

        val read = header ?: return@Column
        val flac = read.flac
        Section("AUDIO STREAM & FLAC HEADER") {
            Field("Format", if (flac != null) "FLAC (Free Lossless Audio Codec)" else read.mime ?: "Audio Stream")
            if (flac != null) {
                Field("Sample Rate", "%.1f kHz".format(flac.sampleRate / 1000f))
                Field("Bit Depth", "${flac.bitsPerSample}-bit")
                Field("Channels", "${flac.channels} ch")
                Field("Duration", clock(flac.durationMs))
                Field("Total Samples", "%,d".format(flac.totalSamples))
            }
            read.fileBytes?.let { bytes ->
                Field("File Size", "%.2f MB".format(bytes / 1_048_576f))
                if (flac != null && flac.durationMs > 0) {
                    val audio = bytes - flac.audioOffset
                    Field("Bitrate", "${audio * 8 / flac.durationMs} kbps")
                    val raw = flac.totalSamples * flac.channels * flac.bitsPerSample / 8
                    if (raw > 0) Field("Compression Ratio", "%.1f%%".format(audio * 100f / raw))
                }
            }
            if (flac != null) {
                Field("Block Size", if (flac.minBlock == flac.maxBlock) "${flac.minBlock} samples" else "${flac.minBlock}–${flac.maxBlock} samples")
                Field("Encoder / Vendor", flac.vendor ?: "—")
                Field("Audio MD5", if (flac.md5.all { it == '0' }) "—" else flac.md5, mono = true)
                flac.pictures.forEach {
                    val size = if (it.width > 0) " · ${it.width}×${it.height}" else ""
                    Field("Embedded Cover", "${it.mime}$size · ${it.bytes / 1024} KB")
                }
            }
        }
        if (flac != null && flac.tags.isNotEmpty()) {
            Section("VORBIS COMMENTS / METADATA TAGS") {
                flac.tags.forEach { (key, value) -> Field(key, value) }
            }
        }
        if (flac != null) {
            Section("FLAC METADATA BLOCKS") {
                flac.blocks.forEach { (name, bytes) -> Field(name, "%,d B".format(bytes), mono = true) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = InkDim, modifier = Modifier.weight(0.38f))
        SelectionContainer(Modifier.weight(0.62f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else it },
                color = Ink,
            )
        }
    }
}

@Composable
private fun Spectrogram(image: ImageBitmap, sampleRate: Int, markHz: Int?) {
    val measurer = rememberTextMeasurer()
    val label = InkDim
    val style = MaterialTheme.typography.bodySmall.copy(color = label, fontSize = 11.sp)
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val gutter = 36.dp.toPx()
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
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        drawRect(label.copy(alpha = 0.3f), Offset(gutter, 0f), Size(width, size.height), style = Stroke(1f))
    }
}

private fun Verdict.describe(): String = when (this) {
    Verdict.Lossless -> "✓ Genuine Lossless — Full frequency extension up to Nyquist with no transcode shelf."
    is Verdict.Upsampled -> "⚠ Upsampled — High-rate container, but spectrum cuts off at ${khz(cutoffHz)} kHz."
    is Verdict.Lossy -> "⚠ Suspect Transcode — Steep low-pass shelf at ${khz(cutoffHz)} kHz (typical of $kbps kbps lossy)."
    Verdict.TooQuiet -> "Silence / Too quiet to determine spectral cutoff."
}

private fun khz(hz: Int) = "%.1f".format(hz / 1000f)

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun DesktopAudioInspection.Spectrum.toImage(): ImageBitmap {
    val rows = DesktopAudioInspection.ROWS
    val peak = db.maxOf { it.maxOrNull() ?: -120f }
    val buffered = BufferedImage(columns, rows, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(columns * rows)
    for (c in 0 until columns) for (r in 0 until rows) {
        val t = ((db[c][r] - (peak - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
        pixels[(rows - 1 - r) * columns + c] = palette(t)
    }
    buffered.setRGB(0, 0, columns, rows, pixels, 0, columns)
    return buffered.toComposeImageBitmap()
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
