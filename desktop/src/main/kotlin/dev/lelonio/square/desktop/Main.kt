package dev.lelonio.square.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.ui.shell.App
import dev.lelonio.square.desktop.ui.shell.NativeWindowFrameHost
import org.json.JSONObject
import java.awt.Dimension
import java.io.File

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
        kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
    }
    LogTail.install()
    runCatching { Renderer.valueOf(uiPrefs.get("renderer", "Auto")) }.getOrNull()?.api?.let {
        System.setProperty("skiko.renderApi", it)
    }
    app()
}

private fun app() = application {
    val state = remember { AppState() }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 840.dp))
    val closeApp = {
        state.saveQueueNow()
        state.listening.save()
        state.local.close()
        state.loudness.shutdown()
        exitApplication()
    }
    Window(
        onCloseRequest = closeApp,
        title = "Square-Yuma",
        icon = painterResource("square.png"),
        state = windowState,
        undecorated = true,
        transparent = false,
        onKeyEvent = state::onKey,
    ) {
        window.minimumSize = Dimension(940, 620)
        NativeWindowFrameHost(window = window) { frameController ->
            this@Window.App(
                state = state,
                window = window,
                windowState = windowState,
                frameController = frameController,
                onCloseWindow = closeApp,
            )
        }
    }
}
