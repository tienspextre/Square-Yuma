package dev.lelonio.square.desktop.ui.shell

const val SIDEBAR_COLLAPSE_THRESHOLD_DP = 1000f

/**
 * Determines whether the left navigation sidebar should collapse to an icon rail.
 * When [manualOverride] is non-null (user clicked the collapse/expand toggle), it wins;
 * otherwise collapses automatically below [SIDEBAR_COLLAPSE_THRESHOLD_DP].
 */
fun shouldCollapseSidebar(windowWidthDp: Float, manualOverride: Boolean?): Boolean =
    manualOverride ?: (windowWidthDp < SIDEBAR_COLLAPSE_THRESHOLD_DP)

enum class HitRegion {
    CLIENT,
    CAPTION,
    MIN_BUTTON,
    MAX_BUTTON,
    CLOSE_BUTTON,
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT,
}

data class PixelRect(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean =
        !isEmpty && x >= left && x < right && y >= top && y < bottom
}

/**
 * Pure hit-test geometry matching `native/windows/src/window_win.cpp`'s `hitTestPoint`.
 * Coordinates `(x, y)` are window-relative pixels inside `[0..width) x [0..height)`.
 */
fun hitTestWindowFrame(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    captionHeightPx: Int,
    resizeBorderPx: Int,
    isMaximized: Boolean,
    minButton: PixelRect,
    maxButton: PixelRect,
    closeButton: PixelRect,
    interactiveRects: List<PixelRect>,
): HitRegion {
    if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) {
        return HitRegion.CLIENT
    }
    if (!isMaximized) {
        val border = resizeBorderPx.coerceAtLeast(4)
        val left = x < border
        val right = x >= width - border
        val top = y < border
        val bottom = y >= height - border
        if (top && left) return HitRegion.TOP_LEFT
        if (top && right) return HitRegion.TOP_RIGHT
        if (bottom && left) return HitRegion.BOTTOM_LEFT
        if (bottom && right) return HitRegion.BOTTOM_RIGHT
        if (top) return HitRegion.TOP
        if (bottom) return HitRegion.BOTTOM
        if (left) return HitRegion.LEFT
        if (right) return HitRegion.RIGHT
    }

    if (closeButton.contains(x, y)) return HitRegion.CLOSE_BUTTON
    if (maxButton.contains(x, y)) return HitRegion.MAX_BUTTON
    if (minButton.contains(x, y)) return HitRegion.MIN_BUTTON

    for (r in interactiveRects) {
        if (r.contains(x, y)) return HitRegion.CLIENT
    }

    if (y < captionHeightPx) return HitRegion.CAPTION
    return HitRegion.CLIENT
}

fun packButtonRects(minButton: PixelRect, maxButton: PixelRect, closeButton: PixelRect): IntArray =
    intArrayOf(
        minButton.left, minButton.top, minButton.right, minButton.bottom,
        maxButton.left, maxButton.top, maxButton.right, maxButton.bottom,
        closeButton.left, closeButton.top, closeButton.right, closeButton.bottom,
    )

fun packInteractiveRects(rects: List<PixelRect>): IntArray {
    val out = IntArray(rects.size * 4)
    for (i in rects.indices) {
        val r = rects[i]
        out[i * 4] = r.left
        out[i * 4 + 1] = r.top
        out[i * 4 + 2] = r.right
        out[i * 4 + 3] = r.bottom
    }
    return out
}
