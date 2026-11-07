package dev.lelonio.square.playback

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Resolves or extracts a self-contained static FFmpeg executable for Desktop (Windows & Linux).
 * Guarantees that audio decoding, lossless seeking, and all codecs (FLAC, MP3, AAC, ALAC, Opus, OGG)
 * work out-of-the-box on every system without requiring the user to install FFmpeg.
 */
object DesktopFfmpegLocator {
    @Volatile
    private var cachedBinaryPath: String? = null

    fun getFfmpegPath(): String? {
        cachedBinaryPath?.let { path ->
            if (File(path).canExecute()) return path
        }

        val isWindows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        val exeName = if (isWindows) "ffmpeg.exe" else "ffmpeg"

        // 1. Check adjacent directory (same folder as Square-Yuma.exe or app root)
        val appDirCandidates = listOf(
            File("."),
            File("bin"),
            File("runtime/bin"),
            File(System.getProperty("compose.application.resources.dir") ?: "."),
            File(System.getProperty("user.dir")),
            File(DesktopFfmpegLocator::class.java.protectionDomain?.codeSource?.location?.toURI()?.path ?: ".").parentFile,
        )

        for (dir in appDirCandidates) {
            val candidate = File(dir, exeName)
            if (candidate.exists() && (isWindows || candidate.canExecute())) {
                val abs = candidate.absolutePath
                cachedBinaryPath = abs
                return abs
            }
        }

        // 2. Check system PATH
        if (isCommandAvailable("ffmpeg")) {
            cachedBinaryPath = "ffmpeg"
            return "ffmpeg"
        }

        // 3. Check / extract to user local cache directory
        val localAppData = when {
            isWindows -> System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home") + "/AppData/Local"
            else -> System.getenv("XDG_DATA_HOME") ?: System.getProperty("user.home") + "/.local/share"
        }
        val targetDir = File(localAppData, "Square-Yuma/bin").apply { mkdirs() }
        val targetExe = File(targetDir, exeName)

        if (targetExe.exists() && targetExe.length() > 100_000L) {
            if (!isWindows) targetExe.setExecutable(true)
            val abs = targetExe.absolutePath
            cachedBinaryPath = abs
            return abs
        }

        // 4. Extract bundled gzipped resource if available
        val resourceName = if (isWindows) "/win32-x64/ffmpeg.gz" else "/linux-x64/ffmpeg.gz"
        val resStream = DesktopFfmpegLocator::class.java.getResourceAsStream(resourceName)
        if (resStream != null) {
            try {
                targetExe.parentFile?.mkdirs()
                val tempOut = File(targetDir, "$exeName.tmp")
                GZIPInputStream(resStream).use { gzIn ->
                    FileOutputStream(tempOut).use { fileOut ->
                        gzIn.copyTo(fileOut, bufferSize = 65536)
                    }
                }
                if (!isWindows) tempOut.setExecutable(true)
                if (tempOut.renameTo(targetExe) || targetExe.exists()) {
                    if (!isWindows) targetExe.setExecutable(true)
                    val abs = targetExe.absolutePath
                    cachedBinaryPath = abs
                    return abs
                }
            } catch (e: Exception) {
                System.err.println("Square-Yuma: Failed to extract bundled FFmpeg: ${e.message}")
            }
        }

        return null
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            val proc = ProcessBuilder(cmd, "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
