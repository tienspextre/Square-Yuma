package dev.lelonio.square.desktop

import dev.lelonio.square.desktop.ui.shell.HitRegion
import dev.lelonio.square.desktop.ui.shell.PixelRect
import dev.lelonio.square.desktop.ui.shell.hitTestWindowFrame
import dev.lelonio.square.desktop.ui.shell.packButtonRects
import dev.lelonio.square.desktop.ui.shell.packInteractiveRects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowHitTestTest {

    private val minBtn = PixelRect(1140, 0, 1186, 44)
    private val maxBtn = PixelRect(1186, 0, 1232, 44)
    private val closeBtn = PixelRect(1232, 0, 1280, 44)
    private val searchBox = PixelRect(360, 6, 760, 38)

    private fun hit(x: Int, y: Int, maximized: Boolean = false): HitRegion = hitTestWindowFrame(
        x = x,
        y = y,
        width = 1280,
        height = 840,
        captionHeightPx = 44,
        resizeBorderPx = 6,
        isMaximized = maximized,
        minButton = minBtn,
        maxButton = maxBtn,
        closeButton = closeBtn,
        interactiveRects = listOf(searchBox),
    )

    @Test
    fun mapsCaptionButtonsInteractiveAndClientRegions() {
        assertEquals(HitRegion.CAPTION, hit(180, 20))
        assertEquals(HitRegion.CLIENT, hit(500, 20)) // inside searchBox
        assertEquals(HitRegion.MIN_BUTTON, hit(1160, 20))
        assertEquals(HitRegion.MAX_BUTTON, hit(1205, 20))
        assertEquals(HitRegion.CLOSE_BUTTON, hit(1260, 20))
        assertEquals(HitRegion.CLIENT, hit(500, 200))
    }

    @Test
    fun mapsResizeEdgesWhenRestoredAndSuppressesWhenMaximized() {
        assertEquals(HitRegion.TOP_LEFT, hit(2, 2))
        assertEquals(HitRegion.TOP, hit(500, 2))
        assertEquals(HitRegion.TOP_RIGHT, hit(1278, 2))
        assertEquals(HitRegion.LEFT, hit(2, 400))
        assertEquals(HitRegion.RIGHT, hit(1278, 400))
        assertEquals(HitRegion.BOTTOM_LEFT, hit(2, 838))
        assertEquals(HitRegion.BOTTOM, hit(600, 838))
        assertEquals(HitRegion.BOTTOM_RIGHT, hit(1278, 838))

        // Maximized disables resize edges so corners/top hit caption or buttons cleanly.
        assertEquals(HitRegion.CAPTION, hit(2, 2, maximized = true))
        assertEquals(HitRegion.CLOSE_BUTTON, hit(1278, 2, maximized = true))
    }

    @Test
    fun packsRectsForNativeBridge() {
        assertArrayEquals(
            intArrayOf(1140, 0, 1186, 44, 1186, 0, 1232, 44, 1232, 0, 1280, 44),
            packButtonRects(minBtn, maxBtn, closeBtn),
        )
        assertArrayEquals(intArrayOf(360, 6, 760, 38), packInteractiveRects(listOf(searchBox)))
    }
}
