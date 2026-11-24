/*
 * Transition styles and their automation, after BitChord's CrossfadeController
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0), itself built on Orchard
 * (https://github.com/SFG5453/Orchard, AGPL-3.0). Constants and curve shapes are
 * theirs; the mapping onto this app's DJ filter (low-pass, high-pass, bass shelf) is ours.
 */
package moe.rukamori.archivetune.audiodsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

enum class MixStyle {
    /** Beat-matched blend with a bass swap (the existing staged automix curves). */
    BLEND,
    /** Tempi too far apart to blend: outgoing closes behind a low-pass, incoming arrives high-passed. */
    FILTER_SWEEP,
    /** Same, lighter on the mids, with one decisive low-end handover: for two kicks at the join. */
    BASS_HANDOFF,
    /** No usable grid: equal-power, with a vocal-separation filter as far as the two vocals clash. */
    EQUAL_POWER,
}

/** One deck's settings at one instant of a transition. */
data class DeckShape(val gain: Float, val lowPassHz: Double, val highPassHz: Double, val bassDb: Double)

object MixStyles {
    const val OPEN_HZ = 20_000.0
    const val OFF_HZ = 20.0
    const val CUT_DB = -24.0

    private const val FILTER_ENTRY_HZ = 7_000.0
    private const val FILTER_FLOOR_HZ = 300.0
    private const val FILTER_SWEEP_SHAPE = 0.75
    private const val FILTER_FALL_SHAPE = 1.6f
    private const val FILTER_RISE_SHAPE = 0.8f
    private const val ENTRY_HIGH_PASS_HZ = 1_200.0
    private const val ENTRY_SHAPE = 0.35
    private const val VOCAL_SAFE_ENTRY_HIGH_PASS_HZ = 2_400.0
    private const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
    private const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
    private const val VOCAL_SAFE_RISE_SHAPE = 1.65f
    private const val VOCAL_SAFE_FALL_SHAPE = 0.65f
    const val SWEEP_SWAP_AT = 0.5
    const val HANDOFF_SWAP_AT = 0.43
    private const val SWEEP_OPEN_BY = 0.6
    private const val HANDOFF_OPEN_BY = 0.38
    private const val VOCAL_OPEN_BY = 0.6
    /** A handoff's floor: it keeps the mids recognisable, unlike the sweep's 300 Hz bed. */
    private const val HANDOFF_FLOOR_HZ = 1_500.0
    /** Without a grid the low end changes hands over this much of the fade. */
    private const val SWAP_WIDTH = 0.10

    fun rise(p: Float) = sin(p.coerceIn(0f, 1f) * PI.toFloat() / 2f)
    fun fall(p: Float) = cos(p.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** Geometric: pitch is logarithmic, so equal steps in Hz would lurch and crawl. */
    fun glide(from: Double, to: Double, amount: Double) = from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /** Rounds a fade fraction onto the beat grid, when there is one. */
    fun onBeat(fraction: Double, beatsInFade: Double): Double =
        if (beatsInFade >= 2) (round(fraction * beatsInFade) / beatsInFade).coerceIn(1 / beatsInFade, 1 - 1 / beatsInFade)
        else fraction

    /** The incoming high-pass: from [topHz] (scaled by [amount]) down to open by [openBy]. */
    private fun entryHighPass(p: Float, amount: Double, topHz: Double, openBy: Double): Double {
        val remaining = (1.0 - p / openBy).coerceIn(0.0, 1.0)
        return glide(OFF_HZ, topHz, amount * remaining.pow(ENTRY_SHAPE))
    }

    /**
     * Both decks for [style] at progress [p]. [swapAt] is where the low end changes hands,
     * [swapWidth] how long it takes (half a beat on a grid), [clash] 0..1 how much the two
     * vocals sing over each other.
     */
    fun shape(
        style: MixStyle, p: Float, swapAt: Double, swapWidth: Double, clash: Double,
    ): Pair<DeckShape, DeckShape> {
        val handover = ((p - (swapAt - swapWidth)) / swapWidth).coerceIn(0.0, 1.0)
        // One low end at a time: incoming's is cut until the handover, outgoing's after it.
        val inBass = CUT_DB * (1 - handover)
        val outBass = CUT_DB * handover
        return when (style) {
            MixStyle.FILTER_SWEEP, MixStyle.BASS_HANDOFF -> {
                val handoff = style == MixStyle.BASS_HANDOFF
                val floor = if (handoff) HANDOFF_FLOOR_HZ else FILTER_FLOOR_HZ
                val outLp = glide(glide(OPEN_HZ, FILTER_ENTRY_HZ, 1.0), floor, p.toDouble().pow(FILTER_SWEEP_SHAPE))
                val inHp = entryHighPass(
                    p, 1.0, glide(ENTRY_HIGH_PASS_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, clash),
                    if (handoff) HANDOFF_OPEN_BY else SWEEP_OPEN_BY,
                )
                val styledIn = rise(p.pow(FILTER_RISE_SHAPE))
                val styledOut = fall(p.pow(FILTER_FALL_SHAPE))
                DeckShape(vocalSafe(styledOut, fall(p.pow(VOCAL_SAFE_FALL_SHAPE)), clash), outLp, OFF_HZ, outBass) to
                    DeckShape(vocalSafe(styledIn, rise(p.pow(VOCAL_SAFE_RISE_SHAPE)), clash), OPEN_HZ, inHp, inBass)
            }
            MixStyle.BLEND -> {
                // Beat-matched: both play full range, one kick and bassline at a time, the low end
                // changing hands on the beat the planner picked. Vocals pulled apart only on a clash.
                val outLp = if (clash > 0) glide(OPEN_HZ, glide(OPEN_HZ, VOCAL_SEPARATION_FLOOR_HZ, clash), p.toDouble().pow(FILTER_SWEEP_SHAPE)) else OPEN_HZ
                val inHp = if (clash > 0) entryHighPass(p, clash, VOCAL_SEPARATION_HIGH_PASS_HZ, VOCAL_OPEN_BY) else OFF_HZ
                DeckShape(fall(p), outLp, OFF_HZ, outBass) to DeckShape(rise(p), OPEN_HZ, inHp, inBass)
            }
            MixStyle.EQUAL_POWER -> {
                // Damage control only: nudge two colliding vocals apart, as far as they collide.
                val floor = glide(OPEN_HZ, VOCAL_SEPARATION_FLOOR_HZ, clash)
                val outLp = if (clash > 0) glide(OPEN_HZ, floor, p.toDouble().pow(FILTER_SWEEP_SHAPE)) else OPEN_HZ
                val inHp = if (clash > 0) entryHighPass(p, clash, VOCAL_SEPARATION_HIGH_PASS_HZ, VOCAL_OPEN_BY) else OFF_HZ
                DeckShape(fall(p), outLp, OFF_HZ, 0.0) to DeckShape(rise(p), OPEN_HZ, inHp, 0.0)
            }
        }
    }

    private fun vocalSafe(styled: Float, safe: Float, clash: Double) = styled + (safe - styled) * clash.toFloat()

    fun swapWidth(beatsInFade: Double) = if (beatsInFade >= 2) 0.5 / beatsInFade else SWAP_WIDTH
}
