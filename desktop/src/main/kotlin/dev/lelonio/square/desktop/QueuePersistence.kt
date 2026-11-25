package dev.lelonio.square.desktop

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Serializable
data class PersistedQueueItem(
    val backend: BackendId,
    val trackId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val durationSec: Long = 0L,
    val durationMs: Long = 0L,
    val localPath: String? = null,
    val suffix: String? = null,
) {
    fun toDesktopSong(cachedFilePath: String? = null): SubsonicChildSong = SubsonicChildSong(
        id = trackId,
        title = title,
        album = album,
        artist = artist,
        coverArt = coverArt,
        suffix = suffix,
        duration = if (durationSec > 0L) durationSec else (durationMs / 1000L),
        path = cachedFilePath ?: localPath,
        albumId = albumId,
        artistId = artistId,
    )

    companion object {
        fun fromDesktopSong(
            song: SubsonicChildSong,
            durationMs: Long = song.duration * 1000L,
        ): PersistedQueueItem {
            val isLocal = song.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)
            return PersistedQueueItem(
                backend = if (isLocal) BackendId.LOCAL else BackendId.SUBSONIC,
                trackId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                artistId = song.artistId,
                coverArt = song.coverArt,
                durationSec = song.duration,
                durationMs = durationMs,
                localPath = song.path,
                suffix = song.suffix,
            )
        }
    }
}

@Serializable
data class PersistedQueueState(
    val queue: List<PersistedQueueItem>,
    val unshuffled: List<PersistedQueueItem>? = null,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
)

/**
 * Persists the desktop playback queue, index, position, and shuffle/repeat modes
 * in `~/.square-yuma/queue.json`. Restored sessions always start paused.
 */
class QueuePersistence(
    private val file: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "queue.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(state: PersistedQueueState) {
        if (state.queue.isEmpty()) {
            clear()
            return
        }
        runCatching {
            Files.createDirectories(file.parent)
            val trimmed = trimWindow(state)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(trimmed), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun load(): PersistedQueueState? {
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            json.decodeFromString<PersistedQueueState>(Files.readString(file, StandardCharsets.UTF_8))
                .takeIf { it.queue.isNotEmpty() }
        }.getOrNull()
    }

    fun clear() {
        runCatching { Files.deleteIfExists(file) }
    }

    private fun trimWindow(state: PersistedQueueState): PersistedQueueState {
        if (state.queue.size <= MAX_TRACKS) return state
        val clampedIdx = state.index.coerceIn(0, state.queue.lastIndex)
        val start = (clampedIdx - MAX_TRACKS / 2).coerceIn(0, state.queue.size - MAX_TRACKS)
        val subQueue = state.queue.subList(start, start + MAX_TRACKS)
        val subUnshuffled = state.unshuffled?.let { u ->
            val keptIds = subQueue.map { it.trackId }.toSet()
            u.filter { it.trackId in keptIds }
        }
        return state.copy(
            queue = subQueue,
            unshuffled = subUnshuffled,
            index = clampedIdx - start,
        )
    }

    companion object {
        private const val MAX_TRACKS = 300
    }
}
