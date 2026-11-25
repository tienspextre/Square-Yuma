package dev.lelonio.square.desktop

import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small size-capped rolling file logger under `~/.square-yuma/logs`.
 * Keeps up to [maxFiles] files of [maxFileBytes] each (~1.5 MB max total).
 */
class RollingFileLog(
    private val logDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "logs"),
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val lock = Any()
    private val activeFile: Path = logDir.resolve(CURRENT_FILE_NAME)

    fun appendLine(line: String) {
        val clean = line.trimEnd('\r', '\n')
        val formatted = "${TIMESTAMP_FMT.format(Instant.now())} $clean\n"
        appendRaw(formatted.toByteArray(StandardCharsets.UTF_8))
    }

    private fun appendRaw(bytes: ByteArray) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(logDir)
                if (Files.isRegularFile(activeFile) && Files.size(activeFile) + bytes.size > maxFileBytes) {
                    rotateLocked()
                }
                Files.write(
                    activeFile,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    private fun rotateLocked() {
        for (idx in (maxFiles - 1) downTo 1) {
            val src = if (idx == 1) activeFile else logDir.resolve("$CURRENT_FILE_NAME.${idx - 1}")
            val dst = logDir.resolve("$CURRENT_FILE_NAME.$idx")
            if (Files.exists(src)) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun readLastBytes(maxBytes: Int = DEFAULT_TAIL_BYTES): String = synchronized(lock) {
        if (!Files.isDirectory(logDir) || maxBytes <= 0) return@synchronized ""
        val ordered = buildList {
            for (idx in (maxFiles - 1) downTo 1) {
                val f = logDir.resolve("$CURRENT_FILE_NAME.$idx")
                if (Files.isRegularFile(f)) add(f)
            }
            if (Files.isRegularFile(activeFile)) add(activeFile)
        }
        if (ordered.isEmpty()) return@synchronized ""

        val combined = ByteArrayOutputStream()
        for (p in ordered) {
            runCatching { Files.readAllBytes(p) }.getOrNull()?.let { combined.write(it) }
        }
        val all = combined.toByteArray()
        if (all.size <= maxBytes) return@synchronized String(all, StandardCharsets.UTF_8)
        var start = all.size - maxBytes
        // Advance past UTF-8 continuation bytes (10xxxxxx) so slicing stays valid UTF-8.
        while (start < all.size && (all[start].toInt() and 0xC0) == 0x80) {
            start++
        }
        String(all, start, all.size - start, StandardCharsets.UTF_8)
    }

    companion object {
        const val CURRENT_FILE_NAME = "square-yuma.log"
        const val DEFAULT_MAX_FILE_BYTES: Long = 512L * 1024L
        const val DEFAULT_MAX_FILES: Int = 3
        const val DEFAULT_TAIL_BYTES: Int = 256 * 1024
        private val TIMESTAMP_FMT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}

/**
 * Tees stderr into an in-memory tail and a rolling file log under `~/.square-yuma/logs`,
 * and records uncaught exceptions before delegating.
 */
object LogTail {
    private val buf = ArrayDeque<String>()
    private val line = ByteArrayOutputStream()
    @Volatile
    private var installed = false
    @Volatile
    var fileLog: RollingFileLog = RollingFileLog()
        private set

    fun lines(): List<String> = synchronized(buf) { buf.toList() }

    fun install(logDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "logs")) {
        if (installed) return
        installed = true
        fileLog = RollingFileLog(logDir)
        fileLog.appendLine("=== Square-Yuma PC $AppVersion started ===")

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                fileLog.appendLine("UNCAUGHT [${thread.name}]: ${sw.toString().trimEnd()}")
            }
            prevHandler?.uncaughtException(thread, error)
        }

        val err = System.err
        System.setErr(
            PrintStream(
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        err.write(b)
                        synchronized(buf) {
                            if (b == '\n'.code) {
                                val text = line.toString(StandardCharsets.UTF_8)
                                line.reset()
                                buf.addLast(text)
                                if (buf.size > 300) buf.removeFirst()
                                fileLog.appendLine(text)
                            } else if (line.size() < 4000) {
                                line.write(b)
                            }
                        }
                    }
                },
                true,
                StandardCharsets.UTF_8,
            ),
        )
    }
}

/** Redacts Subsonic credentials, auth headers, tokens, emails, and explicit secret values from diagnostic text. */
object ReportRedactor {
    private val SHAPES = listOf(
        // Embedded credentials in URLs: https://user:pass@host
        Regex("""(https?://)([^:@/\s]+):([^@/\s]+)@""") to "$1<redacted>:<redacted>@",
        // Subsonic query params: u, t, s, p
        Regex("""([?&](?:u|t|s|p))=([^&\s"'#]+)""") to "$1=<redacted>",
        // JSON fields carrying Subsonic credentials or OAuth tokens
        Regex("""("(?:u|t|s|p|password|token|salt|client_secret|access_token|refresh_token|id_token)"\s*:\s*)"([^"]*)"""") to "$1\"<redacted>\"",
        // Key-value / settings entries with secret keys
        Regex("""(?im)^(\s*(?:subsonic_password|password|token|salt|auth_?header|authorization|proxy-authorization|x-api-key|x-auth-token|cookie|set-cookie|client_secret|api_?key|secret)\s*[=:]\s*)(.+)$""") to "$1<redacted>",
        // Authorization header values anywhere
        Regex("""(?im)\b((?:authorization|proxy-authorization|x-api-key|x-auth-token|cookie|set-cookie)\s*:\s*)([^\r\n]+)""") to "$1<redacted>",
        // Bearer / Basic tokens
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer <redacted>",
        Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]+""") to "Basic <redacted>",
        // Generic token= / code= / secret= pairs
        Regex("""(?i)((?:access|refresh|id)_token|client_secret|password|salt)(["']?\s*[=:]\s*["']?)([^\s,"'&;]+)""") to "$1$2<redacted>",
        // Email addresses
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
    )

    fun redact(
        text: String,
        extraSecrets: List<String> = emptyList(),
        redactNames: List<String> = emptyList(),
    ): String {
        var out = text
        extraSecrets
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { secret ->
                out = out.replace(secret, "<redacted>")
            }
        redactNames
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { name ->
                out = out.replace(name, "<account>", ignoreCase = true)
            }
        SHAPES.forEach { (pattern, replacement) ->
            out = pattern.replace(out, replacement)
        }
        return out
    }

    fun redactSettingsEntries(
        entries: Map<String, String>,
        includeFilePaths: Boolean,
        extraSecrets: List<String> = emptyList(),
        redactNames: List<String> = emptyList(),
    ): Map<String, String> {
        val secretKeyRegex = Regex("""(?i).*(password|token|salt|secret|auth|cookie|key).*""")
        val pathKeyRegex = Regex("""(?i).*(folder|path|tree_uri|dir).*""")
        return entries.entries.associate { (k, v) ->
            val value = when {
                secretKeyRegex.matches(k) && v.isNotBlank() -> "<redacted>"
                !includeFilePaths && pathKeyRegex.matches(k) && v.isNotBlank() -> "<omitted-path>"
                else -> redact(v, extraSecrets, redactNames)
            }
            k to value
        }
    }
}

/** Diagnostic zip builder for Settings → About → "Save a report". */
object DiagnosticsReport {
    data class Options(val includeFilePaths: Boolean = false)

    private val STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    suspend fun buildZip(
        state: AppState,
        options: Options = Options(),
        outputDir: Path = state.dataDir.resolve("reports"),
    ): File = withContext(Dispatchers.IO) {
        Files.createDirectories(outputDir)
        val stamp = STAMP_FMT.format(ZonedDateTime.now())
        val zipFile = outputDir.resolve("square-yuma-report-$AppVersion-$stamp.zip").toFile()

        val secrets = listOfNotNull(
            state.prefs.password.value.takeIf { it.isNotBlank() },
        )
        val names = listOfNotNull(
            state.prefs.username.value.takeIf { it.isNotBlank() },
        )

        val ping = if (state.prefs.isConfigured() && !state.offlineMode) {
            runCatching { state.client.ping().fold({ "ok (${it.type} ${it.serverVersion})" }, { "failed: ${it.message}" }) }
                .getOrElse { "failed: ${it.message}" }
        } else {
            if (state.offlineMode) "skipped (offline mode)" else "not configured"
        }

        val rawStatus = runCatching { state.player.statusJson() }.getOrElse { "{}" }
        val chain = AudioSignalChain.fromJson(rawStatus)
        val rawDevices = runCatching { NativeEngine.listDevices() }.getOrElse { "[]" }

        val displayScale = runCatching {
            if (GraphicsEnvironment.isHeadless()) "headless"
            else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toString()
        }.getOrElse { "unavailable" }

        val jvmArgs = runCatching {
            ProcessHandle.current().info().arguments().orElse(emptyArray()).joinToString(" ")
        }.getOrDefault("")

        val rt = Runtime.getRuntime()
        val summaryText = buildString {
            appendLine("Square-Yuma PC Diagnostics Report")
            appendLine("Generated: ${ZonedDateTime.now()}")
            appendLine("App version: $AppVersion")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("JVM: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
            if (jvmArgs.isNotBlank()) appendLine("Process args: $jvmArgs")
            appendLine("Heap MB: used=${(rt.totalMemory() - rt.freeMemory()) / 1_048_576}, total=${rt.totalMemory() / 1_048_576}, max=${rt.maxMemory() / 1_048_576}")
            appendLine("Renderer: ${state.renderer.label} (skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "default"}), displayScale=$displayScale")
            appendLine()
            appendLine("== Audio Output & NativeEngine.status() ==")
            appendLine("Selected mode: ${state.player.outputMode}")
            appendLine("Selected deviceId: ${state.player.outputDeviceId ?: "<system default>"}")
            appendLine("Engine initialized: ${chain.initialized}, playing: ${chain.playing}")
            appendLine("Device: ${chain.deviceName.ifBlank { "default" }} (${chain.deviceSampleRate} Hz, ${chain.deviceFormat}, ${chain.deviceChannels}ch)")
            appendLine("Exclusive active: ${chain.exclusiveActive}${if (chain.fallbackReason.isNotBlank()) " (fallback: ${chain.fallbackReason})" else ""}")
            appendLine("Source: ${chain.sourceCodec.ifBlank { "none" }} ${chain.sourceSampleRate} Hz ${chain.sourceBitsPerSample}-bit ${chain.sourceChannels}ch")
            appendLine("Buffer: ${chain.bufferedMs} ms, underruns: ${chain.underruns}")
            appendLine("Automix: smart=${state.smartAutomix}, mixing=${chain.mixing}, mixProgress=${"%.2f".format(chain.mixProgress)}")
            appendLine()
            appendLine("== Subsonic & Offline Cache ==")
            appendLine("Server: ${state.prefs.serverUrl.value.ifBlank { "not set" }} — ping: $ping")
            appendLine("Offline mode: ${state.offlineMode}, cache limit: ${if (state.cacheLimitGb == 0) "unlimited" else "${state.cacheLimitGb} GB"}")
            appendLine("Downloaded songs: ${state.downloads.kept.size}, size: ${state.downloads.bytes() / 1_048_576} MB")
            if (options.includeFilePaths) {
                appendLine("Downloads root: ${state.downloads.root.absolutePath}")
            }
            appendLine()
            appendLine("== Local Library Stats ==")
            val tracks = state.local.tracks.value
            val albumCount = tracks.map { LocalCatalog.albumKey(it) }.distinct().size
            val artistCount = tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().size
            val playlistCount = state.local.playlistStore.playlists.value.size
            appendLine("Root folders: ${state.local.folders.value.size}")
            appendLine("Tracks: ${tracks.size}, Albums: $albumCount, Artists: $artistCount, Local playlists: $playlistCount")
            appendLine("Listening history entries: ${state.listening.entries.size}")
            if (options.includeFilePaths) {
                state.local.folders.value.forEach { folder ->
                    appendLine("  folder: $folder")
                }
            }
        }.let { ReportRedactor.redact(it, secrets, names) }

        val nativeJson = runCatching {
            JSONObject()
                .put("status", runCatching { JSONObject(rawStatus) }.getOrDefault(rawStatus))
                .put("devices", runCatching { JSONArray(rawDevices) }.getOrDefault(rawDevices))
                .toString(2)
        }.getOrElse { "{}" }.let { ReportRedactor.redact(it, secrets, names) }

        val settingsMap = collectSettingsSnapshot(state)
        val redactedSettings = ReportRedactor.redactSettingsEntries(
            settingsMap,
            includeFilePaths = options.includeFilePaths,
            extraSecrets = secrets,
            redactNames = names,
        )
        val settingsText = redactedSettings.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "$k = $v" }

        val logText = buildString {
            val fileTail = LogTail.fileLog.readLastBytes(RollingFileLog.DEFAULT_TAIL_BYTES)
            if (fileTail.isNotBlank()) {
                append(fileTail)
            } else {
                LogTail.lines().forEach { appendLine(it) }
            }
        }.ifBlank { "No log lines recorded.\n" }.let { ReportRedactor.redact(it, secrets, names) }

        val selfCheckFile = state.dataDir.resolve("self-check.txt").toFile()
        val selfCheckText = if (selfCheckFile.isFile) {
            ReportRedactor.redact(selfCheckFile.readText(), secrets, names)
        } else {
            "No self-check result recorded.\n"
        }

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            fun putText(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            putText("summary.txt", summaryText)
            putText("native-status.json", nativeJson)
            putText("settings.txt", settingsText)
            putText("logs.txt", logText)
            putText("self-check.txt", selfCheckText)
        }

        zipFile
    }

    private fun collectSettingsSnapshot(state: AppState): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val subNode = Preferences.userRoot().node("square-yuma/subsonic")
        runCatching {
            subNode.keys().forEach { k ->
                out["subsonic.$k"] = subNode.get(k, "")
            }
        }
        out["subsonic.serverUrl"] = state.prefs.serverUrl.value
        out["subsonic.username"] = state.prefs.username.value
        out["subsonic.password"] = state.prefs.password.value
        runCatching {
            uiPrefs.keys().forEach { k ->
                out["ui.$k"] = uiPrefs.get(k, "")
            }
        }
        out["runtime.betaChannel"] = state.betaChannel.toString()
        out["runtime.glassProfile"] = state.glassProfile.name
        out["runtime.themeMode"] = state.themeMode.name
        out["runtime.language"] = state.language.code
        out["runtime.renderer"] = state.renderer.name
        out["runtime.outputMode"] = state.player.outputMode
        return out
    }
}
