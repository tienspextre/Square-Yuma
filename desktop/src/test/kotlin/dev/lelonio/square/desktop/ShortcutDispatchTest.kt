package dev.lelonio.square.desktop

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutDispatchTest {

    @Test
    fun resolvesAllGlobalShortcutsWhenNotTyping() {
        assertEquals(ShortcutAction.PLAY_PAUSE, ShortcutDispatch.resolve(Key.Spacebar, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.SEARCH, ShortcutDispatch.resolve(Key.K, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.TOGGLE_LYRICS, ShortcutDispatch.resolve(Key.L, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.TOGGLE_QUEUE, ShortcutDispatch.resolve(Key.Q, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.PREVIOUS, ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.NEXT, ShortcutDispatch.resolve(Key.DirectionRight, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.VOLUME_UP, ShortcutDispatch.resolve(Key.DirectionUp, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.VOLUME_DOWN, ShortcutDispatch.resolve(Key.DirectionDown, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.SEEK_BACK, ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.SEEK_FORWARD, ShortcutDispatch.resolve(Key.DirectionRight, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.TOGGLE_FULLSCREEN, ShortcutDispatch.resolve(Key.F11, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.TOGGLE_MINI_PLAYER, ShortcutDispatch.resolve(Key.M, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.NAV_BACK, ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = false, isAlt = true, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.NAV_FORWARD, ShortcutDispatch.resolve(Key.DirectionRight, isCtrlOrMeta = false, isAlt = true, isShift = false, isTyping = false))
        assertEquals(ShortcutAction.OPEN_SETTINGS, ShortcutDispatch.resolve(Key.Comma, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = false))
    }

    @Test
    fun suppressesNonCtrlShortcutsWhileTypingButAllowsCtrlCombos() {
        // Non-Ctrl keys must be suppressed in text fields
        assertNull(ShortcutDispatch.resolve(Key.Spacebar, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.DirectionRight, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.DirectionUp, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.DirectionDown, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.F11, isCtrlOrMeta = false, isAlt = false, isShift = false, isTyping = true))
        assertNull(ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = false, isAlt = true, isShift = false, isTyping = true))

        // Ctrl-combos continue to dispatch even while typing
        assertEquals(ShortcutAction.SEARCH, ShortcutDispatch.resolve(Key.K, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.TOGGLE_LYRICS, ShortcutDispatch.resolve(Key.L, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.TOGGLE_QUEUE, ShortcutDispatch.resolve(Key.Q, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.TOGGLE_MINI_PLAYER, ShortcutDispatch.resolve(Key.M, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.OPEN_SETTINGS, ShortcutDispatch.resolve(Key.Comma, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.PREVIOUS, ShortcutDispatch.resolve(Key.DirectionLeft, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.NEXT, ShortcutDispatch.resolve(Key.DirectionRight, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.VOLUME_UP, ShortcutDispatch.resolve(Key.DirectionUp, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
        assertEquals(ShortcutAction.VOLUME_DOWN, ShortcutDispatch.resolve(Key.DirectionDown, isCtrlOrMeta = true, isAlt = false, isShift = false, isTyping = true))
    }
}
