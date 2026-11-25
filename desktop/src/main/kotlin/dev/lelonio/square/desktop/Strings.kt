package dev.lelonio.square.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.w3c.dom.Element
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

enum class Language(val code: String, val label: String) {
    English("en", "English"),
    German("de", "Deutsch"),
    Spanish("es", "Español"),
    French("fr", "Français"),
    Hindi("hi", "हिन्दी"),
    Italian("it", "Italiano"),
    Japanese("ja", "日本語"),
    Korean("ko", "한국어"),
    Portuguese("pt", "Português"),
    Russian("ru", "Русский"),
    Turkish("tr", "Türkçe"),
    Vietnamese("vi", "Tiếng Việt");

    companion object {
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: English
    }
}

val TranslationLanguages: List<Pair<String, String>> =
    Language.entries.map { it.code to it.label } + listOf(
        "zh-CN" to "中文",
        "th" to "ไทย",
        "id" to "Bahasa Indonesia",
    )

/**
 * Runtime localization loader:
 * - Reads Android's `strings.xml` packaged by `sharedResources` (`android-res/values[-lang]/strings.xml`)
 * - Overlays desktop-only strings from `i18n/values[-lang]/strings.xml`
 * - Falls back to English (`en`) per missing key or unknown language
 * - Formats Android positional/standard format specifiers (`%1$s`, `%1$d`, `%s`, `%d`, etc.)
 */
object Strings {
    var current by mutableStateOf(Language.English)

    private val androidCache = ConcurrentHashMap<String, Map<String, String>>()
    private val desktopCache = ConcurrentHashMap<String, Map<String, String>>()
    private val mergedCache = ConcurrentHashMap<String, Map<String, String>>()

    fun str(key: String, vararg args: Any): String = formatForLanguage(current.code, key, *args)

    fun formatForLanguage(code: String, key: String, vararg args: Any): String {
        val lang = Language.fromCode(code)
        val map = loadLanguage(lang.code)
        val enMap = loadLanguage(Language.English.code)
        val raw = map[key] ?: enMap[key] ?: return key
        if (args.isEmpty()) return raw
        val locale = Locale.forLanguageTag(lang.code)
        return runCatching { String.format(locale, raw, *args) }
            .getOrElse {
                val fallback = enMap[key] ?: return raw
                runCatching { String.format(Locale.ENGLISH, fallback, *args) }.getOrDefault(raw)
            }
    }

    fun loadLanguage(code: String): Map<String, String> {
        val normalized = Language.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }?.code ?: "en"
        return mergedCache.getOrPut(normalized) {
            val enAndroid = androidStrings("en")
            val enDesktop = desktopStrings("en")
            val langAndroid = if (normalized == "en") emptyMap() else androidStrings(normalized)
            val langDesktop = if (normalized == "en") emptyMap() else desktopStrings(normalized)
            LinkedHashMap<String, String>(enAndroid.size + enDesktop.size).apply {
                putAll(enAndroid)
                putAll(enDesktop)
                putAll(langAndroid)
                putAll(langDesktop)
            }
        }
    }

    fun androidStrings(code: String): Map<String, String> {
        val norm = code.lowercase(Locale.ROOT)
        return androidCache.getOrPut(norm) {
            val qualifier = if (norm == "en") "values" else "values-$norm"
            parseResourceStream(openResource("android-res/$qualifier/strings.xml"))
        }
    }

    fun desktopStrings(code: String): Map<String, String> {
        val norm = code.lowercase(Locale.ROOT)
        return desktopCache.getOrPut(norm) {
            val qualifier = if (norm == "en") "values" else "values-$norm"
            parseResourceStream(openResource("i18n/$qualifier/strings.xml"))
        }
    }

    private fun openResource(path: String): InputStream? =
        Strings::class.java.classLoader?.getResourceAsStream(path)

    internal fun parseResourceStream(stream: InputStream?): Map<String, String> {
        if (stream == null) return emptyMap()
        return stream.use { input ->
            runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isExpandEntityReferences = false
                }
                val doc = factory.newDocumentBuilder().parse(input)
                val nodes = doc.getElementsByTagName("string")
                val out = LinkedHashMap<String, String>(nodes.length)
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    val name = el.getAttribute("name").trim()
                    if (name.isEmpty()) continue
                    out[name] = unescapeAndroidString(el.textContent.orEmpty())
                }
                out
            }.getOrDefault(emptyMap())
        }
    }

    internal fun unescapeAndroidString(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length)
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\' && i + 1 < trimmed.length) {
                when (val next = trimmed[i + 1]) {
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/** Resolves [key] in the listener's current language with optional format arguments. */
fun str(key: String, vararg args: Any): String = Strings.str(key, *args)

/** Convenience helper resolving either a string key or an English literal mapped via the English table. */
fun tr(keyOrEn: String): String {
    val map = Strings.loadLanguage(Strings.current.code)
    if (keyOrEn in map) return map[keyOrEn]!!
    val enMap = Strings.loadLanguage(Language.English.code)
    val resolvedKey = enMap.entries.firstOrNull { it.value == keyOrEn }?.key
    return if (resolvedKey != null) map[resolvedKey] ?: keyOrEn else keyOrEn
}
