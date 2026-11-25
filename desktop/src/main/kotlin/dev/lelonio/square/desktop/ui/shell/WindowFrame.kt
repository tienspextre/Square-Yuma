package dev.lelonio.square.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalDensity
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import java.awt.Frame
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Coordinates Compose titlebar layout bounds with the Windows Win32 frameless subclass
 * (`square_win_native.dll`). On Linux/macOS this stays a harmless no-op while Compose
 * handles dragging via `WindowDraggableArea`.
 */
class NativeWindowFrameController(
    private val window: Window,
    private val resizeBorderPx: Int,
    private val minWidthPx: Int,
    private val minHeightPx: Int,
) {
    private var hwnd: Long = 0L
    private var installed = false

    var captionHeightPx: Int = 44
        private set
    var fullscreenMode: Boolean = false
        private set
    var minButtonRect: PixelRect = PixelRect()
        private set
    var maxButtonRect: PixelRect = PixelRect()
        private set
    var closeButtonRect: PixelRect = PixelRect()
        private set
    private val interactiveMap = LinkedHashMap<String, PixelRect>()

    internal fun attach() {
        if (installed) return
        if (!System.getProperty("os.name").startsWith("Windows")) return
        hwnd = runCatching { NativeEngine.getWindowHwnd(window) }.getOrDefault(0L)
        if (hwnd != 0L) {
            installed = NativeEngine.installFrame(
                hwnd = hwnd,
                captionHeightPx = captionHeightPx,
                resizeBorderPx = resizeBorderPx,
                minWidthPx = minWidthPx,
                minHeightPx = minHeightPx,
                buttonRects = packButtonRects(minButtonRect, maxButtonRect, closeButtonRect),
                interactiveRects = packInteractiveRects(interactiveMap.values.toList()),
            )
        }
    }

    internal fun detach() {
        if (installed && hwnd != 0L) {
            runCatching { NativeEngine.uninstallFrame(hwnd) }
        }
        installed = false
        hwnd = 0L
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (fullscreenMode != fullscreen) {
            fullscreenMode = fullscreen
            pushLayout()
        }
    }

    fun updateCaptionHeight(heightPx: Int) {
        if (heightPx > 0 && heightPx != captionHeightPx) {
            captionHeightPx = heightPx
            pushLayout()
        }
    }

    fun updateButtons(minRect: PixelRect, maxRect: PixelRect, closeRect: PixelRect) {
        if (minRect != minButtonRect || maxRect != maxButtonRect || closeRect != closeButtonRect) {
            minButtonRect = minRect
            maxButtonRect = maxRect
            closeButtonRect = closeRect
            pushLayout()
        }
    }

    fun setInteractiveRect(key: String, rect: PixelRect) {
        if (interactiveMap[key] != rect) {
            interactiveMap[key] = rect
            pushLayout()
        }
    }

    fun removeInteractiveRect(key: String) {
        if (interactiveMap.remove(key) != null) {
            pushLayout()
        }
    }

    private fun pushLayout() {
        if (!installed || hwnd == 0L) return
        NativeEngine.updateFrameLayout(
            hwnd = hwnd,
            captionHeightPx = if (fullscreenMode) 0 else captionHeightPx,
            resizeBorderPx = if (fullscreenMode) 0 else resizeBorderPx,
            minWidthPx = minWidthPx,
            minHeightPx = minHeightPx,
            buttonRects = packButtonRects(minButtonRect, maxButtonRect, closeButtonRect),
            interactiveRects = packInteractiveRects(interactiveMap.values.toList()),
        )
    }
}

@Composable
fun NativeWindowFrameHost(
    window: ComposeWindow,
    content: @Composable (NativeWindowFrameController) -> Unit,
) {
    val density = LocalDensity.current.density
    val borderPx = (6f * density).roundToInt().coerceAtLeast(4)
    val minWPx = (940f * density).roundToInt()
    val minHPx = (620f * density).roundToInt()

    val controller = remember(window, borderPx, minWPx, minHPx) {
        NativeWindowFrameController(window, borderPx, minWPx, minHPx)
    }

    var attached by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, window.isDisplayable) {
        if (!attached && window.isDisplayable) {
            controller.attach()
            attached = true
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.detach() }
    }

    content(controller)
}

fun toggleMaximizeWindow(window: ComposeWindow) {
    val maximized = (window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
    window.extendedState = if (maximized) {
        window.extendedState and Frame.MAXIMIZED_BOTH.inv()
    } else {
        window.extendedState or Frame.MAXIMIZED_BOTH
    }
}

fun isWindowMaximized(window: ComposeWindow): Boolean =
    (window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
