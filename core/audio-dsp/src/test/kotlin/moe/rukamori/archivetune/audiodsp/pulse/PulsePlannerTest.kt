package moe.rukamori.archivetune.audiodsp.pulse

import moe.rukamori.archivetune.audiodsp.Cue
import moe.rukamori.archivetune.audiodsp.MixFeatures
import moe.rukamori.archivetune.audiodsp.MixStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulsePlannerTest {

    private fun env(len: Double, f: (Double) -> Double) =
        Envelope(listOf(Envelope.Seg(0.0, 0.05, DoubleArray((len / 0.05).toInt()) { f(it * 0.05) })))

    /** A steady track: drums on every beat, singing where [sings] says. */
    private fun side(
        len: Double, bpm: Double, sings: (Double) -> Boolean,
        outCues: List<Double> = emptyList(), inCues: List<Double> = emptyList(), drumShift: Double = 0.0,
    ): PulsePlanner.Side {
        val pulse = 60.0 / bpm
        val beats = generateSequence(0.0) { it + pulse }.takeWhile { it < len }.toList().toDoubleArray()
        val mix = MixFeatures(
            bpm = bpm, beatConfidence = 0.9, key = "", contentEnd = len,
            downbeats = DoubleArray(0), phrases = DoubleArray(0),
            curveT = DoubleArray(0), energy = DoubleArray(0), vocal = DoubleArray(0),
            inCues = inCues.map { Cue(it, 0.8, "pickup") }, outCues = outCues.map { Cue(it, 0.9, "outro") },
            coveredFrom = 0.0,
        )
        val p = PulseFeatures(
            pulseSec = pulse, pulseConfidence = 0.9, pulseSubdivision = 1, beats = beats,
            drumOnsets = beats.map { it + drumShift * pulse }.toDoubleArray(),
            rmsDb = env(len) { -15.0 }, rhythmDb = env(len) { -20.0 },
            vocalDb = env(len) { if (sings(it)) -20.0 else -60.0 }, vocalFromModel = true,
        )
        return PulsePlanner.Side(mix, p, len)
    }

    private val outgoing = side(200.0, 120.0, { it < 168.0 }, outCues = listOf(176.0))

    @Test
    fun locks32PulsesAndReleasesTempoToOne() {
        val inc = side(240.0, 124.0, { it >= 40.0 }, inCues = listOf(16.0))
        val plan = PulsePlanner.plan(outgoing, inc, "p")!!
        assertTrue(plan.qa.reason ?: "", plan.beatmatched)
        assertEquals(33, plan.outBeats.size)
        assertEquals(16, plan.cuePulse)
        // The incoming cue beat lands on pulse cuePulse, every one of the 33 beats pinned.
        assertEquals(nearestBeatMs(inc, 16.0), plan.inBeats[plan.cuePulse], 1e-6)
        val map = plan.inTempo!!
        for (i in 0..32) assertEquals(plan.outBeats[i], map.targetAt(plan.inBeats[i]), 1e-6)
        assertEquals(120.0 / 124.0, map.rateAt(plan.inBeats[10]), 1e-6)
        // 6.48 s release ending on 1.0.
        assertEquals(plan.outBeats.last() + 6480.0, plan.releaseEndTargetMs, 1e-6)
        assertEquals(1.0, map.rateAt(map.sourceAt(plan.releaseEndTargetMs) + 1.0), 1e-9)
        val lastSegment = map.rateAt(map.sourceAt(plan.releaseEndTargetMs - 100.0))
        assertTrue("release eases into 1.0, got $lastSegment", lastSegment < 1.01)
        assertEquals(VocalMode.TWO_STAGE, plan.inVocal.mode)
        assertEquals(40_000.0, plan.inVocal.lineStartMs, 50.0) // one 50 ms frame
        assertNotNull(plan.fill)
        assertTrue(plan.fill!!.level in PulsePlanner.MIN_FILL..PulsePlanner.MAX_FILL)
    }

    @Test
    fun waitsForTheOutgoingLineToEnd() {
        val out = side(200.0, 120.0, { it < 180.0 }, outCues = listOf(176.0))
        val plan = PulsePlanner.plan(out, side(240.0, 120.0, { false }, inCues = listOf(16.0)), "p")!!
        // First quiet beat at 180 s, then POST_VOCAL_HANDOFF_PULSES.
        assertEquals(182_000.0, plan.outBeats.last(), 1e-6)
        assertEquals(28, plan.cuePulse)
        assertEquals(VocalMode.NONE, plan.inVocal.mode)
    }

    @Test
    fun singerOnTheCueEntersInSync() {
        val plan = PulsePlanner.plan(outgoing, side(240.0, 120.0, { it >= 16.0 }, inCues = listOf(16.0)), "p")!!
        assertEquals(VocalMode.CUE_SYNC, plan.inVocal.mode)
        assertEquals(16_000.0, plan.inVocal.lineStartMs, 1.0)
        assertNull("no fill under a cue-sync entry", plan.fill)
    }

    @Test
    fun offGridDrumsRejectTheBeatmatch() {
        val inc = side(240.0, 120.0, { false }, inCues = listOf(16.0), drumShift = 0.4)
        val plan = PulsePlanner.plan(outgoing, inc, "p")!!
        assertFalse(plan.beatmatched)
        assertNull(plan.inTempo)
        assertEquals(MixStyle.FILTER_SWEEP, plan.fallbackStyle)
        val report = PulsePlanner.report(plan, "a", "b")
        assertTrue(report.getJSONObject("qa").getString("reason").contains("incoming"))
    }

    @Test
    fun incomingBassWaitsForTheCue() {
        val plan = PulsePlanner.plan(outgoing, side(240.0, 120.0, { false }, inCues = listOf(16.0)), "p")!!
        val cue = MixAutomation.cueBar(plan)
        assertEquals(0f, MixAutomation.incoming(plan, cue - 0.1, 0.0).low, 1e-6f)
        assertEquals(1f, MixAutomation.incoming(plan, cue + 0.6, 0.0).low, 1e-6f)
        assertEquals(1f, MixAutomation.outgoing(plan, 0.0, 0.0).low, 1e-6f)
        assertEquals(0f, MixAutomation.outgoing(plan, cue + 0.6, 0.0).low, 1e-6f)
        assertTrue(plan.loudnessDb.all { it in PulsePlanner.LOUDNESS_MIN_DB..PulsePlanner.LOUDNESS_MAX_DB })
    }

    private fun nearestBeatMs(s: PulsePlanner.Side, t: Double) = s.pulse.beats[PulsePlanner.nearest(s.pulse.beats, t)] * 1000
}
