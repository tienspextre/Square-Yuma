package moe.rukamori.archivetune.audiodsp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairPlannerTest {

    /** A synthetic analyzer JSON: a beat grid, a 1 s curve, singing where [sings] says. */
    private fun track(
        length: Double, bpm: Double, key: String, sings: (Double) -> Boolean,
        outCues: List<Pair<Double, String>> = emptyList(), inCues: List<Double> = listOf(0.0),
        confidence: Double = 0.9, low: Double = 0.2,
    ): String {
        val bar = 4 * 60.0 / bpm
        val curve = JSONArray()
        val lowCurve = JSONArray()
        val mask = JSONArray()
        var t = 0.0
        while (t < length) {
            lowCurve.put(JSONObject().put("t", t).put("e", low))
            curve.put(JSONObject().put("t", t).put("e", 0.8)); mask.put(if (sings(t)) 0.9 else 0.1); t += 1.0
        }
        return JSONObject()
            .put("bpm", bpm).put("beatConfidence", confidence).put("key", key).put("contentEndTime", length)
            .put("downbeats", JSONArray(generateSequence(0.0) { it + bar }.takeWhile { it < length }.toList()))
            .put("phraseBoundaries", JSONArray(generateSequence(0.0) { it + bar * 8 }.takeWhile { it < length }.toList()))
            .put("energyCurve", curve).put("lowEnergyCurve", lowCurve).put("vocalActivityMask", mask)
            .put("mixOutCandidates", JSONArray(outCues.map { JSONObject().put("t", it.first).put("s", 0.9).put("y", it.second) }))
            .put("mixInCandidates", JSONArray(inCues.map { JSONObject().put("t", it).put("s", 0.8).put("y", "pickup") }))
            .toString()
    }

    @Test
    fun beatmatchesAndWaitsForTheSingerToFinish() {
        // Sung until 168 s, then an instrumental outro.
        val out = MixFeatures.parse(track(200.0, 120.0, "A minor", { it < 168.0 }, listOf(176.0 to "outro_start", 198.0 to "content_end")))!!
        // The next singer comes in at 20 s.
        val inc = MixFeatures.parse(track(240.0, 124.0, "C major", { it >= 20.0 }))!!

        val plan = PairPlanner.plan(out, inc, 200_000L, 6000L, bassSwap = true)!!
        assertEquals(TransitionTier.SMART_BEATMATCH, plan.tier)
        assertEquals(120f / 124f, plan.incomingTempoRatio, 1e-4f)
        assertEquals(16_000L, plan.durationMs) // 8 bars at 120 BPM, keys are relatives
        val exit = plan.triggerAtMs!! / 1000.0
        assertTrue("exit $exit must wait for the line to end", exit >= 166.0)
        assertEquals("exit on a downbeat", 0.0, exit % 2.0, 1e-6)
        // Nobody sings on top of anybody.
        assertEquals(0.0, out.vocalShare(exit + 4.8, exit + 16.0) * inc.vocalShare(plan.incomingStartMs / 1000.0, plan.incomingStartMs / 1000.0 + 16.0), 1e-9)
    }

    @Test
    fun keyClashShortensTheOverlap() {
        val out = MixFeatures.parse(track(200.0, 120.0, "C major", { false }))!!
        val inc = MixFeatures.parse(track(200.0, 120.0, "F♯ major", { false }))!!
        assertEquals(8_000L, PairPlanner.plan(out, inc, 200_000L, 6000L, true)!!.durationMs)
    }

    @Test
    fun farTemposGetAFilterSweepInsteadOfAStretch() {
        val out = MixFeatures.parse(track(200.0, 90.0, "C major", { false }))!!
        val inc = MixFeatures.parse(track(200.0, 128.0, "C major", { false }))!!
        val plan = PairPlanner.plan(out, inc, 200_000L, 6000L, true)!!
        assertEquals(TransitionTier.PLAIN_CROSSFADE, plan.tier)
        assertEquals(MixStyle.FILTER_SWEEP, plan.style)
        assertEquals(1f, plan.incomingTempoRatio)
        assertEquals(10_667L, plan.durationMs) // 16 beats at 90 BPM
        assertEquals(0.5, plan.swapAt, 1e-9) // beat 8 of 16
    }

    @Test
    fun twoKicksAtTheJoinGetABassHandoff() {
        val out = MixFeatures.parse(track(200.0, 90.0, "C major", { false }, low = 0.9))!!
        val inc = MixFeatures.parse(track(200.0, 128.0, "C major", { false }, low = 0.9))!!
        val plan = PairPlanner.plan(out, inc, 200_000L, 6000L, true)!!
        assertEquals(MixStyle.BASS_HANDOFF, plan.style)
        assertEquals(7.0 / 16.0, plan.swapAt, 1e-9) // 0.43 rounded onto beat 7
    }

    @Test
    fun noGridMeansEqualPowerWithVocalSeparation() {
        val out = MixFeatures.parse(track(200.0, 120.0, "C major", { true }, confidence = 0.1))!!
        val inc = MixFeatures.parse(track(200.0, 120.0, "C major", { true }, confidence = 0.1))!!
        val plan = PairPlanner.plan(out, inc, 200_000L, 6000L, true)!!
        assertEquals(MixStyle.EQUAL_POWER, plan.style)
        assertEquals(6_000L, plan.durationMs)
        assertTrue(plan.vocalClash > 0.9)
        val (o, i) = MixStyles.shape(MixStyle.EQUAL_POWER, 0.5f, plan.swapAt, plan.swapWidth, plan.vocalClash)
        assertTrue(o.lowPassHz < 6000.0 && i.highPassHz > 30.0)
    }

    @Test
    fun sweepShapesHandTheLowEndOverOnce() {
        val (o0, i0) = MixStyles.shape(MixStyle.FILTER_SWEEP, 0.1f, 0.5, 0.03, 0.0)
        assertEquals(0.0, o0.bassDb, 1e-9); assertEquals(MixStyles.CUT_DB, i0.bassDb, 1e-9)
        assertTrue(i0.highPassHz > 500.0)
        val (o1, i1) = MixStyles.shape(MixStyle.FILTER_SWEEP, 0.9f, 0.5, 0.03, 0.0)
        assertEquals(MixStyles.CUT_DB, o1.bassDb, 1e-9); assertEquals(0.0, i1.bassDb, 1e-9)
        assertTrue(o1.lowPassHz < 500.0 && i1.highPassHz <= MixStyles.OFF_HZ + 1e-6)
        assertTrue(o1.gain < 0.3f && i1.gain > 0.9f)
    }

    @Test
    fun liveSpokenAndAlbumRules() {
        assertTrue(TransitionRules.isSpokenOrLive("Song (Live at Wembley)", "", ""))
        assertTrue(TransitionRules.isSpokenOrLive("Intro", "", "Live: At The Hall"))
        assertTrue(TransitionRules.isSpokenOrLive("Episode 3", "My Podcast", ""))
        assertTrue(!TransitionRules.isSpokenOrLive("Live It Up", "", ""))
        val album = listOf("A" to "X", "A" to "X", "A" to "X")
        assertTrue(TransitionRules.isAlbumPlaythrough(album, shuffled = false))
        assertTrue(!TransitionRules.isAlbumPlaythrough(album, shuffled = true))
        assertTrue(!TransitionRules.isAlbumPlaythrough(album + ("B" to "X"), shuffled = false))
    }

    @Test
    fun headOnlyAnalysisOfALongSongIsRefused() {
        val head = MixFeatures.parse(track(30.0, 120.0, "C major", { false }))!!
        assertNull(PairPlanner.plan(head, head, 200_000L, 6000L, true))
    }

    @Test
    fun tailWindowIsShiftedOntoTheTrackClock() {
        val f = MixFeatures.parse(
            track(30.0, 120.0, "C major", { false }),
            track(45.0, 120.0, "C major", { it > 40.0 }, listOf(10.0 to "outro_start")),
            tailOffset = 155.0,
        )!!
        assertEquals(165.0, f.outCues.single().t, 1e-9)
        assertTrue(f.singingAt(197.0))
        assertTrue(!f.singingAt(20.0))
        assertEquals(200.0, f.contentEnd, 1e-9)
    }

    @Test
    fun camelotWheel() {
        assertEquals(true, PairPlanner.camelotCompatible("A minor", "C major"))
        assertEquals(true, PairPlanner.camelotCompatible("C major", "G major"))
        assertEquals(false, PairPlanner.camelotCompatible("C major", "F♯ major"))
        assertNull(PairPlanner.camelotCompatible("", "C major"))
    }

    @Test
    fun modelPassReplacesGridAndVocalInsideItsWindow() {
        val dsp = track(200.0, 120.0, "C major", { false })
        val model = JSONObject().put("headBpm", 126.0).put("tailBpm", 124.0).put("conf", 0.9)
            .put("downbeats", JSONArray(listOf(1.0, 3.0, 5.0, 7.0, 190.0)))
            .put("vocal", JSONArray().put(JSONObject().put("start", 180.0).put("fps", 2.0).put("v", JSONArray(List(20) { 0.6 }))))
            .toString()
        val f = MixFeatures.parse(dsp, modelJson = model)!!
        assertEquals(126.0, f.bpm, 1e-9)
        assertEquals(124.0, f.outBpm, 1e-9)
        assertEquals(5, f.downbeats.size)
        assertTrue(f.singingAt(185.0))
        assertTrue(!f.singingAt(195.0))
        assertEquals(0.5, MixFeatures.modelToVocal(0.25), 1e-9)
    }
}
