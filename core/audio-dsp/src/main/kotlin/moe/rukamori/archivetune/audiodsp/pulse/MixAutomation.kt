package moe.rukamori.archivetune.audiodsp.pulse

/**
 * One deck's virtual stem bus settings. Band gains map automix-engine's stems onto the DJ filter:
 * bass → [low], drums → [low]+[high], other → [mid], vocals → the Mid/Side duck ([vocal], 1 =
 * untouched). Filter cutoffs of 0 mean off.
 */
data class StemMix(
    val low: Float,
    val mid: Float,
    val high: Float,
    val vocal: Float,
    val highPassHz: Float = 0f,
    val lowPassHz: Float = 0f,
) {
    companion object { val FULL = StemMix(1f, 1f, 1f, 1f) }
}

/** Stem gain curves on the bars axis (0..8 over the 32-pulse lock, past 8 the tail). */
object MixAutomation {
    const val BED_HIGH_PASS_HZ = 320f
    const val DRUMS_HIGH_PASS_HZ = 170f
    const val DARK_LOW_PASS_HZ = 3600f
    const val OPEN_LOW_PASS_HZ = 16000f
    const val LOCK_BARS = PulseFeatures.TRANSITION_PULSES / PulseFeatures.PULSES_PER_BAR.toDouble()
    private const val SWAP_BARS = 0.5
    /**
     * Deepest incoming vocal duck. The Mid/Side duck removes the whole centre of 250 Hz–5 kHz, where
     * the kick's punch, the bass's harmonics and the snare live too, so a full duck sounds bass-less.
     */
    const val VOCAL_DUCK_FLOOR = 0.5f

    /** 0 before [a], 1 after [b], smoothstep between. */
    fun ramp(x: Double, a: Double, b: Double): Float {
        if (b <= a) return if (x >= b) 1f else 0f
        val p = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return (p * p * (3 - 2 * p)).toFloat()
    }

    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p

    fun cueBar(plan: PulsePlan) = plan.cuePulse / PulseFeatures.PULSES_PER_BAR.toDouble()

    /** [sourceMs] is the outgoing track position, for the fill's end past the lock. */
    fun outgoing(plan: PulsePlan, bars: Double, sourceMs: Double): StemMix {
        val cue = cueBar(plan)
        val floor = plan.fill?.level?.toFloat() ?: 0f
        val (vf0, vf1) = plan.outVocalFadeBars
        val vocal = 1f - ramp(bars, vf0, vf1)
        if (bars >= LOCK_BARS) {
            val fill = plan.fill ?: return StemMix(0f, 0f, 0f, 0f)
            val handoff = plan.outBeats.last()
            val left = 1f - ramp(sourceMs, handoff, fill.endMs)
            return StemMix(0f, floor * left, floor * left, vocal * floor * left, lowPassHz = DARK_LOW_PASS_HZ)
        }
        val fade = 1f - ramp(bars, cue, LOCK_BARS)
        val body = lerp(floor, 1f, fade)
        return StemMix(
            low = 1f - ramp(bars, cue, cue + SWAP_BARS),
            mid = body,
            high = body,
            vocal = vocal,
            lowPassHz = if (bars < cue) 0f else lerp(OPEN_LOW_PASS_HZ, DARK_LOW_PASS_HZ, ramp(bars, cue, cue + 1)),
        )
    }

    /** [sourceMs] is the incoming track position: the vocal reveal runs on its own clock. */
    fun incoming(plan: PulsePlan, bars: Double, sourceMs: Double): StemMix {
        val cue = cueBar(plan)
        val r = plan.inVocal
        val planned = when (r.mode) {
            VocalMode.CUE_SYNC -> ramp(sourceMs, r.fadeStartMs, r.lineStartMs)
            VocalMode.TWO_STAGE ->
                if (sourceMs < r.lineStartMs) r.lineGain.toFloat() * ramp(sourceMs, r.fadeStartMs, r.lineStartMs)
                else lerp(r.lineGain.toFloat(), 1f, ramp(sourceMs, r.lineStartMs, r.lineStartMs + r.tailMs))
            VocalMode.NONE -> 0f
        }
        // Past the lock the incoming deck is the song: open within a bar instead of holding the
        // duck until its next sung line (up to ~30 s later), which kept its body hollow.
        val open = maxOf(planned, ramp(bars, LOCK_BARS, LOCK_BARS + 1))
        val vocal = lerp(VOCAL_DUCK_FLOOR, 1f, open)
        val hp = when {
            bars < cue - 2 -> BED_HIGH_PASS_HZ
            bars < cue -> DRUMS_HIGH_PASS_HZ
            else -> 0f
        }
        return StemMix(
            low = ramp(bars, cue, cue + SWAP_BARS),
            mid = lerp(0.7f * ramp(bars, 0.0, cue - 1), 1f, ramp(bars, cue - 1, cue)),
            high = ramp(bars, cue - 2, cue),
            vocal = vocal,
            highPassHz = hp,
        )
    }

    /** Rough loudness weight of a stem mix: power share of the bands (low 0.4, mid 0.4, high 0.2). */
    fun power(m: StemMix): Double =
        0.4 * m.low * m.low + 0.4 * m.mid * m.mid + 0.2 * m.high * m.high
}
