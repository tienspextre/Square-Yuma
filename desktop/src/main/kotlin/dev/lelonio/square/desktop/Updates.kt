package dev.lelonio.square.desktop

import dev.lelonio.square.common.executeCancellable
import dev.lelonio.square.desktop.ui.components.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** This build's version, set by the launcher (build.gradle.kts jvmArgs). */
val AppVersion: String = System.getProperty("square.version") ?: "0"

const val UPDATE_CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
private const val RELEASES_API_URL = "https://api.github.com/repos/tienspextre/Square-Yuma/releases?per_page=40"

internal fun isWindowsOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Windows", ignoreCase = true)

/**
 * Semantic version parser & comparator supporting `major.minor.patch[-prerelease][+build]`
 * as well as `yuma-pc-v` / `v` tag prefixes.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        val count = minOf(prerelease.size, other.prerelease.size)
        for (i in 0 until count) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            if (a == b) continue
            val aNum = a.toLongOrNull()
            val bNum = b.toLongOrNull()
            return when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1 // numeric identifiers have lower precedence than non-numeric
                bNum != null -> 1
                else -> a.compareTo(b)
            }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) {
            append('-').append(prerelease.joinToString("."))
        }
    }

    companion object {
        fun parse(raw: String): SemVer? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("yuma-pc-v", ignoreCase = true)) {
                s = s.substring("yuma-pc-v".length)
            } else if (s.startsWith("yuma-pc-", ignoreCase = true)) {
                s = s.substring("yuma-pc-".length)
            } else if (s.startsWith("v", ignoreCase = true)) {
                s = s.substring(1)
            }
            s = s.substringBefore('+').trim()
            if (s.isEmpty()) return null

            val dash = s.indexOf('-')
            val corePart = if (dash >= 0) s.substring(0, dash) else s
            val prePart = if (dash >= 0) s.substring(dash + 1) else ""

            val parts = corePart.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { part ->
                if (part.isEmpty() || !part.all(Char::isDigit)) return null
                part.toIntOrNull() ?: return null
            }
            val pre = if (dash >= 0) {
                if (prePart.isEmpty()) return null
                prePart.split('.').also { list ->
                    if (list.any { it.isEmpty() }) return null
                }
            } else {
                emptyList()
            }
            return SemVer(
                major = nums[0],
                minor = nums.getOrElse(1) { 0 },
                patch = nums.getOrElse(2) { 0 },
                prerelease = pre,
            )
        }
    }
}

/** A newer PC release and the installer/page details for this platform. */
data class Update(
    val version: String,
    val tagName: String,
    val url: String?,
    val fileName: String?,
    val expectedBytes: Long,
    val sha256: String?,
    val checksumUrl: String?,
    val page: String,
    val notes: String,
    val prerelease: Boolean,
) {
    val canInstallInApp: Boolean get() = !url.isNullOrBlank() && !fileName.isNullOrBlank()
}

internal object ReleaseSelector {
    fun shouldAutoCheck(lastCheckMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastCheckMs <= 0L || (nowMs - lastCheckMs) >= UPDATE_CHECK_INTERVAL_MS

    fun selectRelease(
        releasesJson: String,
        currentVersion: String,
        includeBeta: Boolean,
        isWindows: Boolean = isWindowsOs(),
    ): Update? {
        val current = SemVer.parse(currentVersion) ?: SemVer(0, 0, 0)
        val arr = JSONArray(releasesJson)
        data class Candidate(val obj: JSONObject, val tag: String, val version: String, val semVer: SemVer, val isPre: Boolean)

        val best = (0 until arr.length())
            .asSequence()
            .map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft", false) }
            .mapNotNull { obj ->
                val tag = obj.optString("tag_name", "")
                if (!tag.startsWith("yuma-pc-v")) return@mapNotNull null
                val verStr = tag.removePrefix("yuma-pc-v")
                val sv = SemVer.parse(verStr) ?: return@mapNotNull null
                val isPre = obj.optBoolean("prerelease", false) || sv.isPrerelease
                if (!includeBeta && isPre) return@mapNotNull null
                Candidate(obj, tag, verStr, sv, isPre)
            }
            .maxByOrNull { it.semVer }
            ?.takeIf { it.semVer > current }
            ?: return null

        val htmlUrl = best.obj.optString("html_url", "https://github.com/tienspextre/Square-Yuma/releases")
        val notes = best.obj.optString("body", "").trim()

        if (!isWindows) {
            // On Linux, only offer opening the GitHub release page.
            return Update(
                version = best.version,
                tagName = best.tag,
                url = null,
                fileName = null,
                expectedBytes = 0L,
                sha256 = null,
                checksumUrl = null,
                page = htmlUrl,
                notes = notes,
                prerelease = best.isPre,
            )
        }

        val assets = best.obj.optJSONArray("assets") ?: return null
        val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }
        val expectedMsiName = "Square-Yuma-PC-${best.version}-windows-x64.msi"
        val msiAsset = assetList.firstOrNull {
            it.optString("name").equals(expectedMsiName, ignoreCase = true)
        } ?: assetList.firstOrNull {
            it.optString("name").endsWith("-windows-x64.msi", ignoreCase = true)
        } ?: return null

        val assetName = msiAsset.optString("name")
        val downloadUrl = msiAsset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return null
        val sizeBytes = msiAsset.optLong("size", 0L).coerceAtLeast(0L)

        val shaFromBody = extractSha256FromReleaseBody(notes, assetName)
        val checksumAsset = assetList.firstOrNull { a ->
            val n = a.optString("name")
            n.equals("$assetName.sha256", ignoreCase = true) ||
                n.equals("SHA256SUMS.txt", ignoreCase = true) ||
                n.equals("SHA256SUMS", ignoreCase = true) ||
                n.equals("checksums.txt", ignoreCase = true)
        }
        val checksumUrl = checksumAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }

        return Update(
            version = best.version,
            tagName = best.tag,
            url = downloadUrl,
            fileName = assetName,
            expectedBytes = sizeBytes,
            sha256 = shaFromBody,
            checksumUrl = checksumUrl,
            page = htmlUrl,
            notes = notes,
            prerelease = best.isPre,
        )
    }

    private val hex64Regex = Regex("""\b([0-9a-fA-F]{64})\b""")

    fun extractSha256FromReleaseBody(body: String, assetName: String): String? {
        if (body.isBlank()) return null
        val lines = body.lineSequence().toList()
        // Prefer a line explicitly mentioning the asset name
        lines.firstOrNull { it.contains(assetName, ignoreCase = true) }?.let { line ->
            hex64Regex.find(line)?.let { return it.groupValues[1].lowercase(Locale.ROOT) }
        }
        // Fallback: explicit sha256 label anywhere in the body
        val labeled = Regex("""(?i)sha-?256\s*[:=]\s*([0-9a-fA-F]{64})\b""")
        labeled.find(body)?.let { return it.groupValues[1].lowercase(Locale.ROOT) }
        return null
    }

    fun parseSha256Text(text: String, assetName: String): String? {
        if (text.isBlank()) return null
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstOrNull { it.contains(assetName, ignoreCase = true) }?.let { line ->
            hex64Regex.find(line)?.let { return it.groupValues[1].lowercase(Locale.ROOT) }
        }
        if (lines.size == 1) {
            hex64Regex.find(lines.first())?.let { return it.groupValues[1].lowercase(Locale.ROOT) }
        }
        return null
    }
}

/**
 * Queries GitHub Releases for the newest `yuma-pc-v*` release (ignoring Android `v*`, drafts,
 * and prereleases unless [includeBeta] is enabled).
 */
suspend fun checkForUpdate(includeBeta: Boolean = false): Update? = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url(RELEASES_API_URL)
        .header("Accept", "application/vnd.github+json")
        .build()
    val body = Http.client.executeCancellable(req) { if (it.isSuccessful) it.body.string() else error("HTTP ${it.code}") }
    val selected = ReleaseSelector.selectRelease(body, AppVersion, includeBeta) ?: return@withContext null
    if (selected.sha256 == null && selected.checksumUrl != null && selected.fileName != null) {
        val sumText = runCatching {
            Http.client.executeCancellable(Request.Builder().url(selected.checksumUrl).build()) { r ->
                if (r.isSuccessful) r.body.string() else null
            }
        }.getOrNull()
        val parsed = sumText?.let { ReleaseSelector.parseSha256Text(it, selected.fileName) }
        if (parsed != null) return@withContext selected.copy(sha256 = parsed)
    }
    selected
}

/**
 * Downloads [u]'s MSI installer into a temporary directory with progress, verifies byte size
 * and SHA-256 (when available), then launches `msiexec /i <file>` and triggers [onExit].
 */
suspend fun downloadAndVerifyUpdate(
    u: Update,
    tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "square-yuma-updates"),
    progress: (Float?) -> Unit,
): File = withContext(Dispatchers.IO) {
    val url = u.url ?: error("No installer URL for this platform")
    val fileName = u.fileName ?: error("No installer filename")
    Files.createDirectories(tempRoot)
    val target = tempRoot.resolve(fileName).toFile()
    val part = tempRoot.resolve("$fileName.part").toFile()
    part.delete()

    Http.client.executeCancellable(Request.Builder().url(url).build()) { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        val total = r.body.contentLength().takeIf { it > 0 } ?: u.expectedBytes
        val digest = MessageDigest.getInstance("SHA-256")
        r.body.byteStream().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f)) else progress(null)
                }
            }
        }
        if (u.expectedBytes > 0L && part.length() != u.expectedBytes) {
            val actual = part.length()
            part.delete()
            throw IOException("Installer size mismatch: expected ${u.expectedBytes} bytes, got $actual")
        }
        val expectedSha = u.sha256?.trim()?.lowercase(Locale.ROOT)
        if (!expectedSha.isNullOrEmpty()) {
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != expectedSha) {
                part.delete()
                throw IOException("Installer SHA-256 mismatch")
            }
        }
    }

    try {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    target
}

fun launchWindowsInstallerAndExit(file: File, onExit: () -> Unit) {
    ProcessBuilder("msiexec", "/i", file.absolutePath).start()
    onExit()
}

fun openReleasePage(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return
        }
    }
    if (isWindowsOs()) {
        ProcessBuilder("cmd", "/c", "start", "", url).start()
    } else {
        ProcessBuilder("xdg-open", url).start()
    }
}

internal fun versionCheck() {
    check(SemVer.parse("1.10.0")!! > SemVer.parse("1.9.9")!!)
    check(SemVer.parse("2.0.0")!! > SemVer.parse("2.0.0-beta.1")!!)
    check(SemVer.parse("1.2.0")!! == SemVer.parse("1.2")!!)
}
