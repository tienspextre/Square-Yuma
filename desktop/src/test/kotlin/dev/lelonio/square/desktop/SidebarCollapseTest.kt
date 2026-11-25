package dev.lelonio.square.desktop

import dev.lelonio.square.desktop.ui.shell.SIDEBAR_COLLAPSE_THRESHOLD_DP
import dev.lelonio.square.desktop.ui.shell.shouldCollapseSidebar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarCollapseTest {

    @Test
    fun collapsesAutomaticallyBelowThreshold() {
        assertTrue(shouldCollapseSidebar(999f, null))
        assertTrue(shouldCollapseSidebar(720f, null))
        assertFalse(shouldCollapseSidebar(SIDEBAR_COLLAPSE_THRESHOLD_DP, null))
        assertFalse(shouldCollapseSidebar(1280f, null))
    }

    @Test
    fun manualOverrideWinsRegardlessOfWidth() {
        assertTrue(shouldCollapseSidebar(1440f, true))
        assertFalse(shouldCollapseSidebar(800f, false))
    }
}
