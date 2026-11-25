package dev.lelonio.square.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

private class InMemoryBoundsKv : BoundsKeyValue {
    private val ints = HashMap<String, Int>()
    private val strings = HashMap<String, String>()
    override fun getInt(key: String, default: Int): Int = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getString(key: String, default: String): String = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun contains(key: String): Boolean = key in ints || key in strings
}

class MiniPlayerBoundsTest {
    private val leftMon = MonitorBounds("left", x = -1920, y = 0, width = 1920, height = 1040)
    private val mainMon = MonitorBounds("main", x = 0, y = 0, width = 2560, height = 1400)
    private val monitors = listOf(leftMon, mainMon)

    @Test
    fun clampsOffScreenPositionsInsideNearestMonitor() {
        // Off the far right / bottom of main monitor
        val (x1, y1) = MiniPlayerBounds.clampMiniPlayerPosition(3000, 1800, 360, 88, monitors)
        assertEquals(2560 - 360, x1)
        assertEquals(1400 - 88, y1)

        // Off the far left / top of negative-x secondary monitor
        val (x2, y2) = MiniPlayerBounds.clampMiniPlayerPosition(-2500, -100, 360, 88, monitors)
        assertEquals(-1920, x2)
        assertEquals(0, y2)
    }

    @Test
    fun remembersPerMonitorPositionsAndClampsWhenMonitorShrinks() {
        val store = InMemoryBoundsKv()
        // Save on left monitor
        MiniPlayerBounds.savePosition(store, -400, 900, 360, 88, monitors)
        // Save on main monitor
        MiniPlayerBounds.savePosition(store, 2000, 1200, 360, 88, monitors)

        val resolvedLeft = MiniPlayerBounds.resolveInitialPosition(
            store, monitors, 360, 88, preferredCenterX = -1000, preferredCenterY = 500,
        )
        assertEquals(-400 to 900, resolvedLeft)

        val resolvedMain = MiniPlayerBounds.resolveInitialPosition(
            store, monitors, 360, 88, preferredCenterX = 1280, preferredCenterY = 700,
        )
        assertEquals(2000 to 1200, resolvedMain)

        // If main monitor later shrinks (e.g. 1280x720), saved position clamps cleanly on-screen
        val shrunkMain = listOf(MonitorBounds("main", x = 0, y = 0, width = 1280, height = 720))
        val clampedAfterShrink = MiniPlayerBounds.resolveInitialPosition(
            store, shrunkMain, 360, 88, preferredCenterX = 500, preferredCenterY = 300,
        )
        assertEquals(1280 - 360, clampedAfterShrink.first)
        assertEquals(720 - 88, clampedAfterShrink.second)
    }
}
