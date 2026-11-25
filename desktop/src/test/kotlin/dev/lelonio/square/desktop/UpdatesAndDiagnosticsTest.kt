package dev.lelonio.square.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread

class UpdatesAndDiagnosticsTest {

    @Test
    fun semVerParsesAndComparesCorrectly() {
        val v199 = SemVer.parse("1.9.9")!!
        val v1100 = SemVer.parse("yuma-pc-v1.10.0")!!
        val v200Beta1 = SemVer.parse("yuma-pc-v2.0.0-beta.1")!!
        val v200Beta2 = SemVer.parse("2.0.0-beta.2")!!
        val v200Rc1 = SemVer.parse("2.0.0-rc.1")!!
        val v200 = SemVer.parse("2.0.0")!!

        assertTrue(v1100 > v199)
        assertTrue(v200Beta1 > v1100)
        assertTrue(v200Beta2 > v200Beta1)
        assertTrue(v200Rc1 > v200Beta2)
        assertTrue(v200 > v200Rc1)
        assertEquals(SemVer.parse("1.2"), SemVer.parse("1.2.0"))
        assertEquals(SemVer(2, 0, 0), SemVer.parse("v2.0.0+build.42"))
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse("1.2.3.4"))
    }

    @Test
    fun selectReleaseIgnoresAndroidDraftsAndPrereleasesUnlessBetaEnabled() {
        val sha = "a".repeat(64)
        val releasesJson = """
            [
              {
                "tag_name": "v9.9.9",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/tienspextre/Square-Yuma/releases/tag/v9.9.9",
                "body": "Android release",
                "assets": [
                  {"name": "square-9.9.9-arm64-v8a.apk", "browser_download_url": "https://example.com/app.apk", "size": 1234}
                ]
              },
              {
                "tag_name": "yuma-pc-v3.0.0",
                "draft": true,
                "prerelease": false,
                "html_url": "https://github.com/tienspextre/Square-Yuma/releases/tag/yuma-pc-v3.0.0",
                "body": "Draft",
                "assets": [
                  {"name": "Square-Yuma-PC-3.0.0-windows-x64.msi", "browser_download_url": "https://example.com/draft.msi", "size": 100}
                ]
              },
              {
                "tag_name": "yuma-pc-v2.1.0-beta.1",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/tienspextre/Square-Yuma/releases/tag/yuma-pc-v2.1.0-beta.1",
                "body": "Beta notes\n$sha  Square-Yuma-PC-2.1.0-beta.1-windows-x64.msi",
                "assets": [
                  {"name": "Square-Yuma-PC-2.1.0-beta.1-windows-x64.zip", "browser_download_url": "https://example.com/beta.zip", "size": 500},
                  {"name": "Square-Yuma-PC-2.1.0-beta.1-windows-x64.msi", "browser_download_url": "https://example.com/beta.msi", "size": 777}
                ]
              },
              {
                "tag_name": "yuma-pc-v2.0.1",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/tienspextre/Square-Yuma/releases/tag/yuma-pc-v2.0.1",
                "body": "Stable notes\nSHA-256: $sha",
                "assets": [
                  {"name": "Square-Yuma-PC-2.0.1-windows-x64.zip", "browser_download_url": "https://example.com/2.0.1.zip", "size": 999},
                  {"name": "Square-Yuma-PC-2.0.1-windows-x64.msi", "browser_download_url": "https://example.com/2.0.1.msi", "size": 888}
                ]
              }
            ]
        """.trimIndent()

        // Stable channel on Windows selects 2.0.1 MSI
        val stableWin = ReleaseSelector.selectRelease(releasesJson, "2.0.0", includeBeta = false, isWindows = true)
        assertNotNull(stableWin)
        assertEquals("2.0.1", stableWin!!.version)
        assertEquals("Square-Yuma-PC-2.0.1-windows-x64.msi", stableWin.fileName)
        assertEquals(888L, stableWin.expectedBytes)
        assertEquals(sha, stableWin.sha256)
        assertTrue(stableWin.canInstallInApp)

        // Beta channel on Windows selects 2.1.0-beta.1 MSI
        val betaWin = ReleaseSelector.selectRelease(releasesJson, "2.0.0", includeBeta = true, isWindows = true)
        assertNotNull(betaWin)
        assertEquals("2.1.0-beta.1", betaWin!!.version)
        assertTrue(betaWin.prerelease)
        assertEquals("Square-Yuma-PC-2.1.0-beta.1-windows-x64.msi", betaWin.fileName)
        assertEquals(777L, betaWin.expectedBytes)
        assertEquals(sha, betaWin.sha256)

        // Linux only offers opening the release page
        val linux = ReleaseSelector.selectRelease(releasesJson, "2.0.0", includeBeta = false, isWindows = false)
        assertNotNull(linux)
        assertEquals("2.0.1", linux!!.version)
        assertFalse(linux.canInstallInApp)
        assertEquals("https://github.com/tienspextre/Square-Yuma/releases/tag/yuma-pc-v2.0.1", linux.page)

        // Already up to date
        assertNull(ReleaseSelector.selectRelease(releasesJson, "2.0.1", includeBeta = false, isWindows = true))
        assertNull(ReleaseSelector.selectRelease(releasesJson, "2.1.0-beta.1", includeBeta = true, isWindows = true))
    }

    @Test
    fun autoCheckRespects24HourInterval() {
        val now = 1_700_000_000_000L
        assertTrue(ReleaseSelector.shouldAutoCheck(0L, now))
        assertFalse(ReleaseSelector.shouldAutoCheck(now - UPDATE_CHECK_INTERVAL_MS + 1_000L, now))
        assertTrue(ReleaseSelector.shouldAutoCheck(now - UPDATE_CHECK_INTERVAL_MS, now))
    }

    @Test
    fun redactorStripsSubsonicSecretsAuthHeadersAndPasswords() {
        val sample = """
            GET https://alice:SuperSecretPass99@nas.example.com/rest/stream.view?u=alice&t=0123456789abcdef0123456789abcdef&s=randomSalt42&p=enc:deadbeef&id=42
            Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig
            Cookie: session_id=xyz123secret
            {"u":"alice","t":"deadbeefcafe","s":"mysalt99","p":"MyRawPassword!"}
            subsonic_password = MyRawPassword!
            email: user@example.org
        """.trimIndent()

        val redacted = ReportRedactor.redact(
            sample,
            extraSecrets = listOf("MyRawPassword!", "SuperSecretPass99"),
            redactNames = listOf("alice"),
        )

        assertFalse(redacted.contains("MyRawPassword!"))
        assertFalse(redacted.contains("SuperSecretPass99"))
        assertFalse(redacted.contains("0123456789abcdef0123456789abcdef"))
        assertFalse(redacted.contains("randomSalt42"))
        assertFalse(redacted.contains("enc:deadbeef"))
        assertFalse(redacted.contains("eyJhbGciOi"))
        assertFalse(redacted.contains("xyz123secret"))
        assertFalse(redacted.contains("deadbeefcafe"))
        assertFalse(redacted.contains("mysalt99"))
        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("user@example.org"))
        assertTrue(redacted.contains("u=<redacted>"))
        assertTrue(redacted.contains("t=<redacted>"))
        assertTrue(redacted.contains("s=<redacted>"))
        assertTrue(redacted.contains("Authorization: <redacted>"))
    }

    @Test
    fun redactorRedactsSettingsEntriesAndRespectsIncludeFilePaths() {
        val rawSettings = mapOf(
            "subsonic.subsonic_server_url" to "https://music.example.com",
            "subsonic.subsonic_username" to "bobuser",
            "subsonic.subsonic_password" to "hunter2",
            "subsonic.subsonic_download_tree_uri" to "/home/bob/Music",
            "ui.theme" to "DARK",
        )
        val noPaths = ReportRedactor.redactSettingsEntries(
            rawSettings,
            includeFilePaths = false,
            extraSecrets = listOf("hunter2"),
            redactNames = listOf("bobuser"),
        )
        assertEquals("<redacted>", noPaths["subsonic.subsonic_password"])
        assertEquals("<account>", noPaths["subsonic.subsonic_username"])
        assertEquals("<omitted-path>", noPaths["subsonic.subsonic_download_tree_uri"])
        assertEquals("DARK", noPaths["ui.theme"])

        val withPaths = ReportRedactor.redactSettingsEntries(
            rawSettings,
            includeFilePaths = true,
            extraSecrets = listOf("hunter2"),
            redactNames = listOf("bobuser"),
        )
        assertEquals("<redacted>", withPaths["subsonic.subsonic_password"])
        assertEquals("/home/bob/Music", withPaths["subsonic.subsonic_download_tree_uri"])
    }

    @Test
    fun rollingFileLogRotatesAndReadsLastNBytes() {
        val dir = Files.createTempDirectory("square-log-test-")
        try {
            val log = RollingFileLog(dir, maxFileBytes = 256L, maxFiles = 3)
            repeat(40) { i ->
                log.appendLine("line-$i " + "x".repeat(40))
            }
            val files = Files.list(dir).use { it.toList() }
            assertTrue("Expected rotated log files, got ${files.size}", files.size in 2..3)
            val tail = log.readLastBytes(200)
            assertTrue(tail.isNotEmpty())
            assertTrue(tail.toByteArray().size <= 200)
            assertTrue(tail.contains("line-39"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadAndVerifyUpdateChecksSizeAndSha256() {
        val payload = "fake-msi-installer-binary-payload-1234567890".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(),
                    )
                    out.write(payload)
                    out.flush()
                }
            }
        }
        val tmpDir = Files.createTempDirectory("square-update-dl-test-")
        try {
            val baseUrl = "http://127.0.0.1:${server.localPort}/Square-Yuma-PC-2.0.0-windows-x64.msi"
            val goodUpdate = Update(
                version = "2.0.0",
                tagName = "yuma-pc-v2.0.0",
                url = baseUrl,
                fileName = "Square-Yuma-PC-2.0.0-windows-x64.msi",
                expectedBytes = payload.size.toLong(),
                sha256 = sha256,
                checksumUrl = null,
                page = "https://example.com",
                notes = "",
                prerelease = false,
            )
            val downloaded = runBlocking { downloadAndVerifyUpdate(goodUpdate, tmpDir) {} }
            assertTrue(downloaded.isFile)
            assertEquals(payload.size.toLong(), downloaded.length())

            val badSize = goodUpdate.copy(fileName = "bad-size.msi", expectedBytes = payload.size + 10L)
            var sizeFailed = false
            try {
                runBlocking { downloadAndVerifyUpdate(badSize, tmpDir) {} }
            } catch (_: IOException) {
                sizeFailed = true
            }
            assertTrue("Expected size mismatch IOException", sizeFailed)
            assertFalse(tmpDir.resolve("bad-size.msi").toFile().exists())

            val badSha = goodUpdate.copy(fileName = "bad-sha.msi", sha256 = "0".repeat(64))
            var shaFailed = false
            try {
                runBlocking { downloadAndVerifyUpdate(badSha, tmpDir) {} }
            } catch (_: IOException) {
                shaFailed = true
            }
            assertTrue("Expected SHA-256 mismatch IOException", shaFailed)
            assertFalse(tmpDir.resolve("bad-sha.msi").toFile().exists())
        } finally {
            server.close()
            t.join(1000)
            tmpDir.toFile().deleteRecursively()
        }
    }
}
