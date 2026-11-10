package dev.lelonio.square.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File

/** This build's version, set by the launcher (build.gradle.kts jvmArgs). */
val AppVersion: String = System.getProperty("square.version") ?: "0"

/** A newer PC release and the file this computer should install. */
data class Update(val version: String, val url: String, val fileName: String, val page: String)

/**
 * The newest Square-Yuma PC release on GitHub. Only `yuma-pc-v*` tags count:
 * the phone's releases share the repository and are never offered here.
 */
suspend fun checkForUpdate(): Update? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("https://api.github.com/repos/tienspextre/Square-Yuma/releases?per_page=40")
        .header("Accept", "application/vnd.github+json").build()
    val body = Http.client.newCall(req).execute().use { if (it.isSuccessful) it.body.string() else return@withContext null }
    val releases = JSONArray(body)
    val windows = System.getProperty("os.name").startsWith("Windows")
    val deb = !windows && File("/usr/bin/dpkg").exists()
    val wanted = when { windows -> "-windows-x64.msi"; deb -> "-amd64.deb"; else -> "-linux-x64.tar.gz" }
    (0 until releases.length()).map { releases.getJSONObject(it) }
        .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").startsWith("yuma-pc-v") }
        .maxByOrNull { versionKey(it.getString("tag_name").removePrefix("yuma-pc-v")) }
        ?.takeIf { versionKey(it.getString("tag_name").removePrefix("yuma-pc-v")) > versionKey(AppVersion) }
        ?.let { r ->
            val assets = r.getJSONArray("assets")
            val a = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(wanted) }
                ?: return@let null
            Update(r.getString("tag_name").removePrefix("yuma-pc-v"), a.getString("browser_download_url"), a.getString("name"), r.getString("html_url"))
        }
}

/** "1.10.2" sorts after "1.9.9". */
internal fun versionKey(v: String): Long = v.split('.', '-').take(3).map { it.toLongOrNull() ?: 0 }
    .let { (it + listOf(0L, 0L, 0L)).take(3) }.fold(0L) { acc, n -> acc * 100_000 + n }

/** Downloads the installer to ~/Downloads and hands it to the system: Windows Installer, or the Linux software centre. */
suspend fun installUpdate(u: Update, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
    val file = File(dir, u.fileName)
    Http.client.newCall(Request.Builder().url(u.url).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        val total = r.body.contentLength()
        r.body.byteStream().use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
    }
    if (System.getProperty("os.name").startsWith("Windows")) ProcessBuilder("msiexec", "/i", file.path).start()
    else if (!file.name.endsWith(".tar.gz")) ProcessBuilder("xdg-open", file.path).start()
    else ProcessBuilder("xdg-open", dir.path).start()
    file
}

/** Everything worth knowing when something goes wrong, without the password. */
suspend fun diagnostics(state: AppState): File = withContext(Dispatchers.IO) {
    fun run(vararg cmd: String) = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().inputStream.bufferedReader().readText().lineSequence().firstOrNull().orEmpty()
    }.getOrElse { "unavailable: ${it.message}" }
    val ping = runCatching { state.client.ping().fold({ "ok" }, { "failed: ${it.message}" }) }.getOrElse { "failed: ${it.message}" }
    val text = buildString {
        appendLine("Square-Yuma PC diagnostics, ${java.time.ZonedDateTime.now()}")
        appendLine("Version: $AppVersion")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        appendLine("Renderer: ${state.renderer.label} (skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "default"})")
        appendLine("Display scale: ${java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX}")
        appendLine("ffmpeg: ${DesktopPlayer.ffmpegPath} — ${run(DesktopPlayer.ffmpegPath, "-version")}")
        appendLine("Server: ${state.prefs.serverUrl.value.ifBlank { "not set" }} — ping $ping")
        appendLine("Language: ${state.language.label}, font: ${if (state.systemFont) "system" else "Inter"}")
        appendLine("Audio filter: ${state.player.audioFilter ?: "none"}")
        appendLine("Downloads: ${state.downloads.kept.size} songs, ${state.downloads.bytes() / 1_048_576} MB in ${state.downloads.root}")
        appendLine("Listening entries: ${state.listening.entries.size}")
        appendLine("Memory: ${Runtime.getRuntime().totalMemory() / 1_048_576} MB of ${Runtime.getRuntime().maxMemory() / 1_048_576} MB")
        appendLine()
        appendLine("Recent log:")
        LogTail.lines().forEach { appendLine(it) }
    }
    val file = File(System.getProperty("user.home"), "square-yuma-diagnostics.txt")
    file.writeText(text)
    file
}

/** The last few hundred lines the app wrote to stderr, for the diagnostics report. */
object LogTail {
    private val buf = ArrayDeque<String>()
    private val line = java.io.ByteArrayOutputStream()

    fun lines(): List<String> = synchronized(buf) { buf.toList() }

    /** Keeps stderr going to the terminal while copying it here. */
    fun install() {
        val err = System.err
        System.setErr(java.io.PrintStream(object : java.io.OutputStream() {
            override fun write(b: Int) {
                err.write(b)
                synchronized(buf) {
                    if (b == '\n'.code) { buf.addLast(line.toString(Charsets.UTF_8)); line.reset(); if (buf.size > 300) buf.removeFirst() }
                    else if (line.size() < 4000) line.write(b)
                }
            }
        }, true))
    }
}

internal fun versionCheck() {
    check(versionKey("1.10.0") > versionKey("1.9.9"))
    check(versionKey("1.3.0") > versionKey("1.2.0"))
    check(versionKey("1.2.0") == versionKey("1.2"))
}
