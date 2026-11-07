package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.LyricsCredit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

object DesktopLyricsRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Lyrics>()

    private val BETTER_LYRICS_ENDPOINTS = listOf(
        "getLyrics",
        "kugou/getLyrics",
        "qq/getLyrics",
    )

    suspend fun fetchLyrics(
        trackUri: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        localFilePath: String? = null,
    ): Lyrics? = withContext(Dispatchers.IO) {
        cache[trackUri]?.let { return@withContext it }

        // 1. Check sidecar .lrc file if playing a local file
        if (!localFilePath.isNullOrBlank()) {
            val audioFile = File(localFilePath)
            val lrcFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
            if (lrcFile.exists() && lrcFile.isFile) {
                runCatching { parseLrc(lrcFile.readText(), source = "Local .lrc") }
                    .getOrNull()
                    ?.takeIf { it.lines.isNotEmpty() }
                    ?.also { cache[trackUri] = it }
                    ?.let { return@withContext it }
            }
        }

        val cleanTitle = cleanSongTitle(title)
        val primaryArtist = primaryArtist(artist)

        // 2. Try BetterLyrics (Apple Music / TTML word-by-word karaoke syllables)
        fetchBetterLyrics(cleanTitle, primaryArtist, durationMs)?.let {
            cache[trackUri] = it
            return@withContext it
        }

        // 3. Try LrcLib (synced LRC & plain fallback)
        fetchLrcLib(cleanTitle, artist, primaryArtist, album, durationMs)?.let {
            cache[trackUri] = it
            return@withContext it
        }

        null
    }

    private fun fetchBetterLyrics(title: String, artist: String, durationMs: Long): Lyrics? {
        if (title.isBlank()) return null
        for (endpoint in BETTER_LYRICS_ENDPOINTS) {
            val url = buildString {
                append("https://lyrics-api.boidu.dev/").append(endpoint)
                append("?s=").append(encode(title))
                append("&a=").append(encode(artist))
                if (durationMs > 0) append("&d=").append(durationMs / 1000)
            }
            val body = httpGet(url) ?: continue
            val ttml = runCatching { JSONObject(body).optString("ttml") }.getOrNull()
                ?.takeIf { it.trim().startsWith("<") } ?: continue
            val parsed = parseTtml(ttml)
            if (parsed != null && parsed.lines.isNotEmpty()) {
                return parsed
            }
        }
        return null
    }

    private fun fetchLrcLib(
        cleanTitle: String,
        fullArtist: String,
        primaryArtist: String,
        album: String,
        durationMs: Long,
    ): Lyrics? {
        val durSec = if (durationMs > 0) (durationMs / 1000).toInt() else null
        val urls = buildList {
            if (durSec != null) {
                add("https://lrclib.net/api/get?track_name=${encode(cleanTitle)}&artist_name=${encode(fullArtist)}&duration=$durSec")
                if (primaryArtist != fullArtist) {
                    add("https://lrclib.net/api/get?track_name=${encode(cleanTitle)}&artist_name=${encode(primaryArtist)}&duration=$durSec")
                }
            }
            add("https://lrclib.net/api/get?track_name=${encode(cleanTitle)}&artist_name=${encode(primaryArtist)}")
        }

        for (u in urls) {
            val body = httpGet(u) ?: continue
            parseLrcLibJson(body)?.let { return it }
        }

        // Fallback search
        val q = "$cleanTitle $primaryArtist".trim()
        val searchRaw = httpGet("https://lrclib.net/api/search?q=${encode(q)}") ?: return null
        val arr = runCatching { JSONArray(searchRaw) }.getOrNull() ?: return null
        val targetSec = durationMs / 1000.0
        var bestSynced: Lyrics? = null
        var bestPlain: Lyrics? = null
        var minDiff = Double.MAX_VALUE

        for (i in 0 until arr.length().coerceAtMost(10)) {
            val item = arr.optJSONObject(i) ?: continue
            val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (synced == null && plain == null) continue
            val itemDur = item.optDouble("duration", 0.0)
            val diff = if (targetSec > 0 && itemDur > 0) abs(itemDur - targetSec) else 0.0
            if (synced != null && diff < minDiff) {
                parseLrc(synced, source = "LRCLIB")?.let {
                    bestSynced = it
                    minDiff = diff
                }
            } else if (bestSynced == null && bestPlain == null && plain != null) {
                bestPlain = Lyrics(
                    lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(null, it.trim()) },
                    synced = false,
                    source = "LRCLIB",
                    credit = LyricsCredit(provider = "LRCLIB"),
                )
            }
        }
        return bestSynced ?: bestPlain
    }

    private fun parseLrcLibJson(body: String): Lyrics? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        json.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let {
            return parseLrc(it, source = "LRCLIB")
        }
        json.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plain ->
            return Lyrics(
                lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(null, it.trim()) },
                synced = false,
                source = "LRCLIB",
                credit = LyricsCredit(provider = "LRCLIB"),
            )
        }
        return null
    }

    fun parseTtml(raw: String): Lyrics? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        val pNodes = doc.getElementsByTagName("p")
        if (pNodes.length == 0) return null

        val lines = mutableListOf<LyricLine>()
        var firstAgent: String? = null

        for (i in 0 until pNodes.length) {
            val p = pNodes.item(i) as? Element ?: continue
            val lineStart = parseTimeMs(p.getAttribute("begin"))
            val lineEnd = parseTimeMs(p.getAttribute("end"))
            val agent = p.getAttribute("ttm:agent").ifBlank { p.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent") }
            if (agent.isNotBlank() && firstAgent == null) {
                firstAgent = agent
            }
            val secondVoice = agent.isNotBlank() && firstAgent != null && agent != firstAgent

            val words = mutableListOf<LyricWord>()
            val textBuilder = StringBuilder()

            val children = p.childNodes
            for (c in 0 until children.length) {
                val node = children.item(c)
                when (node.nodeType) {
                    Node.TEXT_NODE -> {
                        textBuilder.append(node.textContent)
                    }
                    Node.ELEMENT_NODE -> {
                        val el = node as Element
                        val role = el.getAttribute("ttm:role").ifBlank {
                            el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
                        }
                        if (role == "x-translation" || role == "x-roman") continue
                        val isBg = role == "x-bg"
                        val wStart = parseTimeMs(el.getAttribute("begin"))
                        val wEnd = parseTimeMs(el.getAttribute("end"))
                        val rawWordText = el.textContent ?: ""
                        // Check if following sibling is whitespace
                        val nextNode = children.item(c + 1)
                        val trailingSpace = if (nextNode?.nodeType == Node.TEXT_NODE && nextNode.textContent.startsWith(" ")) " " else ""
                        val wordText = (rawWordText + trailingSpace).ifEmpty { rawWordText }
                        if (!isBg) {
                            textBuilder.append(rawWordText)
                        }
                        if (wStart != null && wEnd != null && rawWordText.isNotBlank()) {
                            words += LyricWord(
                                startMs = wStart,
                                endMs = wEnd.coerceAtLeast(wStart + 40L),
                                text = wordText.trimEnd().let { if (trailingSpace.isNotEmpty() || !rawWordText.endsWith(" ")) "$it " else it },
                                background = isBg,
                            )
                        }
                    }
                }
            }

            val fullText = textBuilder.toString().replace(Regex("\\s+"), " ").trim()
            val effectiveStart = lineStart ?: words.firstOrNull()?.startMs
            if (effectiveStart != null && fullText.isNotBlank()) {
                lines += LyricLine(
                    startTimeMs = effectiveStart,
                    text = fullText,
                    words = words,
                    secondVoice = secondVoice,
                )
            }
        }

        if (lines.isEmpty()) return null
        Lyrics(
            lines = lines.sortedBy { it.startTimeMs ?: 0L },
            synced = true,
            source = "BetterLyrics (TTML Word-Sync)",
            credit = LyricsCredit(provider = "Apple Music / BetterLyrics TTML"),
        )
    }.getOrNull()

    private val LRC_LINE_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?](.*)""")
    private val WORD_TAG_REGEX = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>([^<]+)""")

    fun parseLrc(raw: String, source: String = "LRCLIB"): Lyrics? {
        val parsedLines = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val match = LRC_LINE_REGEX.matchEntire(line.trim()) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: continue
            val sec = match.groupValues[2].toLongOrNull() ?: continue
            val fracStr = match.groupValues[3]
            val fracMs = when (fracStr.length) {
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                3 -> fracStr.toLongOrNull() ?: 0L
                else -> 0L
            }
            val startMs = min * 60_000L + sec * 1_000L + fracMs
            val content = match.groupValues[4].trim()
            if (content.isEmpty()) continue

            val wordMatches = WORD_TAG_REGEX.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = mutableListOf<LyricWord>()
                val sb = StringBuilder()
                for (i in wordMatches.indices) {
                    val wm = wordMatches[i]
                    val wMin = wm.groupValues[1].toLongOrNull() ?: 0L
                    val wSec = wm.groupValues[2].toLongOrNull() ?: 0L
                    val wFracStr = wm.groupValues[3]
                    val wFrac = when (wFracStr.length) {
                        1 -> (wFracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (wFracStr.toLongOrNull() ?: 0L) * 10L
                        3 -> wFracStr.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val wStart = wMin * 60_000L + wSec * 1_000L + wFrac
                    val wText = wm.groupValues[4]
                    val nextStart = if (i + 1 < wordMatches.size) {
                        val nm = wordMatches[i + 1]
                        val nMin = nm.groupValues[1].toLongOrNull() ?: 0L
                        val nSec = nm.groupValues[2].toLongOrNull() ?: 0L
                        nMin * 60_000L + nSec * 1_000L
                    } else {
                        wStart + 400L
                    }
                    sb.append(wText)
                    words += LyricWord(
                        startMs = wStart,
                        endMs = nextStart.coerceAtLeast(wStart + 80L),
                        text = wText,
                    )
                }
                parsedLines += LyricLine(
                    startTimeMs = startMs,
                    text = sb.toString().trim(),
                    words = words,
                )
            } else {
                parsedLines += LyricLine(
                    startTimeMs = startMs,
                    text = content,
                )
            }
        }
        if (parsedLines.isEmpty()) return null
        return Lyrics(
            lines = parsedLines.sortedBy { it.startTimeMs ?: 0L },
            synced = true,
            source = source,
            credit = LyricsCredit(provider = source),
        )
    }

    private fun parseTimeMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.endsWith("s")) {
            val sec = s.removeSuffix("s").toDoubleOrNull() ?: return null
            return (sec * 1000.0).toLong()
        }
        val parts = s.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                h * 3_600_000L + m * 60_000L + (sec * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                m * 60_000L + (sec * 1000.0).toLong()
            }
            1 -> {
                val sec = parts[0].toDoubleOrNull() ?: return null
                (sec * 1000.0).toLong()
            }
            else -> null
        }
    }

    private fun cleanSongTitle(title: String): String =
        title
            .replace(Regex("""\s*\((?:feat\.?|ft\.?|with|prod\.?|remaster(?:ed)?|live|deluxe|bonus)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[(?:feat\.?|ft\.?|with|remaster(?:ed)?|live|deluxe)[^\]]*]""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { title.trim() }

    private fun primaryArtist(artist: String): String =
        artist.split(",", ";", "&", " feat.", " ft.", " x ", ignoreCase = true)
            .firstOrNull()
            ?.trim()
            ?.ifEmpty { artist.trim() }
            ?: artist.trim()

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "SquareYumaDesktop/1.0.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")
}
