package dev.lelonio.square.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayoutMapTest {
    @Test
    fun followsTheRateAcrossATempoMap() {
        val m = PlayoutMap()
        m.add(1000, 1000, 1f)
        assertNull("plain speed math until a map runs", m.mediaFrames(500))
        m.scheduled = true
        // 0.968 for 3100 output frames (3000 in), then a release back to 1.0.
        m.add(3000, 3100, 0.968f)
        m.add(500, 500, 1f)
        assertEquals(500L, m.mediaFrames(500))
        assertEquals(1000L + 1500, m.mediaFrames(1000 + 1550))
        assertEquals(4000L + 250, m.mediaFrames(4100 + 250))
        // Past what was produced: the current segment's ratio.
        assertEquals(4500L + 100, m.mediaFrames(4600 + 100))
        m.reset()
        assertNull(m.mediaFrames(10))
    }
}
