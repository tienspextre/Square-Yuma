package moe.rukamori.archivetune.audiodsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

const val FULL_CUT_DB = -24.0

const val DIVE_START_P = 0.05f
const val DIVE_SUBMERGED_P = 0.35f
const val DIVE_HOLD_UNTIL_P = 0.45f

const val FILTER_OPEN_HZ = 20000.0
const val FILTER_UNDERWATER_HZ = 2200.0
const val FILTER_FLOOR_HZ = 1000.0

const val INCOMING_START_P = 0.30f

val RISE: (Float) -> Float = { p -> sin(p.coerceIn(0f, 1f) * PI.toFloat() / 2f) }
val FALL: (Float) -> Float = { p -> cos(p.coerceIn(0f, 1f) * PI.toFloat() / 2f) }

const val BASS_SWAP_WINDOW_START = 0.45f
const val BASS_SWAP_WINDOW_END = 0.55f
private const val BASS_SWAP_DOMINANCE = 0.55

const val MID_DUCK_MAX_DB = 6.0
const val SOLO_OUTGOING_PROGRESS = 0.55f

fun outgoingLowPassHz(progress: Float): Double {
    val p = progress.coerceIn(0f, 1f)
    return when {
        p <= DIVE_START_P -> FILTER_OPEN_HZ
        p <= DIVE_SUBMERGED_P -> {
            val t = (p - DIVE_START_P) / (DIVE_SUBMERGED_P - DIVE_START_P)
            FILTER_OPEN_HZ + (FILTER_UNDERWATER_HZ - FILTER_OPEN_HZ) * smoothStep(t)
        }
        p <= DIVE_HOLD_UNTIL_P -> FILTER_UNDERWATER_HZ
        else -> {
            val t = (p - DIVE_HOLD_UNTIL_P) / (1f - DIVE_HOLD_UNTIL_P)
            FILTER_UNDERWATER_HZ + (FILTER_FLOOR_HZ - FILTER_UNDERWATER_HZ) * smoothStep(t)
        }
    }
}

private val bassFloorLinear = 10.0.pow(FULL_CUT_DB / 20.0)

private fun bassSwapIncomingLinear(progress: Float): Double {
    val phase = ((progress.coerceIn(0f, 1f) - BASS_SWAP_WINDOW_START) /
        (BASS_SWAP_WINDOW_END - BASS_SWAP_WINDOW_START)).coerceIn(0f, 1f)
    return if (phase <= 0.5f) {
        phase / 0.5f * BASS_SWAP_DOMINANCE
    } else {
        BASS_SWAP_DOMINANCE + (phase - 0.5f) / 0.5f * (1.0 - BASS_SWAP_DOMINANCE)
    }.toDouble()
}

private fun linearToBassDb(linear: Double): Double =
    (20.0 * log10(linear.coerceAtLeast(bassFloorLinear))).coerceAtLeast(FULL_CUT_DB)

fun incomingBassGainDb(progress: Float): Double = linearToBassDb(bassSwapIncomingLinear(progress))

fun outgoingBassGainDb(progress: Float): Double = linearToBassDb(1.0 - bassSwapIncomingLinear(progress))

fun outgoingMidDuckDb(progress: Float): Double {
    val clamped = progress.coerceIn(0f, 1f)
    val s = sin((clamped * PI / 2.0).toDouble())
    return -MID_DUCK_MAX_DB * s * s
}

fun outgoingMidDuckGain(progress: Float): Double = 10.0.pow(outgoingMidDuckDb(progress) / 20.0)

fun outgoingStageGain(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= DIVE_HOLD_UNTIL_P) return 1.0f
    val t = (p - DIVE_HOLD_UNTIL_P) / (1f - DIVE_HOLD_UNTIL_P)
    return 1.0f - smoothStep(t)
}

fun incomingStageGain(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p < INCOMING_START_P) return 0f
    val t = (p - INCOMING_START_P) / (1f - INCOMING_START_P)
    return smoothStep(t)
}

private fun smoothStep(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

fun boundedFoldMs(latenessMs: Long, durationMs: Long): Long {
    if (latenessMs <= 0L || durationMs <= 0L) return 0L
    val maxFold = (durationMs / 2).coerceAtMost(durationMs - CrossfadeConstants.MIN_FADE_MS)
    return latenessMs.coerceIn(0L, maxFold.coerceAtLeast(0L))
}

fun fadeProgress(elapsedMs: Long, foldMs: Long, durationMs: Long): Float {
    val span = durationMs - foldMs
    if (span <= 0L) return 1f
    return ((elapsedMs - foldMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

fun advanceCueForElapsed(cueInMs: Long, elapsedMs: Long, maxPositionMs: Long?): Long {
    if (cueInMs <= 0L || elapsedMs <= 0L) return cueInMs
    val advanced = cueInMs + elapsedMs
    val ceiling = maxPositionMs ?: return advanced
    return advanced.coerceAtMost(ceiling.coerceAtLeast(0L))
}

fun effectiveCrossfadeDuration(durationMs: Long, configuredMs: Long): Long? {
    if (durationMs <= 0L) return null
    val maxDuration = durationMs - CrossfadeConstants.END_GUARD_MS
    if (maxDuration < CrossfadeConstants.MIN_FADE_MS) return null
    return configuredMs
        .coerceAtLeast(CrossfadeConstants.MIN_FADE_MS)
        .coerceAtMost(maxDuration)
}

fun requiredStartBufferMs(configuredMs: Long): Long =
    minOf(configuredMs, CrossfadeConstants.DEFAULT_MS)

fun isGaplessAlbumTransition(
    currentAlbum: String?,
    targetAlbum: String?,
): Boolean = currentAlbum != null && currentAlbum == targetAlbum
