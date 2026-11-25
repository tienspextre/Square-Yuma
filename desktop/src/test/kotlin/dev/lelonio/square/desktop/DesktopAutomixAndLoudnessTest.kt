package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import moe.rukamori.archivetune.audiodsp.TransitionTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAutomixAndLoudnessTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun fixtureAnalysis(bpm: Double, key: String, lengthSec: Double = 200.0): TrackAnalysisResult {
        val bar = 4.0 * 60.0 / bpm
        val curve = JSONArray()
        val low = JSONArray()
        val vocal = JSONArray()
        var t = 0.0
        while (t < lengthSec) {
            curve.put(JSONObject().put("t", t).put("e", 0.75))
            low.put(JSONObject().put("t", t).put("e", 0.3))
            vocal.put(if (t < lengthSec - 35.0 && t > 20.0) 0.9 else 0.1)
            t += 1.0
        }
        val raw = JSONObject()
            .put("bpm", bpm)
            .put("beatConfidence", 0.92)
            .put("key", key)
            .put("contentEndTime", lengthSec)
            .put("downbeats", JSONArray(generateSequence(0.0) { it + bar }.takeWhile { it < lengthSec }.toList()))
            .put("phraseBoundaries", JSONArray(generateSequence(0.0) { it + bar * 8 }.takeWhile { it < lengthSec }.toList()))
            .put("energyCurve", curve)
            .put("lowEnergyCurve", low)
            .put("vocalActivityMask", vocal)
            .put("mixOutCandidates", JSONArray().put(JSONObject().put("t", lengthSec - 24.0).put("s", 0.9).put("y", "outro_start")))
            .put("mixInCandidates", JSONArray().put(JSONObject().put("t", 0.0).put("s", 0.85).put("y", "pickup")))
            .toString()

        return TrackAnalysisResult(
            bpm = bpm,
            mixInTime = 0.0,
            mixOutTime = lengthSec - 24.0,
            contentEndTime = lengthSec,
            firstBeat = 0.0,
            rawJson = raw,
            loudnessLufs = -10.0,
            dynamicRangeDb = 6.0,
        )
    }

    @Test
    fun pairPlannerPlansLocalToLocalAndSubsonicToLocalFromFixtureAnalyses() {
        val cacheDir = tmp.newFolder("analysis").apply { mkdirs() }
        val local1 = SubsonicChildSong(
            id = "local:track:111",
            title = "Local Track A",
            artist = "Artist 1",
            album = "Album 1",
            duration = 200,
            path = "/music/a.flac",
        )
        val local2 = SubsonicChildSong(
            id = "local:track:222",
            title = "Local Track B",
            artist = "Artist 2",
            album = "Album 2",
            duration = 200,
            path = "/music/b.flac",
        )
        val subsonic = SubsonicChildSong(
            id = "sub-track-1",
            title = "Subsonic Track",
            artist = "Artist 3",
            album = "Album 3",
            duration = 200,
        )

        val analyses = Analyses(
            source = { it.path ?: "http://nas/stream/${it.id}" },
            durationMsOf = { it.duration * 1000L },
            dir = cacheDir,
        )
        analyses.put(local1, fixtureAnalysis(bpm = 120.0, key = "A minor"))
        analyses.put(local2, fixtureAnalysis(bpm = 124.0, key = "C major"))
        analyses.put(subsonic, fixtureAnalysis(bpm = 122.0, key = "A minor"))

        val localToLocal = analyses.plan(
            out = local1,
            inc = local2,
            queue = listOf(local1, local2),
            shuffled = false,
            fadeMs = 6_000L,
            bassSwap = true,
            loopMix = false,
        )
        assertNotNull("Expected PairPlanner plan for Local -> Local", localToLocal)
        assertEquals(TransitionTier.SMART_BEATMATCH, localToLocal!!.tier)
        assertTrue(localToLocal.durationMs > 0L)

        val subsonicToLocal = analyses.plan(
            out = subsonic,
            inc = local2,
            queue = listOf(subsonic, local2),
            shuffled = false,
            fadeMs = 6_000L,
            bassSwap = true,
            loopMix = false,
        )
        assertNotNull("Expected PairPlanner plan for Subsonic -> Local", subsonicToLocal)
        assertEquals(TransitionTier.SMART_BEATMATCH, subsonicToLocal!!.tier)

        val localToSubsonic = analyses.plan(
            out = local1,
            inc = subsonic,
            queue = listOf(local1, subsonic),
            shuffled = false,
            fadeMs = 6_000L,
            bassSwap = true,
            loopMix = false,
        )
        assertNotNull("Expected PairPlanner plan for Local -> Subsonic", localToSubsonic)
        assertEquals(TransitionTier.SMART_BEATMATCH, localToSubsonic!!.tier)
    }

    @Test
    fun loudnessCachePersistsAndComputesClampedNormalizationGain() {
        val dir = tmp.newFolder("lufs_cache").toPath()
        val song = SubsonicChildSong(id = "sub-lufs-1", title = "Loud Song", duration = 120)
        val cache = LoudnessCache(dir, sourceResolver = { "/fake/song.flac" }, measureFn = { -9.5 })
        try {
            var deliveredGain: Float? = null
            cache.request(song) { _, gainDb -> deliveredGain = gainDb }
            val deadline = System.currentTimeMillis() + 3000
            while (deliveredGain == null && System.currentTimeMillis() < deadline) Thread.sleep(20)

            assertNotNull(deliveredGain)
            assertEquals(-6.5f, deliveredGain!!, 0.01f)

            // Fresh instance loads persisted LUFS from ~/.square-yuma/analysis
            val reloaded = LoudnessCache(dir, sourceResolver = { "" }, measureFn = { null })
            try {
                assertEquals(-9.5, reloaded.get(song)!!, 0.01)
                assertEquals(-6.5f, reloaded.gainDbFor(song)!!, 0.01f)
            } finally {
                reloaded.shutdown()
            }
        } finally {
            cache.shutdown()
        }
    }
}
