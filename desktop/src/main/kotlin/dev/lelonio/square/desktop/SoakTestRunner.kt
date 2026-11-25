package dev.lelonio.square.desktop

import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

data class SoakCycleReport(
    val cycle: Int,
    val step: String,
    val positionMs: Long,
    val underruns: Long,
    val heapUsedMb: Double,
    val rssMb: Double,
)

object SoakTestRunner {
    fun generateSineWav(file: File, durationSec: Int = 30, sampleRate: Int = 44100, channels: Int = 2) {
        val totalFrames = durationSec * sampleRate
        val dataBytes = totalFrames * channels * 2
        val baos = ByteArrayOutputStream(44 + dataBytes)
        fun writeLeInt(out: DataOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeLeShort(out: DataOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        DataOutputStream(baos).use { out ->
            out.writeBytes("RIFF")
            writeLeInt(out, 36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)
            writeLeShort(out, 1)
            writeLeShort(out, channels)
            writeLeInt(out, sampleRate)
            writeLeInt(out, sampleRate * channels * 2)
            writeLeShort(out, channels * 2)
            writeLeShort(out, 16)
            out.writeBytes("data")
            writeLeInt(out, dataBytes)
            for (i in 0 until totalFrames) {
                val t = i.toDouble() / sampleRate.toDouble()
                val s = 0.25 * sin(2.0 * PI * 440.0 * t) + 0.12 * sin(2.0 * PI * 554.37 * t)
                val sample = (s * 32767.0).roundToInt().coerceIn(-32768, 32767)
                repeat(channels) { writeLeShort(out, sample) }
            }
        }
        file.writeBytes(baos.toByteArray())
    }

    fun generateCoverPng(file: File) {
        val img = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(0x3B, 0x5B, 0xFF), 320f, 320f, Color(0xFF, 0x4E, 0x7A))
        g.fillRect(0, 0, 320, 320)
        g.color = Color(255, 255, 255, 90)
        g.fillOval(60, 60, 200, 200)
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    fun currentHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }

    fun currentRssMb(): Double {
        val statm = File("/proc/self/statm")
        if (!statm.isFile) return -1.0
        val parts = statm.readText().trim().split(Regex("\\s+"))
        val rssPages = parts.getOrNull(1)?.toLongOrNull() ?: return -1.0
        return (rssPages * 4096.0) / (1024.0 * 1024.0)
    }

    suspend fun run(
        state: AppState,
        trayStateMachine: WindowTrayStateMachine,
        captureMainBounds: () -> WindowBoundsSnapshot?,
        onFinish: (exitCode: Int) -> Unit,
    ) {
        val tempDir: Path = Files.createTempDirectory("square-yuma-soak-")
        var exitCode = 0
        try {
            val wavFile = tempDir.resolve("soak_tone.wav").toFile()
            val coverFile = tempDir.resolve("cover.png").toFile()
            generateWavAndCover(wavFile, coverFile)

            state.local.addFolder(tempDir)
            val songs = state.local.desktopSongs().filter { it.path?.startsWith(tempDir.toString()) == true }
            val song = songs.firstOrNull() ?: DropTargetHandler.songFromPath(wavFile.toPath(), state.local)

            delay(700)
            state.player.playQueue(listOf(song), 0)
            // Wait up to 3s for audio position to start advancing
            var initialPos = 0L
            for (attempt in 0 until 30) {
                delay(100)
                initialPos = state.player.positionMs.value
                if (initialPos > 0L) break
            }
            println("SOAK_START posMs=$initialPos heapMb=${"%.2f".format(currentHeapMb())} rssMb=${"%.2f".format(currentRssMb())}")

            var prevPos = initialPos
            var maxUnderruns = 0L
            val shownHeaps = mutableListOf<Double>()
            val trayHeaps = mutableListOf<Double>()

            fun sample(cycle: Int, step: String): SoakCycleReport {
                val rawStatus = NativeEngine.status()
                val chain = AudioSignalChain.fromJson(rawStatus)
                val pos = NativeEngine.position(0).coerceAtLeast(state.player.positionMs.value)
                maxUnderruns = maxOf(maxUnderruns, chain.underruns)
                val rep = SoakCycleReport(
                    cycle = cycle,
                    step = step,
                    positionMs = pos,
                    underruns = chain.underruns,
                    heapUsedMb = currentHeapMb(),
                    rssMb = currentRssMb(),
                )
                println(
                    "SOAK_STEP cycle=${rep.cycle} step=${rep.step} posMs=${rep.positionMs} " +
                        "playing=${chain.playing} dev=${chain.deviceName} bufMs=${chain.bufferedMs} " +
                        "underruns=${rep.underruns} heapMb=${"%.2f".format(rep.heapUsedMb)} rssMb=${"%.2f".format(rep.rssMb)}",
                )
                return rep
            }

            for (cycle in 1..10) {
                delay(220)
                sample(cycle, "Main")

                trayStateMachine.toggleMiniPlayer(captureMainBounds())
                delay(250)
                sample(cycle, "MiniPlayer")

                trayStateMachine.restoreMain()
                delay(250)
                sample(cycle, "MainFromMini")

                trayStateMachine.onCloseRequested(captureMainBounds(), forceTray = true)
                delay(250)
                System.gc()
                delay(80)
                System.gc()
                delay(80)
                val trayRep = sample(cycle, "TrayPostGc")
                trayHeaps += trayRep.heapUsedMb

                trayStateMachine.restoreMain()
                delay(300)
                System.gc()
                delay(80)
                System.gc()
                delay(80)
                val shownRep = sample(cycle, "MainPostGc")
                shownHeaps += shownRep.heapUsedMb

                prevPos = maxOf(prevPos, shownRep.positionMs)
            }

            val finalChain = AudioSignalChain.fromJson(NativeEngine.status())
            val finalPos = maxOf(prevPos, NativeEngine.position(0))
            val avgShown = shownHeaps.average()
            val avgTray = trayHeaps.average()
            val posAdvanced = finalPos > initialPos
            val zeroUnderruns = (maxUnderruns == 0L && finalChain.underruns == 0L)

            println(
                "SOAK_SUMMARY initialPosMs=$initialPos finalPosMs=$finalPos posAdvanced=$posAdvanced " +
                    "maxUnderruns=$maxUnderruns avgShownHeapMb=${"%.2f".format(avgShown)} avgTrayHeapMb=${"%.2f".format(avgTray)}",
            )
            if (!posAdvanced || !zeroUnderruns) {
                System.err.println("SOAK_FAILED posAdvanced=$posAdvanced maxUnderruns=$maxUnderruns")
                exitCode = 1
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            exitCode = 1
        } finally {
            runCatching { state.local.removeFolder(tempDir) }
            runCatching { tempDir.toFile().deleteRecursively() }
            onFinish(exitCode)
        }
    }

    private fun generateWavAndCover(wavFile: File, coverFile: File) {
        generateSineWav(wavFile, durationSec = 30)
        generateCoverPng(coverFile)
    }
}
