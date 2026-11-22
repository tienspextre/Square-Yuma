package moe.rukamori.archivetune.playback.smart

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseWindowsTest {
    @Test
    fun batteryGuardSkipsModelPass() {
        var decoded = false
        ModelPass.shouldSkip = { true }
        try {
            val json = runBlocking { ModelPass.run(240.0) { _, _ -> decoded = true; null } }
            assertEquals("", json)
            assertTrue(!decoded)
        } finally {
            ModelPass.shouldSkip = { false }
        }
    }

    @Test
    fun windowMath() {
        assertEquals(0L to 65_000L, AudioDecoder.headWindowMs(240_000L))
        assertEquals(175_000L to 240_000L, AudioDecoder.tailWindowMs(240_000L))
        // Short track: head covers it, tail (when any) overlaps the head.
        assertEquals(0L to 50_000L, AudioDecoder.headWindowMs(50_000L))
        assertNull(AudioDecoder.tailWindowMs(50_000L))
        assertEquals(0L to 65_000L, AudioDecoder.headWindowMs(100_000L))
        assertEquals(35_000L to 100_000L, AudioDecoder.tailWindowMs(100_000L))
    }

    @Test
    fun pairIdIsStable() {
        val id = PairCache.id("a", "b", 5, 1)
        assertEquals(16, id.length)
        assertEquals(id, PairCache.id("a", "b", 5, 1))
        assertNotEquals(id, PairCache.id("b", "a", 5, 1))
        assertNotEquals(id, PairCache.id("a", "b", 6, 1))
    }

    @Test
    fun pairCacheRoundTripAndReportPruning() {
        val root = kotlin.io.path.createTempDirectory("automix").toFile()
        try {
            val cache = PairCache(File(root, "pairs"))
            assertNull(cache.get("x"))
            cache.put("x", "{\"k\":1}")
            assertEquals("{\"k\":1}", cache.get("x"))

            val store = ReportStore(File(root, "reports"))
            repeat(55) { i ->
                store.write("r%03d.json".format(i), "{}")
                File(root, "reports/r%03d.json".format(i)).setLastModified(1_000_000L + i * 1000L)
            }
            store.write("r055.json", "{}")
            val names = store.files().map { it.name }
            assertEquals(50, names.size)
            assertEquals("r055.json", names.first())
            assertTrue("r000.json" !in names)
        } finally {
            root.deleteRecursively()
        }
    }
}
