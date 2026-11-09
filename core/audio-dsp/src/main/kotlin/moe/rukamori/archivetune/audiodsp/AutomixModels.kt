package moe.rukamori.archivetune.audiodsp

data class AutomixPlan(
    val triggerOffsetMs: Long,
    val durationMs: Long,
    val incomingStartMs: Long,
    val enableBassSwap: Boolean,
    val triggerAtMs: Long? = null,
    val prepareAheadMs: Long? = null,
    val tier: TransitionTier = TransitionTier.PLAIN_CROSSFADE,
    val incomingTempoRatio: Float = 1.0f,
    /** The pair planner's style; null keeps the deck's original staged curves. */
    val style: MixStyle? = null,
    /** Fade fraction where the low end changes hands, and how long that takes. */
    val swapAt: Double = 0.5,
    val swapWidth: Double = 0.1,
    /** 0..1: how much the two vocals sing over each other through the overlap. */
    val vocalClash: Double = 0.0,
    /** Loop mix: the outgoing song repeats [loopStartMs, loopEndMs) from its exit (= loop end). */
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
)

enum class TransitionTier { SMART_BEATMATCH, PLAIN_CROSSFADE, GAPLESS, HARD_CUT }

enum class TransitionStyle {
    PLAIN_CROSSFADE,
    DJ_ASSISTED,
    BEATMATCHED,
}
