package dev.lelonio.square.desktop

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueuePersistenceTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun queuePersistenceRoundTripLocalAndSubsonicPausedAtPosition() {
        val queueFile = tmp.newFolder("q").toPath().resolve("queue.json")
        val persistence = QueuePersistence(queueFile)

        val localSong = SubsonicChildSong(
            id = "local:track:abc123",
            title = "Local Track",
            artist = "Local Artist",
            album = "Local Album",
            duration = 210,
            path = "/music/local_track.flac",
            suffix = "flac",
        )
        val offlineSubsonicSong = SubsonicChildSong(
            id = "sub-99",
            title = "Offline Subsonic Track",
            artist = "NAS Artist",
            album = "NAS Album",
            duration = 195,
            path = "/cache/sub-99.flac",
            suffix = "flac",
        )

        val player1 = DesktopPlayer(
            streamUrl = { it.path ?: "http://example/stream/${it.id}" },
            durationMsOf = { if (it.id == localSong.id) 210_500L else it.duration * 1000L },
            initEngine = false,
        )
        player1.playQueue(listOf(localSong, offlineSubsonicSong), start = 1)
        player1.toggleShuffle()
        player1.cycleRepeat() // ALL
        player1.seek(47_250L)

        val snap = player1.snapshotQueueState()
        assertNotNull(snap)
        persistence.save(snap!!)

        // Restore into a fresh DesktopPlayer on startup
        val loaded = persistence.load()
        assertNotNull(loaded)
        loaded!!
        assertEquals(2, loaded.queue.size)
        assertTrue(loaded.queue.any { it.backend == BackendId.LOCAL && it.trackId == localSong.id })
        assertTrue(loaded.queue.any { it.backend == BackendId.SUBSONIC && it.trackId == offlineSubsonicSong.id })

        val player2 = DesktopPlayer(
            streamUrl = { it.path ?: "http://example/stream/${it.id}" },
            initEngine = false,
        )
        player2.restoreQueueState(loaded) { item ->
            item.toDesktopSong(cachedFilePath = item.localPath)
        }

        assertFalse("Restored player must start paused", player2.isPlaying.value)
        assertEquals(47_250L, player2.positionMs.value)
        assertEquals(RepeatMode.ALL, player2.repeat.value)
        assertTrue(player2.shuffle.value)
        assertEquals(offlineSubsonicSong.id, player2.current.value?.id)
        assertEquals("/cache/sub-99.flac", player2.current.value?.path)
        assertEquals(2, player2.queue.value.size)
    }
}
