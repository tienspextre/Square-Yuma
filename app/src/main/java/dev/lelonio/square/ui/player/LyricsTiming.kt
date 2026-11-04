package dev.lelonio.square.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import dev.lelonio.square.playback.AudioEffects
import kotlinx.coroutines.isActive

/**
 * 60/120fps drift-corrected hardware-clock lyrics timing coordinator.
 *
 * Eliminates the 250ms polling stutter, accounts for playback speed and audio drift,
 * applies human visual perception lead-time (+120ms for syllables, +260ms for lines),
 * and incorporates user-customized sync offsets (AudioEffects.lyricsSyncOffsetMs).
 */
@Composable
fun rememberSmoothLyricsPosition(
    source: State<Long>,
    isPlaying: Boolean,
    playbackSpeed: Float = 1f,
    isTtml: Boolean = false,
): State<Long> {
    val syncOffsetState = AudioEffects.lyricsSyncOffsetMs.collectAsState()
    val smoothed = remember { mutableLongStateOf(source.value) }

    val latestSource = rememberUpdatedState(source.value)
    val latestIsPlaying = rememberUpdatedState(isPlaying)
    val latestSpeed = rememberUpdatedState(playbackSpeed)
    val latestSyncOffset = rememberUpdatedState(syncOffsetState.value)
    val latestIsTtml = rememberUpdatedState(isTtml)

    LaunchedEffect(isPlaying) {
        val leadMs = if (latestIsTtml.value) 120L else 260L
        if (!latestIsPlaying.value) {
            smoothed.longValue = (latestSource.value + latestSyncOffset.value + leadMs).coerceAtLeast(0L)
            return@LaunchedEffect
        }

        var anchorPlayerMs = latestSource.value
        var anchorNanos = 0L

        // The source only changes every ~250ms poll. Comparing the stale value against a per-frame
        // projection read as a -200ms "seek" before every poll and snapped back: syllables flickered.
        // So drift is measured only when a fresh value lands, and output never runs backwards
        // except on a real seek.
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
                    // Seek or track change: snap.
                    anchorNanos = nowNanos
                    anchorPlayerMs = rawPlayerMs
                    nextPositionMs = rawPlayerMs
                    seeked = true
                } else {
                    // Gentle slew toward the player clock.
                    anchorPlayerMs += driftMs / 4
                    nextPositionMs += driftMs / 4
                }
            }

            val currentLead = if (latestIsTtml.value) 120L else 260L
            var finalPositionMs = (nextPositionMs + latestSyncOffset.value + currentLead).coerceAtLeast(0L)
            if (!seeked && finalPositionMs < lastOut) finalPositionMs = lastOut
            lastOut = finalPositionMs

            if (smoothed.longValue != finalPositionMs) {
                smoothed.longValue = finalPositionMs
            }
        }
    }

    return smoothed
}
