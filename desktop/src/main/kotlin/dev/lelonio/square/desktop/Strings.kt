package dev.lelonio.square.desktop

enum class Language(val code: String, val label: String) { English("en", "English"), Vietnamese("vi", "Tiếng Việt") }

/** Placeholder; filled in with the Vietnamese table. */
object Strings {
    var current: Language = Language.English
}
