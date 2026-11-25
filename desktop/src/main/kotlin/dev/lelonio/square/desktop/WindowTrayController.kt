package dev.lelonio.square.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class WindowMode {
    SHOWN,
    MINI_PLAYER,
    TRAY,
}

interface WindowHost {
    fun showMainWindow(bounds: WindowBoundsSnapshot?)
    fun hideMainWindow()
    fun showMiniPlayer()
    fun hideMiniPlayer()
    fun disposeAllWindowsAndCaches()
    fun showFirstTrayNotice()
    fun shutdownEngineAndExit()
}

/**
 * Coordinates transitions between Main Window, Floating Mini-Player, and Headless System Tray.
 * Designed as a testable state machine over [WindowHost].
 */
class WindowTrayStateMachine(
    private val host: WindowHost,
    initialCloseToTray: Boolean = true,
    initialTraySupported: Boolean = true,
    initialNoticeShown: Boolean = false,
    private val onNoticeShown: () -> Unit = {},
) {
    var mode by mutableStateOf(WindowMode.SHOWN)
        private set
    var closeToTray by mutableStateOf(initialCloseToTray)
    var traySupported by mutableStateOf(initialTraySupported)
    var trayNoticeShown by mutableStateOf(initialNoticeShown)
        private set
    var savedMainBounds: WindowBoundsSnapshot? = null
        private set

    fun updateMainBounds(bounds: WindowBoundsSnapshot?) {
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            savedMainBounds = bounds
        }
    }

    fun toggleMiniPlayer(currentMainBounds: WindowBoundsSnapshot? = null) {
        when (mode) {
            WindowMode.SHOWN -> {
                updateMainBounds(currentMainBounds)
                mode = WindowMode.MINI_PLAYER
                host.hideMainWindow()
                host.showMiniPlayer()
            }
            WindowMode.MINI_PLAYER -> restoreMain()
            WindowMode.TRAY -> openMiniPlayerFromTray()
        }
    }

    fun openMiniPlayerFromTray() {
        if (mode == WindowMode.MINI_PLAYER) return
        mode = WindowMode.MINI_PLAYER
        host.hideMainWindow()
        host.showMiniPlayer()
    }

    fun restoreMain() {
        if (mode == WindowMode.SHOWN) {
            host.showMainWindow(savedMainBounds)
            return
        }
        val wasMini = mode == WindowMode.MINI_PLAYER
        mode = WindowMode.SHOWN
        if (wasMini) host.hideMiniPlayer()
        host.showMainWindow(savedMainBounds)
    }

    /**
     * Handles close (X) button. When [closeToTray] and [traySupported] are true (or [forceTray] for
     * programmatic soak tests), enters low-RAM [WindowMode.TRAY]; otherwise shuts down cleanly.
     */
    fun onCloseRequested(currentMainBounds: WindowBoundsSnapshot? = null, forceTray: Boolean = false) {
        updateMainBounds(currentMainBounds)
        if (forceTray || (closeToTray && traySupported)) {
            hideToTray(currentMainBounds)
        } else {
            quit()
        }
    }

    fun hideToTray(currentMainBounds: WindowBoundsSnapshot? = null) {
        if (mode == WindowMode.SHOWN) {
            updateMainBounds(currentMainBounds)
        }
        if (mode == WindowMode.TRAY) return
        mode = WindowMode.TRAY
        host.hideMiniPlayer()
        host.hideMainWindow()
        host.disposeAllWindowsAndCaches()
        if (!trayNoticeShown) {
            trayNoticeShown = true
            onNoticeShown()
            host.showFirstTrayNotice()
        }
    }

    fun quit() {
        host.hideMiniPlayer()
        host.hideMainWindow()
        host.disposeAllWindowsAndCaches()
        host.shutdownEngineAndExit()
    }
}
