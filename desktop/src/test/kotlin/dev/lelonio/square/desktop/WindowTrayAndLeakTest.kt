package dev.lelonio.square.desktop

import androidx.compose.ui.graphics.ImageBitmap
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.ui.components.artworkCacheSize
import dev.lelonio.square.desktop.ui.components.blurCacheSize
import dev.lelonio.square.desktop.ui.components.clearArtworkCaches
import dev.lelonio.square.desktop.ui.components.clearBlurCache
import dev.lelonio.square.desktop.ui.components.seedArtworkCacheForTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWindowHost : WindowHost {
    var mainShowCount = 0
    var mainHideCount = 0
    var miniShowCount = 0
    var miniHideCount = 0
    var disposeCount = 0
    var noticeCount = 0
    var quitCount = 0
    var lastRestoredBounds: WindowBoundsSnapshot? = null

    override fun showMainWindow(bounds: WindowBoundsSnapshot?) {
        mainShowCount++
        lastRestoredBounds = bounds
    }

    override fun hideMainWindow() {
        mainHideCount++
    }

    override fun showMiniPlayer() {
        miniShowCount++
    }

    override fun hideMiniPlayer() {
        miniHideCount++
    }

    override fun disposeAllWindowsAndCaches() {
        disposeCount++
        clearArtworkCaches()
        clearBlurCache()
    }

    override fun showFirstTrayNotice() {
        noticeCount++
    }

    override fun shutdownEngineAndExit() {
        quitCount++
    }
}

class WindowTrayAndLeakTest {

    @Test
    fun trayStateMachineTransitionsBetweenShownMiniPlayerTrayAndQuit() {
        val host = FakeWindowHost()
        val bounds = WindowBoundsSnapshot(120, 80, 1280, 840, maximized = false)
        var noticePersisted = false
        val sm = WindowTrayStateMachine(
            host = host,
            initialCloseToTray = true,
            initialTraySupported = true,
            initialNoticeShown = false,
            onNoticeShown = { noticePersisted = true },
        )

        assertEquals(WindowMode.SHOWN, sm.mode)

        // Shown -> MiniPlayer -> Shown
        sm.toggleMiniPlayer(bounds)
        assertEquals(WindowMode.MINI_PLAYER, sm.mode)
        assertEquals(1, host.miniShowCount)
        assertEquals(1, host.mainHideCount)

        sm.toggleMiniPlayer()
        assertEquals(WindowMode.SHOWN, sm.mode)
        assertEquals(1, host.miniHideCount)
        assertEquals(1, host.mainShowCount)
        assertEquals(bounds, host.lastRestoredBounds)

        // Shown -> Tray (shows notice once) -> MiniPlayer -> Tray -> Shown
        sm.onCloseRequested(bounds)
        assertEquals(WindowMode.TRAY, sm.mode)
        assertEquals(1, host.disposeCount)
        assertEquals(1, host.noticeCount)
        assertTrue(noticePersisted)

        sm.openMiniPlayerFromTray()
        assertEquals(WindowMode.MINI_PLAYER, sm.mode)

        sm.onCloseRequested()
        assertEquals(WindowMode.TRAY, sm.mode)
        assertEquals(1, host.noticeCount) // Only shown the first time!

        sm.restoreMain()
        assertEquals(WindowMode.SHOWN, sm.mode)
        assertEquals(bounds, host.lastRestoredBounds)

        // Quit from each mode
        sm.quit()
        assertEquals(1, host.quitCount)

        sm.toggleMiniPlayer(bounds)
        sm.quit()
        assertEquals(2, host.quitCount)

        sm.hideToTray(bounds)
        sm.quit()
        assertEquals(3, host.quitCount)

        // When closeToTray is false (or traySupported is false), close (X) quits directly
        val exitHost = FakeWindowHost()
        val exitSm = WindowTrayStateMachine(exitHost, initialCloseToTray = false, initialTraySupported = true)
        exitSm.onCloseRequested(bounds)
        assertEquals(1, exitHost.quitCount)
        assertFalse(exitSm.trayNoticeShown)

        val unsupportedHost = FakeWindowHost()
        val unsupportedSm = WindowTrayStateMachine(unsupportedHost, initialCloseToTray = true, initialTraySupported = false)
        unsupportedSm.onCloseRequested(bounds)
        assertEquals(1, unsupportedHost.quitCount)
    }

    @Test
    fun leakCheckAcrossTwentyShownToTrayToShownCycles() = runBlocking {
        val host = FakeWindowHost()
        val sm = WindowTrayStateMachine(host, initialCloseToTray = true, initialTraySupported = true)
        val state = AppState(
            initEngine = false,
            startMediaKeysService = false,
            installShutdownHook = false,
            mainDispatcher = Dispatchers.Default,
        )
        val dummyCb = object : NativeEngine.Callback {
            override fun onEnded(deck: Int) {}
            override fun onDeviceLost(reason: String) {}
            override fun onSmtcButton(button: String) {}
            override fun onError(deck: Int, message: String) {}
        }
        try {
            // Simulate registered NativeEngine callback (single active listener)
            NativeEngine.setCallback(dummyCb)
            val baselineJobs = state.activeChildJobCount()
            val baselineCallbacks = NativeEngine.registeredCallbackCount
            val bounds = WindowBoundsSnapshot(100, 100, 1280, 840)

            repeat(20) { cycle ->
                seedArtworkCacheForTest("cover_$cycle", ImageBitmap(4, 4))
                assertTrue(artworkCacheSize() > 0)

                sm.onCloseRequested(bounds)
                assertEquals(WindowMode.TRAY, sm.mode)
                assertEquals(0, artworkCacheSize())
                assertEquals(0, blurCacheSize())

                sm.restoreMain()
                assertEquals(WindowMode.SHOWN, sm.mode)
            }

            assertEquals(20, host.disposeCount)
            assertEquals(20, host.mainShowCount)
            assertEquals(baselineCallbacks, NativeEngine.registeredCallbackCount)
            assertEquals(0, state.player.smtcUpdateCount)
            assertTrue(
                "Expected no coroutine job growth in AppState (baseline=$baselineJobs, now=${state.activeChildJobCount()})",
                state.activeChildJobCount() <= baselineJobs,
            )
        } finally {
            NativeEngine.setCallback(null)
            state.shutdown()
        }
    }
}
