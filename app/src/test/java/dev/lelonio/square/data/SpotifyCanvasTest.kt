package dev.lelonio.square.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCanvasTest {
    @Test
    fun readsUrlAndKindOfFirstCanvas() {
        val url = "https://canvaz.scdn.co/upload/x.mp4".toByteArray()
        // Canvaz { string id = 1; string url = 2; Type type = 4 (VIDEO_LOOPING = 2) }
        val canvaz = byteArrayOf(0x0a, 1, 'a'.code.toByte(), 0x12, url.size.toByte()) + url + byteArrayOf(0x20, 2)
        val response = byteArrayOf(0x0a, canvaz.size.toByte()) + canvaz
        assertEquals(CanvasClip(String(url), isVideo = true), SpotifyCanvas.parse(response))
    }

    @Test
    fun emptyResponseIsNoCanvas() {
        assertNull(SpotifyCanvas.parse(ByteArray(0)))
    }
}
