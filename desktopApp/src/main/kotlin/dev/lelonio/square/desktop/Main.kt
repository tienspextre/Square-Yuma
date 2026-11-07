package dev.lelonio.square.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) = application {
    val controller = remember {
        DesktopMusicController().apply {
            when {
                "--immersion" in args -> setLayoutMode(DesktopLayoutMode.IMMERSION_STAGE)
                "--inspect" in args -> {
                    setImmersionTab(ImmersionRightTab.INSPECT)
                    setLayoutMode(DesktopLayoutMode.IMMERSION_STAGE)
                }
                "--mini" in args -> setLayoutMode(DesktopLayoutMode.MINI_CUBE)
            }
        }
    }
    val layoutMode by controller.layoutMode.collectAsState()
    val currentTrack by controller.player.currentTrack.collectAsState()

    val windowState = rememberWindowState(
        size = DpSize(1420.dp, 880.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )

    LaunchedEffect(layoutMode) {
        if (layoutMode == DesktopLayoutMode.MINI_CUBE) {
            windowState.size = DpSize(380.dp, 400.dp)
        } else if (windowState.size.width < 600.dp) {
            windowState.size = DpSize(1420.dp, 880.dp)
        }
    }

    val windowTitle = buildString {
        val t = currentTrack
        if (t != null) {
            append(t.name).append(" — ").append(t.artist).append("  •  ")
        }
        append("Square × Yuma Desktop (Liquid Glass & Automix Studio)")
    }

    Window(
        onCloseRequest = {
            controller.player.release()
            exitApplication()
        },
        state = windowState,
        title = windowTitle,
        alwaysOnTop = layoutMode == DesktopLayoutMode.MINI_CUBE,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when {
                    event.key == Key.F11 -> {
                        controller.toggleImmersionMode()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.M -> {
                        controller.toggleMiniCubeMode()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.L -> {
                        controller.toggleRightSidecar()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.I -> {
                        controller.setImmersionTab(ImmersionRightTab.INSPECT)
                        controller.setLayoutMode(DesktopLayoutMode.IMMERSION_STAGE)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionRight -> {
                        controller.player.skipNext()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionLeft -> {
                        controller.player.skipPrevious()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionUp -> {
                        controller.player.setMasterVolume(controller.player.masterVolume.value + 0.05f)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionDown -> {
                        controller.player.setMasterVolume(controller.player.masterVolume.value - 0.05f)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        },
    ) {
        SquareDesktopApp(controller = controller)
    }
}
