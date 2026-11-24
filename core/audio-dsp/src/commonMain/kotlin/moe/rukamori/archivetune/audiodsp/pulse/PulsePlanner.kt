package moe.rukamori.archivetune.audiodsp.pulse

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import moe.rukamori.archivetune.audiodsp.MixFeatures
import moe.rukamori.archivetune.audiodsp.MixStyle
import moe.rukamori.archivetune.audiodsp.PairPlanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * automix-engine's cue_analysis + stem_transition decisions, on analysed features instead of
 * stems. See docs/automix-pulse-rfc.md §2 for the function map.
 */
object PulsePlanner {
    const val TRANSITION_PULSES = PulseFeatures.TRANSITION_PULSES
    const val PULSES_PER_BAR = PulseFeatures.PULSES_PER_BAR
    const val CUE_PULSE = PulseFeatures.CUE_PULSE
    const val MAX_CUE_PULSE = 28
    const val TEMPO_RELEASE_SECONDS = 6.48
    const val RELEASE_CONTROL_POINTS = 16
    const val MAX_RELEASE_PULSES = 16
    const val POST_VOCAL_HANDOFF_PULSES = 4
    const val VOCAL_QUIET_FLOOR_DB = -42.0
    const val VOCAL_QUIET_BELOW_REF_DB = 18.0
    const val VOCAL_LINE_BELOW_P75_DB = 15.0
    const val VOCAL_LINE_MIN_GAP = 0.04
    const val VOCAL_LINE_MIN_ACTIVE = 0.75
    const val CUE_VOCAL_WINDOW = 0.40
    const val CUE_VOCAL_MIN_QUIET = 0.25
    const val CUE_VOCAL_MIN_RISE_DB = 10.0
    const val CUE_VOCAL_MIN_SHARE = 0.45
    const val CUE_VOCAL_FADE_MS = 100.0
    const val MIN_CUE_RATE = 0.82
    const val MAX_CUE_RATE = 1.18
    const val MIN_MAP_RATE = 0.82
    const val MAX_MAP_RATE = 1.25
    const val FILL_DB = -6.0
    const val MIN_FILL = 0.28
    const val MAX_FILL = 0.65
    const val CUE_SCORE_WINDOW = 8
    const val CUE_RISE_WEIGHT = 0.65
    const val CUE_RISE_CAP_DB = 12.0
    const val CUE_STRONG_BELOW_P90_DB = 10.0
    const val CUE_MIN_GAIN_DB = 3.0
    const val QA_MAX_P90_PHASE = 0.32
    const val QA_MAX_RELATIVE_PHASE = 0.12
    const val QA_MIN_ONSETS = 8
    const val LOUDNESS_MIN_DB = -3.0
    const val LOUDNESS_MAX_DB = 5.0
    /** ponytail: the head window is 65 s; cues past this leave too little after them to score. */
    const val MAX_ENTRY_S = 40.0

    class Side(val mix: MixFeatures, val pulse: PulseFeatures, val durationSec: Double)

    fun plan(out: Side, inc: Side, pairId: String): PulsePlan? {
        val op = out.pulse
        val ip = inc.pulse
        if (!op.hasPulse || ip.pulseSec <= 0.0 || ip.beats.size < 2) return null

        // Outgoing: latest cue in [0.55·dur, dur − 4 s], then wait for the line to end.
        val end = (out.mix.contentEnd.takeIf { it > 0.0 } ?: out.durationSec).coerceAtMost(out.durationSec)
        val cueT = (out.mix.outCues.map { it.t } + out.mix.phrases.toList())
            .filter { it in 0.55 * out.durationSec..end - 4.0 }
            .maxOrNull() ?: max(0.55 * out.durationSec, end - 4.0 - TRANSITION_PULSES / 2 * op.pulseSec)
        val ot = outgoingTiming(op, nearest(op.beats, cueT), end) ?: return null
        val outBeats = DoubleArray(TRANSITION_PULSES + 1) { beatAt(op.beats, op.pulseSec, ot.start + it) * 1000 }

        // Incoming: drum/bass-refined cue, landing on pulse cuePulse of the lock.
        val inCue = incomingCue(inc)
        val inBeats = DoubleArray(TRANSITION_PULSES + 1) {
            beatAt(ip.beats, ip.pulseSec, inCue - ot.cuePulse + it) * 1000
        }

        val outPulseMs = (outBeats.last() - outBeats.first()) / TRANSITION_PULSES
        val rate = (inBeats.last() - inBeats.first()) / (outBeats.last() - outBeats.first())
        val tempo = tempoMaps(outBeats, inBeats, outPulseMs)

        val outVocalFade = outVocalFade(op, outBeats, ot)
        val reveal = vocalReveal(out, inc, outBeats, inBeats, ot, rate, tempo)
        val fill = if (reveal.mode == VocalMode.CUE_SYNC) null else fill(op, ip, outBeats, inBeats, tempo)
        val qa = gridQa(op, ip, outBeats, inBeats, tempo.inTempo)
        val draft = PulsePlan(
            outBeats = outBeats,
            inBeats = inBeats,
            cuePulse = ot.cuePulse,
            inTempo = tempo.inTempo.takeIf { qa.passed },
            outFillTempo = tempo.outFill.takeIf { qa.passed },
            releaseEndTargetMs = tempo.releaseEndTargetMs,
            outVocalFadeBars = outVocalFade,
            inVocal = reveal,
            fill = fill,
            loudnessBars = DoubleArray(0),
            loudnessDb = DoubleArray(0),
            beatmatched = qa.passed,
            fallbackStyle = if (qa.passed) null
            else if (min(out.mix.beatConfidence, inc.mix.beatConfidence) >= PairPlanner.MIN_DJ_CONFIDENCE) MixStyle.FILTER_SWEEP
            else MixStyle.EQUAL_POWER,
            qa = qa,
            pairId = pairId,
        )
        val (bars, db) = loudness(draft, op, ip, tempo.inTempo)
        return draft.copy(loudnessBars = bars, loudnessDb = db)
    }

    // ---- grid helpers ----

    /** Beat [i] of a regular grid, extrapolated by [pulse] past either end. */
    fun beatAt(beats: DoubleArray, pulse: Double, i: Int): Double = when {
        i < 0 -> beats.first() + i * pulse
        i >= beats.size -> beats.last() + (i - beats.size + 1) * pulse
        else -> beats[i]
    }

    fun nearest(beats: DoubleArray, t: Double): Int {
        var i = beats.binarySearch(t)
        if (i >= 0) return i
        i = -i - 1
        return if (i == 0) 0 else if (i >= beats.size) beats.size - 1
        else if (t - beats[i - 1] <= beats[i] - t) i - 1 else i
    }

    /** Mean dB over beat i's span, NaN where uncovered. */
    private fun beatDb(env: Envelope, beats: DoubleArray, pulse: Double, i: Int): Double =
        mean(env.frames(beatAt(beats, pulse, i), beatAt(beats, pulse, i + 1)))

    private fun mean(v: DoubleArray): Double {
        var s = 0.0
        var n = 0
        for (x in v) if (!x.isNaN()) { s += x; n++ }
        return if (n == 0) Double.NaN else s / n
    }

    fun percentile(v: DoubleArray, p: Double): Double {
        val s = v.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return Double.NaN
        val x = p / 100 * (s.size - 1)
        val lo = x.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (x - lo)
    }

    // ---- outgoing_rhythm_timing ----

    class OutTiming(val start: Int, val end: Int, val cuePulse: Int, val quietDb: Double, val lastVocalBeat: Int?)

    fun outgoingTiming(p: PulseFeatures, cue: Int, endSec: Double): OutTiming? {
        val v = { i: Int -> beatDb(p.vocalDb, p.beats, p.pulseSec, i) }
        val ref = percentile(DoubleArray(TRANSITION_PULSES) { v(cue - TRANSITION_PULSES + it) }, 75.0)
        val quiet = if (ref.isNaN()) VOCAL_QUIET_FLOOR_DB else max(VOCAL_QUIET_FLOOR_DB, ref - VOCAL_QUIET_BELOW_REF_DB)
        val firstQuiet = (cue..cue + TRANSITION_PULSES).firstOrNull { v(it).let { d -> d.isNaN() || d <= quiet } } ?: cue
        // The handoff must still be inside the song.
        var end = firstQuiet + POST_VOCAL_HANDOFF_PULSES
        while (end > TRANSITION_PULSES && beatAt(p.beats, p.pulseSec, end) > endSec) end--
        val start = end - TRANSITION_PULSES
        if (start < 0) return null
        val lastVocal = (end downTo start).firstOrNull { v(it).let { d -> !d.isNaN() && d > quiet } }
        val cuePulse = lastVocal?.let { (it + 1 - start).coerceIn(CUE_PULSE, MAX_CUE_PULSE) } ?: CUE_PULSE
        return OutTiming(start, end, cuePulse, quiet, lastVocal)
    }

    // ---- refine_incoming_cue ----

    fun incomingCue(s: Side): Int {
        val p = s.pulse
        val limit = minOf(0.35 * s.durationSec, MAX_ENTRY_S)
        val r = { i: Int -> beatDb(p.rhythmDb, p.beats, p.pulseSec, i) }
        fun score(k: Int): Pair<Double, Double> {
            val after = mean(DoubleArray(CUE_SCORE_WINDOW) { r(k + it) })
            val before = mean(DoubleArray(CUE_SCORE_WINDOW) { r(k - CUE_SCORE_WINDOW + it) })
            val rise = if (before.isNaN()) 0.0 else after - before
            return after to (after + CUE_RISE_WEIGHT * min(rise, CUE_RISE_CAP_DB))
        }
        val original = s.mix.inCues.minByOrNull { it.t }?.t?.takeIf { it <= limit }?.let { nearest(p.beats, it) } ?: 0
        val candidates = (s.mix.inCues.map { it.t } + s.mix.phrases.toList() + s.mix.downbeats.toList())
            .filter { it in 0.0..limit }.map { nearest(p.beats, it) }.distinct()
        if (candidates.isEmpty()) return original
        val p90 = percentile(p.rhythmDb.frames(0.0, limit + CUE_SCORE_WINDOW * p.pulseSec), 90.0)
        val strong = { after: Double -> !after.isNaN() && !p90.isNaN() && after >= p90 - CUE_STRONG_BELOW_P90_DB }
        val (oAfter, oScore) = score(original)
        val best = candidates.map { it to score(it) }.filter { strong(it.second.first) }
            .maxByOrNull { it.second.second } ?: return original
        val gain = best.second.second - oScore
        return if (oScore.isNaN() || !strong(oAfter) || gain >= CUE_MIN_GAIN_DB) best.first else original
    }

    // ---- build_tempo_map / build_outgoing_fill_tempo_map ----

    class Tempo(val inTempo: TempoMap, val outFill: TempoMap, val releaseEndTargetMs: Double)

    fun smoothstep(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    fun tempoMaps(outBeats: DoubleArray, inBeats: DoubleArray, outPulseMs: Double): Tempo {
        val n = TRANSITION_PULSES
        // Rate the incoming leaves the lock at: its last bar's pulse against the outgoing's.
        val matched = (inBeats[n] - inBeats[n - PULSES_PER_BAR]) / (outBeats[n] - outBeats[n - PULSES_PER_BAR])
        val releaseMs = min(TEMPO_RELEASE_SECONDS * 1000, MAX_RELEASE_PULSES * outPulseMs)
        val segMs = releaseMs / RELEASE_CONTROL_POINTS
        val src = ArrayList<Double>(n + RELEASE_CONTROL_POINTS + 2).apply { addAll(inBeats.toList()) }
        val tgt = ArrayList<Double>(src.size).apply { addAll(outBeats.toList()) }
        val outSrc = arrayListOf(outBeats[n])
        val outTgt = arrayListOf(outBeats[n])
        fun rel(k: Int) = matched + (1 - matched) * smoothstep(k.toDouble() / RELEASE_CONTROL_POINTS)
        for (k in 1..RELEASE_CONTROL_POINTS) {
            val r = (rel(k - 1) + rel(k)) / 2
            tgt += tgt.last() + segMs
            src += src.last() + r * segMs
            outTgt += outTgt.last() + segMs
            outSrc += outSrc.last() + r / matched * segMs
        }
        val releaseEnd = tgt.last()
        // The outgoing fill holds the incoming's pulse past the release for as long as it lasts.
        outTgt += releaseEnd + 30_000.0
        outSrc += outSrc.last() + 30_000.0 / matched
        return Tempo(TempoMap(src.toDoubleArray(), tgt.toDoubleArray()), TempoMap(outSrc.toDoubleArray(), outTgt.toDoubleArray()), releaseEnd)
    }

    // ---- vocals ----

    fun outVocalFade(p: PulseFeatures, outBeats: DoubleArray, t: OutTiming): Pair<Double, Double> {
        val beat = t.lastVocalBeat?.let { it + 1 - t.start } ?: t.cuePulse
        val bar = beat.coerceIn(0, TRANSITION_PULSES) / PULSES_PER_BAR.toDouble()
        return bar to bar + 0.5
    }

    /**
     * detect_next_vocal_line_start on 50 ms frames from [fromSec]: a gap of at least
     * VOCAL_LINE_MIN_GAP, then VOCAL_LINE_MIN_ACTIVE of singing with one dropout allowed.
     */
    fun nextLineStart(vocal: Envelope, fromSec: Double, toSec: Double): Double? {
        val hop = Envelope.HOP
        val f = vocal.frames(fromSec, toSec, hop)
        val p75 = percentile(f, 75.0)
        if (p75.isNaN()) return null
        val quiet = max(VOCAL_QUIET_FLOOR_DB, p75 - VOCAL_LINE_BELOW_P75_DB)
        val gap = max(1, (VOCAL_LINE_MIN_GAP / hop).toInt())
        val active = (VOCAL_LINE_MIN_ACTIVE / hop).toInt()
        val on = { i: Int -> !f[i].isNaN() && f[i] > quiet }
        for (i in gap until f.size - active) {
            if (!on(i) || (i - gap until i).any { on(it) }) continue
            var misses = 0
            for (j in i until i + active) if (!on(j)) misses++
            if (misses <= 1) return fromSec + i * hop
        }
        return null
    }

    fun vocalReveal(
        out: Side, inc: Side, outBeats: DoubleArray, inBeats: DoubleArray, t: OutTiming, rate: Double, tempo: Tempo,
    ): VocalReveal {
        val iv = inc.pulse.vocalDb
        val cueSec = inBeats[t.cuePulse] / 1000
        // Cue-synchronous entry: the incoming singer starts on the cue itself.
        val onset = nextLineStart(iv, cueSec - CUE_VOCAL_WINDOW - CUE_VOCAL_MIN_QUIET, cueSec + CUE_VOCAL_WINDOW + VOCAL_LINE_MIN_ACTIVE)
        if (onset != null && abs(onset - cueSec) <= CUE_VOCAL_WINDOW) {
            val before = iv.frames(onset - CUE_VOCAL_MIN_QUIET, onset)
            val p75 = percentile(iv.frames(onset, onset + VOCAL_LINE_MIN_ACTIVE), 75.0)
            val quiet = max(VOCAL_QUIET_FLOOR_DB, p75 - VOCAL_LINE_BELOW_P75_DB)
            val activeBefore = before.count { !it.isNaN() && it > quiet }
            val rise = mean(iv.frames(onset, onset + 0.5)) - mean(iv.frames(onset - 0.5, onset))
            val outAtCue = beatDb(out.pulse.vocalDb, out.pulse.beats, out.pulse.pulseSec, t.start + t.cuePulse)
            val outQuiet = outAtCue.isNaN() || outAtCue <= t.quietDb
            if (activeBefore <= 1 && rise >= CUE_VOCAL_MIN_RISE_DB && outQuiet &&
                t.cuePulse.toDouble() / TRANSITION_PULSES >= CUE_VOCAL_MIN_SHARE && rate in MIN_CUE_RATE..MAX_CUE_RATE
            ) {
                val ms = onset * 1000
                return VocalReveal(VocalMode.CUE_SYNC, fadeStartMs = ms - CUE_VOCAL_FADE_MS, lineStartMs = ms)
            }
        }
        // Two-stage reveal after the tempo release.
        val releaseEndSrc = tempo.inTempo.sourceAt(tempo.releaseEndTargetMs) / 1000
        val line = nextLineStart(iv, releaseEndSrc - 3.0, releaseEndSrc + 30.0) ?: return VocalReveal(VocalMode.NONE)
        val wait = line - releaseEndSrc
        val lead = min(max(wait, 0.1), (0.55 + 0.65 * wait).coerceIn(0.55, 1.60))
        val gain = 0.32 + 0.20 * min(1.0, lead / 1.60)
        val tail = (0.45 + 0.25 * wait).coerceIn(0.45, 0.80)
        return VocalReveal(VocalMode.TWO_STAGE, (line - lead) * 1000, line * 1000, gain, tail * 1000)
    }

    // ---- fill ----

    fun fill(op: PulseFeatures, ip: PulseFeatures, outBeats: DoubleArray, inBeats: DoubleArray, tempo: Tempo): Fill {
        val outDb = mean(op.rmsDb.frames(outBeats.first() / 1000, outBeats.last() / 1000))
        val inDb = mean(ip.rmsDb.frames(inBeats.first() / 1000, inBeats.last() / 1000))
        val ratio = if (outDb.isNaN() || inDb.isNaN()) 1.0 else 10.0.pow((inDb - outDb) / 20)
        val level = (ratio * 10.0.pow(FILL_DB / 20)).coerceIn(MIN_FILL, MAX_FILL)
        return Fill(level, tempo.outFill.sourceAt(tempo.releaseEndTargetMs))
    }

    // ---- drum_grid_qa / beat_alignment_qa ----

    /** |phase| of each onset against a grid subdivided [sub] times, in pulses. */
    private fun phases(onsets: DoubleArray, gridMs: DoubleArray, sub: Int, map: (Double) -> Double): DoubleArray =
        onsets.map { map(it * 1000) }.filter { it >= gridMs.first() && it < gridMs.last() }.map { t ->
            var i = gridMs.binarySearch(t)
            if (i < 0) i = -i - 2
            i = i.coerceIn(0, gridMs.size - 2)
            val pulse = gridMs[i + 1] - gridMs[i]
            val x = (t - gridMs[i]) / pulse * sub
            (x - Math.round(x)) / sub
        }.toDoubleArray()

    fun gridQa(op: PulseFeatures, ip: PulseFeatures, outBeats: DoubleArray, inBeats: DoubleArray, map: TempoMap): GridQa {
        val outPh = phases(op.drumOnsets, outBeats, op.pulseSubdivision) { it }
        val inPh = phases(ip.drumOnsets, inBeats, ip.pulseSubdivision) { it }
        val rel = phases(ip.drumOnsets, outBeats, 1) { map.targetAt(it) }
        val outP90 = percentile(outPh.map(::abs).toDoubleArray(), 90.0)
        val inP90 = percentile(inPh.map(::abs).toDoubleArray(), 90.0)
        val relMed = percentile(rel, 50.0)
        val rates = map.sourceMs.indices.drop(1).map {
            (map.sourceMs[it] - map.sourceMs[it - 1]) / (map.targetMs[it] - map.targetMs[it - 1])
        }
        val rateOk = rates.all { it in MIN_MAP_RATE..MAX_MAP_RATE }
        val reason = when {
            !rateOk -> "tempo map rate outside $MIN_MAP_RATE..$MAX_MAP_RATE"
            outPh.size < QA_MIN_ONSETS -> "outgoing: too few drum onsets (${outPh.size})"
            inPh.size < QA_MIN_ONSETS -> "incoming: too few drum onsets (${inPh.size})"
            outP90 > QA_MAX_P90_PHASE -> "outgoing grid p90 phase %.2f > $QA_MAX_P90_PHASE".format(outP90)
            inP90 > QA_MAX_P90_PHASE -> "incoming grid p90 phase %.2f > $QA_MAX_P90_PHASE".format(inP90)
            abs(relMed) > QA_MAX_RELATIVE_PHASE -> "relative phase %.2f > $QA_MAX_RELATIVE_PHASE".format(relMed)
            else -> null
        }
        return GridQa(outP90, inP90, relMed, rateOk, reason)
    }

    // ---- smooth_loudness_control ----

    /** Per-pulse correction (bars, dB) so the predicted mix stays on a line from outgoing to incoming level. */
    fun loudness(plan: PulsePlan, op: PulseFeatures, ip: PulseFeatures, map: TempoMap): Pair<DoubleArray, DoubleArray> {
        val n = TRANSITION_PULSES
        val bars = DoubleArray(n + 1) { it / PULSES_PER_BAR.toDouble() }
        val outRef = mean(op.rmsDb.frames(plan.outBeats.first() / 1000 - 8.0, plan.outBeats.first() / 1000))
        val inRef = mean(ip.rmsDb.frames(plan.inBeats.last() / 1000, plan.inBeats.last() / 1000 + 8.0))
        if (outRef.isNaN() || inRef.isNaN()) return bars to DoubleArray(n + 1)
        val raw = DoubleArray(n + 1) { i ->
            val o = op.rmsDb.at(plan.outBeats[i] / 1000)
            val inn = ip.rmsDb.at(plan.inBeats[i] / 1000)
            val po = if (o.isNaN()) 0.0 else MixAutomation.power(MixAutomation.outgoing(plan, bars[i], plan.outBeats[i])) * 10.0.pow(o / 10)
            val pi = if (inn.isNaN()) 0.0 else MixAutomation.power(MixAutomation.incoming(plan, bars[i], plan.inBeats[i])) * 10.0.pow(inn / 10)
            val predicted = 10 * kotlin.math.log10(po + pi + 1e-10)
            val target = outRef + (inRef - outRef) * i / n
            (target - predicted).coerceIn(LOUDNESS_MIN_DB, LOUDNESS_MAX_DB)
        }
        // Smooth (≈ σ 0.2 s at dance tempos) and taper both ends to 0.
        val smooth = DoubleArray(n + 1) { i ->
            0.25 * raw[max(0, i - 1)] + 0.5 * raw[i] + 0.25 * raw[min(n, i + 1)]
        }
        smooth[0] = 0.0
        smooth[n] = 0.0
        smooth[1] *= 0.5
        smooth[n - 1] *= 0.5
        return bars to smooth
    }

    // ---- report ----

    fun report(plan: PulsePlan, outId: String, inId: String): JSONObject = JSONObject().apply {
        fun arr(v: DoubleArray) = JSONArray().apply { v.forEach { put(Math.round(it * 10) / 10.0) } }
        put("pairId", plan.pairId)
        put("out", outId)
        put("in", inId)
        put("beatmatched", plan.beatmatched)
        put("fallback", plan.fallbackStyle?.name ?: JSONObject.NULL)
        put("cuePulse", plan.cuePulse)
        put("outBeats", arr(plan.outBeats))
        put("inBeats", arr(plan.inBeats))
        put("releaseEndTargetMs", plan.releaseEndTargetMs)
        plan.inTempo?.let { put("inTempo", JSONObject().put("src", arr(it.sourceMs)).put("tgt", arr(it.targetMs))) }
        put("outVocalFadeBars", JSONArray().put(plan.outVocalFadeBars.first).put(plan.outVocalFadeBars.second))
        put("inVocal", JSONObject().put("mode", plan.inVocal.mode.name).put("fadeStartMs", plan.inVocal.fadeStartMs)
            .put("lineStartMs", plan.inVocal.lineStartMs).put("lineGain", plan.inVocal.lineGain).put("tailMs", plan.inVocal.tailMs))
        plan.fill?.let { put("fill", JSONObject().put("level", it.level).put("endMs", it.endMs)) }
        put("loudnessDb", arr(plan.loudnessDb))
        put("qa", JSONObject().apply {
            fun num(v: Double): Any = if (v.isNaN()) JSONObject.NULL else v
            put("outP90Phase", num(plan.qa.outP90Phase))
            put("inP90Phase", num(plan.qa.inP90Phase))
            put("relativePhase", num(plan.qa.relativePhase))
            put("rateOk", plan.qa.rateOk)
            put("passed", plan.qa.passed)
            put("reason", plan.qa.reason ?: JSONObject.NULL)
        })
    }

}
