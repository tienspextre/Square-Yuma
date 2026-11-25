package dev.lelonio.square.desktop

/**
 * Browser-style back/forward navigation history over [Screen] entries.
 * Maintains a cursor into a bounded stack so back and forward arrows
 * work seamlessly across both root tabs and pushed detail pages.
 */
class NavigationHistory(initial: Screen) {
    private val entries = mutableListOf(initial)
    private var cursor = 0

    val current: Screen get() = entries[cursor]
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor < entries.lastIndex
    val size: Int get() = entries.size
    val backDepth: Int get() = cursor

    /** Pushes [screen] onto history, discarding any forward entries ahead of [cursor]. */
    fun push(screen: Screen): Boolean {
        if (entries[cursor] == screen) return false
        while (entries.lastIndex > cursor) {
            entries.removeAt(entries.lastIndex)
        }
        entries.add(screen)
        if (entries.size > MAX_ENTRIES) {
            val overflow = entries.size - MAX_ENTRIES
            repeat(overflow) { entries.removeAt(0) }
        }
        cursor = entries.lastIndex
        return true
    }

    fun back(): Screen? {
        if (!canGoBack) return null
        cursor--
        return entries[cursor]
    }

    fun forward(): Screen? {
        if (!canGoForward) return null
        cursor++
        return entries[cursor]
    }

    /** Returns the most recent root tab screen at or before [cursor], if any. */
    fun activeRootScreen(): Screen {
        for (i in cursor downTo 0) {
            val s = entries[i]
            if (s.isRootTab) return s
        }
        return entries.first()
    }

    fun snapshot(): List<Screen> = entries.toList()

    companion object {
        private const val MAX_ENTRIES = 64
    }
}

val Screen.isRootTab: Boolean
    get() = this is Screen.Home ||
        this is Screen.Library ||
        this is Screen.LocalFolders ||
        this is Screen.Playlists ||
        this is Screen.Search ||
        this is Screen.Downloads ||
        this is Screen.Month ||
        this is Screen.Settings
