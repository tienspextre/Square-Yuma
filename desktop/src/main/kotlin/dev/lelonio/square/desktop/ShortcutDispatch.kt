package dev.lelonio.square.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Actions dispatched by global in-app keyboard shortcuts. */
enum class ShortcutAction {
    PLAY_PAUSE,
    SEARCH,
    TOGGLE_LYRICS,
    TOGGLE_QUEUE,
    PREVIOUS,
    NEXT,
    VOLUME_UP,
    VOLUME_DOWN,
    SEEK_BACK,
    SEEK_FORWARD,
    TOGGLE_FULLSCREEN,
    TOGGLE_MINI_PLAYER,
    NAV_BACK,
    NAV_FORWARD,
    OPEN_SETTINGS,
    EXIT_FULLSCREEN_OR_BACK,
}

data class ShortcutSpec(val keys: String, val labelKey: String)

object ShortcutDispatch {
    val ALL_SHORTCUTS: List<ShortcutSpec> = listOf(
        ShortcutSpec("Space", "desktop_shortcut_play_pause"),
        ShortcutSpec("Ctrl + K", "desktop_shortcut_search"),
        ShortcutSpec("Ctrl + L", "desktop_shortcut_lyrics"),
        ShortcutSpec("Ctrl + Q", "desktop_shortcut_queue"),
        ShortcutSpec("Ctrl + ← / →", "desktop_shortcut_prev_next"),
        ShortcutSpec("← / →", "desktop_shortcut_seek"),
        ShortcutSpec("Ctrl + ↑ / ↓", "desktop_shortcut_volume"),
        ShortcutSpec("F11", "desktop_shortcut_fullscreen"),
        ShortcutSpec("Ctrl + M", "desktop_shortcut_mini_player"),
        ShortcutSpec("Alt + ← / →", "desktop_shortcut_nav"),
        ShortcutSpec("Ctrl + ,", "desktop_shortcut_settings"),
        ShortcutSpec("Menu / Shift + F10", "desktop_shortcut_context_menu"),
    )

    /**
     * Pure shortcut resolver.
     * - Ctrl/Meta-combos work globally even while [isTyping] is true.
     * - Non-Ctrl shortcuts (Space, arrows, F11, Alt+arrows) are suppressed while [isTyping] is true,
     *   except Escape (handled via [resolvePostInputEscape] after dialogs/menus).
     */
    fun resolve(
        key: Key,
        isCtrlOrMeta: Boolean,
        isAlt: Boolean,
        isShift: Boolean,
        isTyping: Boolean,
    ): ShortcutAction? {
        if (isCtrlOrMeta) {
            return when (key) {
                Key.K, Key.F -> ShortcutAction.SEARCH
                Key.L -> ShortcutAction.TOGGLE_LYRICS
                Key.Q -> ShortcutAction.TOGGLE_QUEUE
                Key.M -> ShortcutAction.TOGGLE_MINI_PLAYER
                Key.Comma -> ShortcutAction.OPEN_SETTINGS
                Key.DirectionLeft -> ShortcutAction.PREVIOUS
                Key.DirectionRight -> ShortcutAction.NEXT
                Key.DirectionUp -> ShortcutAction.VOLUME_UP
                Key.DirectionDown -> ShortcutAction.VOLUME_DOWN
                else -> null
            }
        }
        if (isTyping) return null
        if (isAlt && !isShift) {
            return when (key) {
                Key.DirectionLeft -> ShortcutAction.NAV_BACK
                Key.DirectionRight -> ShortcutAction.NAV_FORWARD
                else -> null
            }
        }
        if (isAlt) return null
        return when (key) {
            Key.Spacebar -> ShortcutAction.PLAY_PAUSE
            Key.F11 -> ShortcutAction.TOGGLE_FULLSCREEN
            Key.DirectionLeft -> ShortcutAction.SEEK_BACK
            Key.DirectionRight -> ShortcutAction.SEEK_FORWARD
            Key.DirectionUp -> ShortcutAction.VOLUME_UP
            Key.DirectionDown -> ShortcutAction.VOLUME_DOWN
            else -> null
        }
    }

    fun resolveEvent(e: KeyEvent, isTyping: Boolean): ShortcutAction? {
        if (e.type != KeyEventType.KeyDown) return null
        return resolve(
            key = e.key,
            isCtrlOrMeta = e.isCtrlPressed || e.isMetaPressed,
            isAlt = e.isAltPressed,
            isShift = e.isShiftPressed,
            isTyping = isTyping,
        )
    }

    fun isContextMenuShortcut(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return e.key == Key.Menu || (e.key == Key.F10 && e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed)
    }

    fun dispatch(state: AppState, action: ShortcutAction): Boolean {
        when (action) {
            ShortcutAction.PLAY_PAUSE -> state.player.toggle()
            ShortcutAction.SEARCH -> {
                if (state.fullscreen) state.exitFullscreen()
                state.nowPlaying = false
                state.focusQuickSearch()
            }
            ShortcutAction.TOGGLE_LYRICS -> state.toggleInspector(InspectorTab.LYRICS)
            ShortcutAction.TOGGLE_QUEUE -> state.toggleInspector(InspectorTab.QUEUE)
            ShortcutAction.PREVIOUS -> state.player.previous()
            ShortcutAction.NEXT -> state.player.next()
            ShortcutAction.VOLUME_UP -> state.setVolume(state.player.volume.value + 0.05f)
            ShortcutAction.VOLUME_DOWN -> state.setVolume(state.player.volume.value - 0.05f)
            ShortcutAction.SEEK_BACK -> state.player.seek(state.player.positionMs.value - 10_000)
            ShortcutAction.SEEK_FORWARD -> state.player.seek(state.player.positionMs.value + 10_000)
            ShortcutAction.TOGGLE_FULLSCREEN -> state.toggleFullscreen()
            ShortcutAction.TOGGLE_MINI_PLAYER -> state.requestMiniPlayerToggle()
            ShortcutAction.NAV_BACK -> state.back()
            ShortcutAction.NAV_FORWARD -> state.forward()
            ShortcutAction.OPEN_SETTINGS -> {
                if (state.fullscreen) state.exitFullscreen()
                state.nowPlaying = false
                state.root(Screen.Settings)
            }
            ShortcutAction.EXIT_FULLSCREEN_OR_BACK -> state.back()
        }
        return true
    }
}
