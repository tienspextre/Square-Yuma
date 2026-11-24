package moe.rukamori.archivetune.audiodsp.pulse

import kotlin.math.log10
import kotlin.math.max
import org.json.JSONObject

/** A frame curve made of windows (head, tail, model windows); NaN where nothing covers [t]. */
class Envelope(val segments: List<Seg>) {
    class Seg(val t0: Double, val hop: Double, val v: DoubleArray) {
        val end get() = t0 + hop * v.size
    }

    fun at(t: Double): Double {
        // Later segments win: the tail overrides the head's overlap, the model overrides DSP.
        for (s in segments.asReversed()) {
            val i = ((t - s.t0) / s.hop).toInt()
            if (t >= s.t0 && i < s.v.size) return s.v[i]
        }
        return Double.NaN
    }

    /** Frames of [hop] seconds over [from, to), uncovered ones as NaN. */
    fun frames(from: Double, to: Double, hop: Double = HOP): DoubleArray {
        val n = max(0, ((to - from) / hop).toInt())
        return DoubleArray(n) { at(from + it * hop) }
    }

    companion object {
        const val HOP = 0.05
        val EMPTY = Envelope(emptyList())

        fun parse(o: JSONObject?, shift: Double): Seg? {
            o ?: return null
            val a = o.optJSONArray("v") ?: return null
            val hop = o.optDouble("hop", HOP).takeIf { it > 0.0 } ?: return null
            return Seg(o.optDouble("t0", 0.0) + shift, hop, DoubleArray(a.length()) { a.optDouble(it, Double.NaN) })
        }
    }
}

/** The Pulse engine's per-track input, on the track clock (see docs/automix-pulse-rfc.md §3). */
class PulseFeatures(
    val pulseSec: Double,
    val pulseConfidence: Double,
    val pulseSubdivision: Int,
    val beats: DoubleArray,
    val drumOnsets: DoubleArray,
    val rmsDb: Envelope,
    val rhythmDb: Envelope,
    val vocalDb: Envelope,
    val vocalFromModel: Boolean,
) {
    val hasPulse get() = pulseSec > 0.0 && beats.size >= TRANSITION_PULSES + 1

    companion object {
        const val TRANSITION_PULSES = 32
        const val PULSES_PER_BAR = 4
        const val CUE_PULSE = 16

        fun parse(headJson: String, tailJson: String = "", tailOffset: Double = 0.0, modelJson: String = ""): PulseFeatures? =
            runCatching {
                if (headJson.isBlank()) return null
                val head = JSONObject(headJson)
                val tail = tailJson.takeIf { it.isNotBlank() }?.let(::JSONObject)
                fun nums(o: JSONObject, name: String, shift: Double) =
                    o.optJSONArray(name)?.let { a -> DoubleArray(a.length()) { a.optDouble(it) + shift } } ?: DoubleArray(0)
                // Head times up to where the tail starts, then the tail's own.
                fun joined(name: String): DoubleArray {
                    val h = nums(head, name, 0.0)
                    tail ?: return h
                    return h.filter { it < tailOffset }.toDoubleArray() + nums(tail, name, tailOffset)
                }
                fun env(name: String) = Envelope(listOfNotNull(Envelope.parse(head.optJSONObject(name), 0.0),
                    tail?.let { Envelope.parse(it.optJSONObject(name), tailOffset) }))

                val rms = env("rmsDb")
                val dspVocal = env("vocalDb")
                val model = modelVocal(modelJson, rms)
                // Pulse from the ending: the outgoing side reads it most, and it is what the tail grid used.
                val src = tail?.takeIf { it.optDouble("pulseInterval", 0.0) > 0.0 } ?: head
                PulseFeatures(
                    pulseSec = src.optDouble("pulseInterval", 0.0),
                    pulseConfidence = src.optDouble("pulseConfidence", 0.0),
                    pulseSubdivision = src.optInt("pulseSubdivision", 1).coerceIn(1, 4),
                    beats = joined("beats"),
                    drumOnsets = joined("drumOnsets"),
                    rmsDb = rms,
                    rhythmDb = env("rhythmDb"),
                    vocalDb = Envelope(dspVocal.segments + model),
                    vocalFromModel = model.isNotEmpty(),
                )
            }.getOrNull()

        /** Model mask → vocal dB: mix RMS + 20·log10(mask), resampled onto 50 ms frames. */
        private fun modelVocal(modelJson: String, rms: Envelope): List<Envelope.Seg> = runCatching {
            if (modelJson.isBlank()) return emptyList()
            val windows = JSONObject(modelJson).optJSONArray("vocal") ?: return emptyList()
            (0 until windows.length()).mapNotNull { w ->
                val win = windows.getJSONObject(w)
                val start = win.optDouble("start")
                val fps = win.optDouble("fps")
                val v = win.optJSONArray("v") ?: return@mapNotNull null
                if (fps <= 0.0 || v.length() == 0) return@mapNotNull null
                val n = (v.length() / fps / Envelope.HOP).toInt()
                Envelope.Seg(start, Envelope.HOP, DoubleArray(n) { i ->
                    val t = start + i * Envelope.HOP
                    val mask = v.optDouble((i * Envelope.HOP * fps).toInt().coerceAtMost(v.length() - 1), 0.0)
                    val mix = rms.at(t)
                    if (mix.isNaN()) Double.NaN else mix + 20 * log10(max(mask, 1e-3))
                })
            }
        }.getOrDefault(emptyList())
    }
}

/** A tempo map: source (track) ms → target (mix clock) ms, monotonic. Rate is d(source)/d(target). */
class TempoMap(val sourceMs: DoubleArray, val targetMs: DoubleArray) {
    init { require(sourceMs.size == targetMs.size && sourceMs.size >= 2) }

    /** Playback rate at [source] ms; 1.0 past the last point. */
    fun rateAt(source: Double): Double {
        if (source < sourceMs.first() || source >= sourceMs.last()) return 1.0
        var i = sourceMs.binarySearch(source)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, sourceMs.size - 2)
        return (sourceMs[i + 1] - sourceMs[i]) / (targetMs[i + 1] - targetMs[i])
    }

    fun targetAt(source: Double): Double = lerp(sourceMs, targetMs, source)
    fun sourceAt(target: Double): Double = lerp(targetMs, sourceMs, target)

    private fun lerp(x: DoubleArray, y: DoubleArray, v: Double): Double {
        if (v <= x.first()) return y.first() + (v - x.first())
        if (v >= x.last()) return y.last() + (v - x.last())
        var i = x.binarySearch(v)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, x.size - 2)
        return y[i] + (y[i + 1] - y[i]) * (v - x[i]) / (x[i + 1] - x[i])
    }
}

enum class VocalMode { CUE_SYNC, TWO_STAGE, NONE }

/** Incoming vocal plan, incoming track ms. */
data class VocalReveal(
    val mode: VocalMode,
    val fadeStartMs: Double = 0.0,
    val lineStartMs: Double = 0.0,
    val lineGain: Double = 1.0,
    val tailMs: Double = 0.0,
)

/** Outgoing fill: gain floor during the lock, kept past handoff until [endMs] (outgoing track ms). */
data class Fill(val level: Double, val endMs: Double)

data class GridQa(
    val outP90Phase: Double,
    val inP90Phase: Double,
    val relativePhase: Double,
    val rateOk: Boolean,
    val reason: String?,
) {
    val passed get() = reason == null
}

/** What the Pulse engine plays. Beats are track ms; bars run 0..8 over the 32-pulse lock. */
data class PulsePlan(
    val outBeats: DoubleArray,
    val inBeats: DoubleArray,
    val cuePulse: Int,
    val inTempo: TempoMap?,
    val outFillTempo: TempoMap?,
    val releaseEndTargetMs: Double,
    val outVocalFadeBars: Pair<Double, Double>,
    val inVocal: VocalReveal,
    val fill: Fill?,
    /** Bars → dB correction, applied through deck volume. */
    val loudnessBars: DoubleArray,
    val loudnessDb: DoubleArray,
    val beatmatched: Boolean,
    val fallbackStyle: moe.rukamori.archivetune.audiodsp.MixStyle?,
    val qa: GridQa,
    val pairId: String,
)
