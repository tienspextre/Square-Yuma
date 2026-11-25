package dev.lelonio.square.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.ui.player.MiniPlayerWindow
import dev.lelonio.square.desktop.ui.shell.App
import dev.lelonio.square.desktop.ui.shell.NativeWindowFrameHost
import org.json.JSONObject
import java.awt.Dimension
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--self-check" in args) {
        val report = File(System.getProperty("user.home"), ".square-yuma/self-check.txt").apply { parentFile.mkdirs() }
        val result = runCatching {
            selfCheck()
            versionCheck()
            automixCheck()
            nativeCheck()
            val nativeReport = JSONObject(NativeEngine.selfCheckRender())
            check(nativeReport.optBoolean("ok")) { nativeReport.optString("summary", "native self-check failed") }
            nativeReport
        }
        val text = result.fold(
            { nr -> "self-check ok: ${nr.optString("summary")} (duration=${nr.optDouble("durationSec")}s lufs=${"%.1f".format(nr.optDouble("lufs"))})\n" },
            { "self-check failed: $it\n" },
        )
        report.writeText(text)
        print(text)
        exitProcess(if (result.isSuccess) 0 else 1)
    }
    LogTail.install()
    runCatching { Renderer.valueOf(uiPrefs.get("renderer", "Auto")) }.getOrNull()?.api?.let {
        System.setProperty("skiko.renderApi", it)
    }
    app(soakTest = "--soak-test" in args)
}

private fun snapshotBounds(win: ComposeWindow?, ws: WindowState?): WindowBoundsSnapshot? {
    if (win != null && win.isDisplayable && win.width > 0 && win.height > 0) {
        val max = (win.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
        return WindowBoundsSnapshot(win.x, win.y, win.width, win.height, maximized = max)
    }
    val s = ws ?: return null
    val pos = s.position
    val x = (pos as? WindowPosition.Absolute)?.x?.value?.toInt() ?: 100
    val y = (pos as? WindowPosition.Absolute)?.y?.value?.toInt() ?: 100
    return WindowBoundsSnapshot(
        x = x,
        y = y,
        width = s.size.width.value.toInt().coerceAtLeast(940),
        height = s.size.height.value.toInt().coerceAtLeast(620),
        maximized = s.placement == WindowPlacement.Maximized,
    )
}

private fun app(soakTest: Boolean = false) = application {
    val state = remember { AppState() }
    var activeMainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    var activeMainState by remember { mutableStateOf<WindowState?>(null) }
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }

    lateinit var trayManager: TrayManager
    lateinit var windowMachine: WindowTrayStateMachine

    val shutdownApp = {
        trayManager.remove()
        state.shutdown()
        exitApplication()
    }

    val host = remember {
        object : WindowHost {
            override fun showMainWindow(bounds: WindowBoundsSnapshot?) {}
            override fun hideMainWindow() {}
            override fun showMiniPlayer() {}
            override fun hideMiniPlayer() {}
            override fun disposeAllWindowsAndCaches() {
                LowRamReleaser.releaseCachesAndGc(listOfNotNull(activeMainWindow))
                activeMainWindow = null
            }
            override fun showFirstTrayNotice() {
                trayManager.showFirstNotice()
            }
            override fun shutdownEngineAndExit() {
                shutdownApp()
            }
        }
    }

    windowMachine = remember {
        WindowTrayStateMachine(
            host = host,
            initialCloseToTray = state.closeToTray,
            initialTraySupported = false,
            initialNoticeShown = state.trayNoticeShown,
            onNoticeShown = { state.markTrayNoticeShown() },
        )
    }

    trayManager = remember {
        TrayManager(
            state = state,
            onPlayPause = { state.player.toggle() },
            onNext = { state.player.next() },
            onPrevious = { state.player.previous() },
            onMiniPlayer = { windowMachine.toggleMiniPlayer(snapshotBounds(activeMainWindow, activeMainState)) },
            onOpen = { windowMachine.restoreMain() },
            onQuit = { windowMachine.quit() },
        )
    }

    LaunchedEffect(Unit) {
        val installed = trayManager.install()
        state.traySupported = installed
        windowMachine.traySupported = installed
        state.onMiniPlayerToggle = {
            windowMachine.toggleMiniPlayer(snapshotBounds(activeMainWindow, activeMainState))
        }
        state.onCloseRequested = {
            windowMachine.onCloseRequested(snapshotBounds(activeMainWindow, activeMainState))
        }
    }

    LaunchedEffect(state.closeToTray) {
        windowMachine.closeToTray = state.closeToTray
    }

    if (soakTest) {
        LaunchedEffect(Unit) {
            SoakTestRunner.run(
                state = state,
                trayStateMachine = windowMachine,
                captureMainBounds = { snapshotBounds(activeMainWindow, activeMainState) },
                onFinish = { code ->
                    trayManager.remove()
                    state.shutdown()
                    exitProcess(code)
                },
            )
        }
    }

    if (windowMachine.mode != WindowMode.TRAY) {
        val saved = windowMachine.savedMainBounds
        val windowState = rememberWindowState(
            position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 840.dp),
            placement = when {
                state.fullscreen -> WindowPlacement.Fullscreen
                saved?.maximized == true -> WindowPlacement.Maximized
                else -> WindowPlacement.Floating
            },
        )
        activeMainState = windowState

        LaunchedEffect(state.fullscreen) {
            if (state.fullscreen) {
                if (windowState.placement != WindowPlacement.Fullscreen) {
                    previousPlacement = windowState.placement
                    windowState.placement = WindowPlacement.Fullscreen
                }
            } else if (windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = previousPlacement
            }
        }

        val closeAction = {
            windowMachine.onCloseRequested(snapshotBounds(activeMainWindow, windowState))
        }

        Window(
            onCloseRequest = closeAction,
            visible = (windowMachine.mode == WindowMode.SHOWN),
            title = "Square-Yuma",
            icon = painterResource("square.png"),
            state = windowState,
            undecorated = true,
            transparent = false,
            onPreviewKeyEvent = state::onKey,
            onKeyEvent = state::onPostKey,
        ) {
            DisposableEffect(window) {
                activeMainWindow = window
                onDispose {
                    if (activeMainWindow === window) {
                        LowRamReleaser.releaseWindowGpuResources(window)
                        activeMainWindow = null
                    }
                }
            }
            window.minimumSize = Dimension(940, 620)
            NativeWindowFrameHost(window = window) { frameController ->
                this@Window.App(
                    state = state,
                    window = window,
                    windowState = windowState,
                    frameController = frameController,
                    onCloseWindow = closeAction,
                )
            }
        }
    }

    if (windowMachine.mode == WindowMode.MINI_PLAYER) {
        MiniPlayerWindow(
            state = state,
            savedMainBounds = windowMachine.savedMainBounds,
            onRestoreMain = { windowMachine.restoreMain() },
            onCloseMini = {
                windowMachine.onCloseRequested(windowMachine.savedMainBounds)
            },
        )
    }
}
