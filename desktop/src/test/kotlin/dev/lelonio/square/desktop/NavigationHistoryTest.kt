package dev.lelonio.square.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationHistoryTest {

    @Test
    fun pushBackForwardAndTruncateForwardBranch() {
        val nav = NavigationHistory(Screen.Home)
        assertEquals(Screen.Home, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.canGoForward)

        assertTrue(nav.push(Screen.Library))
        assertTrue(nav.push(Screen.Album("al-1", "Discovery")))
        assertFalse("Pushing identical current screen is a no-op", nav.push(Screen.Album("al-1", "Discovery")))

        assertTrue(nav.canGoBack)
        assertFalse(nav.canGoForward)
        assertEquals(2, nav.backDepth)

        assertEquals(Screen.Library, nav.back())
        assertTrue(nav.canGoBack)
        assertTrue(nav.canGoForward)

        assertEquals(Screen.Home, nav.back())
        assertFalse(nav.canGoBack)
        assertTrue(nav.canGoForward)
        assertNull(nav.back())

        assertEquals(Screen.Library, nav.forward())
        assertTrue(nav.push(Screen.Settings))
        assertFalse("Pushing after back discards forward branch", nav.canGoForward)
        assertEquals(Screen.Settings, nav.current)
        assertEquals(listOf(Screen.Home, Screen.Library, Screen.Settings), nav.snapshot())
    }

    @Test
    fun activeRootScreenTracksMostRecentRootTab() {
        val nav = NavigationHistory(Screen.Home)
        nav.push(Screen.Library)
        nav.push(Screen.Album("a1", "One"))
        nav.push(Screen.Artist("ar1", "Artist"))
        assertEquals(Screen.Library, nav.activeRootScreen())

        nav.back()
        nav.back()
        assertEquals(Screen.Library, nav.activeRootScreen())
        nav.back()
        assertEquals(Screen.Home, nav.activeRootScreen())
    }
}
