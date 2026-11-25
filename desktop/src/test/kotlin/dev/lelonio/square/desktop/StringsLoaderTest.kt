package dev.lelonio.square.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsLoaderTest {

    @Test
    fun allTwelveLanguagesLoadAndroidAndDesktopResources() {
        assertEquals(12, Language.entries.size)
        for (lang in Language.entries) {
            val androidMap = Strings.androidStrings(lang.code)
            assertTrue("Android strings.xml missing or empty for ${lang.code}", androidMap.isNotEmpty())
            val merged = Strings.loadLanguage(lang.code)
            assertTrue("Merged strings missing home for ${lang.code}", !merged["home"].isNullOrBlank())
            assertTrue("Merged strings missing desktop_local_folders for ${lang.code}", !merged["desktop_local_folders"].isNullOrBlank())
        }
        assertFalse(Strings.loadLanguage("vi")["home"] == Strings.loadLanguage("en")["home"])
        assertFalse(Strings.loadLanguage("ja")["settings"] == Strings.loadLanguage("en")["settings"])
    }

    @Test
    fun fallbackToEnglishForMissingKeyOrUnknownLanguage() {
        val unknown = Strings.loadLanguage("xx-unknown")
        val en = Strings.loadLanguage("en")
        assertEquals(en["home"], unknown["home"])

        // Hindi desktop overlay intentionally omits some desktop keys and must fall back to English.
        val hi = Strings.loadLanguage("hi")
        assertEquals(en["desktop_renderer_auto"], hi["desktop_renderer_auto"])
        assertEquals("nonexistent_key_123", Strings.formatForLanguage("de", "nonexistent_key_123"))
    }

    @Test
    fun formatArgsSubstitutePositionalPlaceholders() {
        val formattedEn = Strings.formatForLanguage("en", "desktop_report_saved_to", "/tmp/report.txt")
        assertTrue(formattedEn.contains("/tmp/report.txt"))
        val formattedAndroid = Strings.formatForLanguage("en", "added_to", "Favorites")
        assertEquals("Added to Favorites", formattedAndroid)
    }
}
