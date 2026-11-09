package moe.rukamori.archivetune.playback.smart

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPassTest {
    private val span = 958 * 1024 / 44_100.0 // the vocal model's window, ~22.24 s

    @Test
    fun windowsTileHeadAndTailWithOverlap() {
        val duration = 240.0
        val headEnd = ModelPass.headEnd(duration, span)
        val tailStart = ModelPass.tailStart(duration, span)
        val windows = ModelPass.modelWindows(headEnd, tailStart, duration, span)
        assertEquals(ModelPass.HEAD_WINDOWS + ModelPass.TAIL_WINDOWS, windows.size)
        assertEquals(0.0, windows.first().first, 1e-9)
        assertEquals(duration, windows.last().second, 1e-9)
        windows.forEach { (from, to) -> assertTrue(to - from <= span + 1e-9) }
        // Neighbours inside a span overlap by the seam margin.
        for (i in listOf(0, 2, 3)) {
            assertEquals(ModelPass.WINDOW_OVERLAP_SECONDS, windows[i].second - windows[i + 1].first, 1e-6)
        }
    }

    @Test
    fun shortTrackIsCoveredOnce() {
        val duration = 80.0
        val windows = ModelPass.modelWindows(
            ModelPass.headEnd(duration, span), ModelPass.tailStart(duration, span), duration, span,
        )
        assertEquals(0.0, windows.first().first, 1e-9)
        assertEquals(duration, windows.last().second, 1e-9)
        windows.zipWithNext().forEach { (a, b) -> assertTrue(b.first < a.second) }
    }

    @Test
    fun jsonCarriesSeamedGridAndVocalWindows() {
        // 120 BPM over two overlapping head windows; each window also sees beats past its seam.
        fun grid(from: Double, to: Double): BeatTracker.Grid {
            val beats = generateSequence(from) { it + 0.5 }.takeWhile { it < to }.toList()
            return BeatTracker.Grid(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 120.0, 0.5, beats.first(), 0.8)
        }
        val windows = listOf(
            ModelPass.Window(0.0, 22.0, grid(0.0, 22.0), FloatArray(4) { 0.25f * it }),
            ModelPass.Window(21.0, 22.0, grid(21.0, 43.0), null),
        )
        val json = JSONObject(ModelPass.buildJson(windows, headEnd = 43.0, tailStart = 300.0, duration = 360.0, vocalFps = 43.0))
        assertEquals(120.0, json.getDouble("headBpm"), 1e-6)
        assertEquals(0.0, json.getDouble("tailBpm"), 0.0)
        assertEquals(0.8, json.getDouble("conf"), 1e-9)
        val downbeats = json.getJSONArray("downbeats")
        val list = (0 until downbeats.length()).map(downbeats::getDouble)
        assertEquals(list.sorted().distinct(), list)
        val vocal = json.getJSONArray("vocal")
        assertEquals(1, vocal.length())
        assertEquals(0.0, vocal.getJSONObject(0).getDouble("start"), 0.0)
        assertEquals(0.75, vocal.getJSONObject(0).getJSONArray("v").getDouble(3), 1e-9)
        assertEquals("", ModelPass.buildJson(listOf(ModelPass.Window(0.0, 22.0, null, null)), 43.0, 300.0, 360.0, 43.0))
    }
}
