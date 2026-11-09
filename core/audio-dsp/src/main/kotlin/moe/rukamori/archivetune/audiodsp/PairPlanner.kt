package moe.rukamori.archivetune.audiodsp

import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

data class Cue(val t: Double, val score: Double, val type: String)

/**
 * The per-track features the pair planner reads, out of the native analyzer's JSON. A streamed
 * track is analysed as a head window plus a tail window; the tail's times are shifted by its
 * offset so both read on the track's own clock.
 */
class MixFeatures(
    val bpm: Double,
    val beatConfidence: Double,
    val key: String,
    val contentEnd: Double,
    val downbeats: DoubleArray,
    val phrases: DoubleArray,
    /** Parallel to [energy]: the analyzer's ~1 s curve grid. */
    val curveT: DoubleArray,
    val energy: DoubleArray,
    val vocal: DoubleArray,
    val inCues: List<Cue>,
    val outCues: List<Cue>,
    /** How far into the track the features reach; past it nothing is known. */
    val coveredFrom: Double,
    /** The ending's own tempo when a model tracked it there; the outgoing side reads this. */
    val bpmTail: Double = 0.0,
) {
    val outBpm: Double get() = bpmTail.takeIf { it > 0.0 } ?: bpm

    val hasGrid: Boolean get() = bpm > 0.0 && beatConfidence >= MIN_BEAT_CONFIDENCE && downbeats.size >= 4

    private fun at(t: Double, values: DoubleArray): Double {
        if (curveT.isEmpty()) return 0.0
        var i = curveT.binarySearch(t)
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        return values[i.coerceAtMost(values.size - 1)]
    }

    fun energyAt(t: Double) = at(t, energy)

    /** Share of the curve frames in [from, to) that carry singing. */
    fun vocalShare(from: Double, to: Double): Double {
        var n = 0
        var sung = 0
        for (i in curveT.indices) {
            if (curveT[i] < from || curveT[i] >= to) continue
            n++
            if (vocal[i] >= VOCAL_ON) sung++
        }
        return if (n == 0) 0.0 else sung.toDouble() / n
    }

    fun singingAt(t: Double) = curveT.isNotEmpty() && at(t, vocal) >= VOCAL_ON

    companion object {
        const val MIN_BEAT_CONFIDENCE = 0.45
        const val VOCAL_ON = 0.5

        /** The model vocal scale's "singing" line (open-unmix band mask), as BitChord gates it. */
        const val MODEL_VOCAL_ON = 0.25

        /** Maps a model mask value onto this file's scale, so [VOCAL_ON] means the same thing. */
        fun modelToVocal(m: Double): Double =
            if (m >= MODEL_VOCAL_ON) VOCAL_ON + (1 - VOCAL_ON) * (m - MODEL_VOCAL_ON) / (1 - MODEL_VOCAL_ON)
            else VOCAL_ON * m / MODEL_VOCAL_ON

        /**
         * [modelJson] is the ONNX pass's output, all times on the track clock:
         * `{"headBpm":d,"tailBpm":d,"conf":d,"downbeats":[..],"vocal":[{"start":s,"fps":f,"v":[..]}..]}`.
         * Where present it replaces the DSP grid and, inside its windows, the DSP vocal guess.
         */
        fun parse(headJson: String, tailJson: String = "", tailOffset: Double = 0.0, modelJson: String = ""): MixFeatures? =
            parseDsp(headJson, tailJson, tailOffset)?.let { dsp -> withModel(dsp, modelJson) }

        private fun withModel(f: MixFeatures, modelJson: String): MixFeatures = runCatching {
            if (modelJson.isBlank()) return f
            val m = JSONObject(modelJson)
            val vocal = f.vocal.copyOf()
            m.optJSONArray("vocal")?.let { windows ->
                for (w in 0 until windows.length()) {
                    val win = windows.getJSONObject(w)
                    val start = win.optDouble("start")
                    val fps = win.optDouble("fps")
                    val v = win.optJSONArray("v") ?: continue
                    if (fps <= 0.0) continue
                    for (i in f.curveT.indices) {
                        val frame = ((f.curveT[i] - start) * fps).toInt()
                        if (frame in 0 until v.length()) vocal[i] = modelToVocal(v.optDouble(frame))
                    }
                }
            }
            val downbeats = m.optJSONArray("downbeats")?.let { a -> DoubleArray(a.length()) { a.optDouble(it) } }
            val conf = m.optDouble("conf", 0.0)
            val gridOk = downbeats != null && downbeats.size >= 4 && conf >= MIN_BEAT_CONFIDENCE
            MixFeatures(
                bpm = m.optDouble("headBpm", 0.0).takeIf { gridOk && it > 0.0 } ?: f.bpm,
                beatConfidence = if (gridOk) conf else f.beatConfidence,
                key = f.key,
                contentEnd = f.contentEnd,
                downbeats = if (gridOk) downbeats!! else f.downbeats,
                phrases = f.phrases,
                curveT = f.curveT,
                energy = f.energy,
                vocal = vocal,
                inCues = f.inCues,
                outCues = f.outCues,
                coveredFrom = f.coveredFrom,
                bpmTail = m.optDouble("tailBpm", 0.0).takeIf { gridOk && it > 0.0 } ?: 0.0,
            )
        }.getOrDefault(f)

        private fun parseDsp(headJson: String, tailJson: String, tailOffset: Double): MixFeatures? = runCatching {
            if (headJson.isBlank()) return null
            val head = JSONObject(headJson)
            val tail = tailJson.takeIf { it.isNotBlank() }?.let(::JSONObject)

            fun nums(o: JSONObject, name: String, shift: Double) =
                o.optJSONArray(name)?.let { a -> DoubleArray(a.length()) { a.optDouble(it) + shift } } ?: DoubleArray(0)
            fun curve(o: JSONObject, shift: Double): Pair<DoubleArray, DoubleArray> {
                val a = o.optJSONArray("energyCurve") ?: JSONArray()
                return DoubleArray(a.length()) { a.getJSONObject(it).optDouble("t") + shift } to
                    DoubleArray(a.length()) { a.getJSONObject(it).optDouble("e") }
            }
            fun cues(o: JSONObject, name: String, shift: Double): List<Cue> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).map {
                    a.getJSONObject(it).let { c -> Cue(c.optDouble("t") + shift, c.optDouble("s"), c.optString("y")) }
                }
            }
            // Head entries up to where the tail starts, then the tail's own.
            fun join(h: DoubleArray, t: DoubleArray?) =
                if (t == null) h else h.filter { it < tailOffset }.toDoubleArray() + t

            val (ht, he) = curve(head, 0.0)
            val hv = nums(head, "vocalActivityMask", 0.0)
            var curveT = ht
            var energy = he
            var vocal = hv
            if (tail != null) {
                val (tt, te) = curve(tail, tailOffset)
                val tv = nums(tail, "vocalActivityMask", 0.0)
                val keep = ht.indices.filter { ht[it] < tailOffset }
                curveT = keep.map { ht[it] }.toDoubleArray() + tt
                energy = keep.map { he[it] }.toDoubleArray() + te
                vocal = keep.map { hv.getOrElse(it) { 0.0 } }.toDoubleArray() + DoubleArray(tt.size) { tv.getOrElse(it) { 0.0 } }
            }
            val src = tail ?: head
            val shift = if (tail != null) tailOffset else 0.0
            MixFeatures(
                // The head's tempo: it saw a longer, steadier span than the tail's 45 s.
                bpm = head.optDouble("bpm", 0.0).takeIf { it > 0.0 } ?: src.optDouble("bpm", 0.0),
                beatConfidence = head.optDouble("beatConfidence", 0.0),
                key = head.optString("key", ""),
                contentEnd = src.optDouble("contentEndTime", 0.0) + shift,
                downbeats = join(nums(head, "downbeats", 0.0), tail?.let { nums(it, "downbeats", tailOffset) }),
                phrases = join(nums(head, "phraseBoundaries", 0.0), tail?.let { nums(it, "phraseBoundaries", tailOffset) }),
                curveT = curveT,
                energy = energy,
                vocal = vocal,
                inCues = cues(head, "mixInCandidates", 0.0),
                outCues = cues(src, "mixOutCandidates", shift),
                coveredFrom = if (tail != null) tailOffset else 0.0,
            )
        }.getOrNull()
    }
}

/**
 * Chooses how two specific songs meet, from per-track features alone: which exit of the outgoing
 * song, which entrance of the incoming one, whether they beat-match, and how long they overlap.
 * Every exit/entrance pair is scored, after automix-engine's rules: wait for the singer to finish
 * the line, never stack two vocals, land on phrase and bar lines, keep the energy level, and
 * shorten the overlap when the keys clash.
 */
object PairPlanner {
    /** Bungee stays clean within this much stretch; beyond it the songs are faded, not matched. */
    const val MAX_STRETCH = 0.08
    const val BARS_MATCHED = 8
    const val BARS_KEY_CLASH = 4
    const val MIN_FADE_S = 4.0
    const val MAX_FADE_S = 20.0
    /** How far before the end the outgoing song may be left, at most. */
    const val EXIT_WINDOW_S = 40.0
    const val ENTRY_WINDOW_S = 40.0

    data class Choice(val exit: Double, val entry: Double, val fade: Double, val score: Double)

    fun plan(
        out: MixFeatures,
        inc: MixFeatures,
        durationMs: Long,
        baseFadeMs: Long,
        bassSwap: Boolean,
    ): AutomixPlan? {
        val duration = durationMs / 1000.0
        // A head-only analysis of a streamed song knows nothing about its ending.
        if ((out.curveT.lastOrNull() ?: 0.0) < duration * 0.8) return null
        val end =out.contentEnd.takeIf { it > 0.0 && it <= duration + 0.5 } ?: duration
        if (end <= MIN_FADE_S * 2) return null

        val ratio = if (out.outBpm > 0 && inc.bpm > 0) TransitionPlanner.normalizedTempoRatio(out.outBpm, inc.bpm) else 0.0
        val match = out.hasGrid && inc.hasGrid && abs(1.0 - ratio) <= MAX_STRETCH
        // Incoming playback speed that lands its beats on the outgoing ones.
        val speed = if (match) 1.0 / ratio else 1.0
        val keysFit = camelotCompatible(out.key, inc.key)

        val fade = if (match) {
            val bar = 4 * 60.0 / out.outBpm
            (bar * if (keysFit != false) BARS_MATCHED else BARS_KEY_CLASH).coerceIn(MIN_FADE_S, MAX_FADE_S)
        } else {
            (baseFadeMs / 1000.0).coerceIn(MIN_FADE_S, MAX_FADE_S)
        }

        val choice = choose(out, inc, end, fade, speed, match) ?: return null
        return AutomixPlan(
            triggerOffsetMs = durationMs - (choice.exit * 1000).roundToLong(),
            durationMs = (choice.fade * 1000).roundToLong(),
            incomingStartMs = (choice.entry * 1000).roundToLong(),
            enableBassSwap = bassSwap,
            triggerAtMs = (choice.exit * 1000).roundToLong(),
            prepareAheadMs = maxOf((choice.fade * 1000).roundToLong() + 2000L, 8000L),
            tier = if (match) TransitionTier.SMART_BEATMATCH else TransitionTier.PLAIN_CROSSFADE,
            incomingTempoRatio = speed.toFloat(),
        )
    }

    fun choose(out: MixFeatures, inc: MixFeatures, end: Double, fade: Double, speed: Double, match: Boolean): Choice? {
        val earliest = maxOf(end - EXIT_WINDOW_S, end * TransitionPlanner.OUTRO_MIN_TRACK_FRACTION, out.coveredFrom)
        val latest = end - fade
        if (latest <= earliest) return null

        // Exits: the analyzer's cues, phrase lines, and (when on a grid) every downbeat in the window.
        val exits = buildList {
            // An outro start is where to begin leaving; a cliff or the content end is where the
            // song is already gone, so the overlap has to be over by then.
            out.outCues.forEach { add((if (it.type == "outro_start") it.t else it.t - fade) to it.score) }
            out.phrases.forEach { add(it to 0.7) }
            if (match) out.downbeats.forEach { add(it to 0.5) }
            add(latest to 0.4)
        }.filter { it.first in earliest..latest }
            .map { (t, s) -> (if (match) snapDown(out.downbeats, t) else t) to s }
            .filter { it.first in earliest..latest }
            .distinctBy { (it.first * 10).roundToLong() }

        // Entrances: its cues, and on a grid its early downbeats; the very top as a fallback.
        val entries = buildList {
            add(0.0 to 0.5)
            inc.inCues.forEach { add(it.t to it.score) }
            if (match) inc.downbeats.filter { it < ENTRY_WINDOW_S }.forEach { add(it to 0.4) }
        }.filter { it.first in 0.0..ENTRY_WINDOW_S }
            .distinctBy { (it.first * 10).roundToLong() }

        var best: Choice? = null
        for ((exit, exitScore) in exits) for ((entry, entryScore) in entries) {
            val s = score(out, inc, end, exit, exitScore, entry, entryScore, fade, speed, match)
            if (best == null || s > best.score) best = Choice(exit, entry, fade, s)
        }
        return best
    }

    private fun score(
        out: MixFeatures, inc: MixFeatures, end: Double,
        exit: Double, exitScore: Double, entry: Double, entryScore: Double,
        fade: Double, speed: Double, match: Boolean,
    ): Double {
        val outVocal = out.vocalShare(exit + fade * 0.3, exit + fade)
        // The incoming song covers fade * speed of its own clock during the overlap.
        val incVocal = inc.vocalShare(entry, entry + fade * speed)
        var s = exitScore + entryScore
        s -= 2.0 * outVocal * incVocal // two singers at once
        if (out.singingAt(exit + fade * 0.5)) s -= 0.6 // the line is cut by the fade
        if (inc.singingAt(entry) && out.singingAt(exit)) s -= 0.4
        if (out.phrases.any { abs(it - exit) < 0.6 }) s += 0.25
        if (match && inc.phrases.any { abs(it - entry) < 0.6 }) s += 0.15
        s -= 0.4 * abs(out.energyAt(exit + fade) - inc.energyAt(entry + fade * speed)).coerceAtMost(1.0)
        s -= 0.5 * ((end - fade - exit) / EXIT_WINDOW_S) // leaving early throws music away
        s -= 0.3 * (entry / ENTRY_WINDOW_S) // so does skipping the intro
        return s
    }

    /** The downbeat at or before [t], or [t] itself when there is none near. */
    fun snapDown(downbeats: DoubleArray, t: Double): Double {
        val d = downbeats.lastOrNull { it <= t + 0.05 } ?: return t
        return if (t - d < 4.0) d else t
    }

    /** True for neighbours on the Camelot wheel, false for a clash, null when either key is unknown. */
    fun camelotCompatible(a: String, b: String): Boolean? {
        val ca = camelot(a) ?: return null
        val cb = camelot(b) ?: return null
        if (ca.first == cb.first) return true // same key, or relative major/minor
        val step = ((ca.first - cb.first) % 12 + 12) % 12
        return ca.second == cb.second && (step == 1 || step == 11)
    }

    private val ROOTS = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    /** (wheel number 0-11, isMinor) for the analyzer's "E♭ minor" style names. */
    fun camelot(key: String): Pair<Int, Boolean>? {
        val parts = key.trim().split(' ')
        if (parts.size != 2) return null
        val root = ROOTS.indexOf(parts[0]).takeIf { it >= 0 } ?: return null
        val minor = when (parts[1]) { "minor" -> true; "major" -> false; else -> return null }
        val majorRoot = if (minor) (root + 3) % 12 else root
        return (majorRoot * 7) % 12 to minor
    }
}
