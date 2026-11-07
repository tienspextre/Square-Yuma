package dev.lelonio.square.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.isActive

internal val GlassInk: Color
    @Composable get() = dev.lelonio.square.ui.theme.Ink

internal val GlassInkDim: Color
    @Composable get() = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.68f)

/**
 * 60/120fps drift-corrected hardware-clock lyrics timing coordinator for Desktop.
 *
 * Eliminates polling stutter, accounts for playback speed and audio drift,
 * and applies YumaPlayer's lead-time (+150ms for word-timed lyrics, +450ms for line-timed).
 */
@Composable
fun rememberSmoothLyricsPosition(
    source: State<Long>,
    isPlaying: Boolean,
    playbackSpeed: Float = 1f,
    isTtml: Boolean = false,
): State<Long> {
    val smoothed = remember { mutableLongStateOf(source.value) }

    val latestSource = rememberUpdatedState(source.value)
    val latestIsPlaying = rememberUpdatedState(isPlaying)
    val latestSpeed = rememberUpdatedState(playbackSpeed)
    val latestIsTtml = rememberUpdatedState(isTtml)

    LaunchedEffect(isPlaying) {
        val leadMs = if (latestIsTtml.value) WORD_LEAD_MS else LINE_LEAD_MS
        if (!latestIsPlaying.value) {
            smoothed.longValue = (latestSource.value + leadMs).coerceAtLeast(0L)
            return@LaunchedEffect
        }

        var anchorPlayerMs = latestSource.value
        var anchorNanos = 0L

        var lastRaw = latestSource.value
        var lastOut = Long.MIN_VALUE
        while (isActive) {
            val nowNanos = withFrameNanos { it }
            if (anchorNanos == 0L) {
                anchorNanos = nowNanos
                anchorPlayerMs = latestSource.value
            }

            val rawPlayerMs = latestSource.value
            val elapsedMs = ((nowNanos - anchorNanos) / 1_000_000.0) * latestSpeed.value.toDouble()
            var nextPositionMs = (anchorPlayerMs + elapsedMs).toLong()
            var seeked = false
            if (rawPlayerMs != lastRaw) {
                lastRaw = rawPlayerMs
                val driftMs = rawPlayerMs - nextPositionMs
                if (driftMs > 300L || driftMs < -300L) {
                    anchorNanos = nowNanos
                    anchorPlayerMs = rawPlayerMs
                    nextPositionMs = rawPlayerMs
                    seeked = true
                } else {
                    anchorPlayerMs += driftMs / 4
                    nextPositionMs += driftMs / 4
                }
            }

            val currentLead = if (latestIsTtml.value) WORD_LEAD_MS else LINE_LEAD_MS
            var finalPositionMs = (nextPositionMs + currentLead).coerceAtLeast(0L)
            if (!seeked && finalPositionMs < lastOut) finalPositionMs = lastOut
            lastOut = finalPositionMs

            if (smoothed.longValue != finalPositionMs) {
                smoothed.longValue = finalPositionMs
            }
        }
    }

    return smoothed
}

private const val WORD_LEAD_MS = 150L
private const val LINE_LEAD_MS = 450L
