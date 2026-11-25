package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogTrack
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class IndexedLocalTrack(
    val id: String,
    val path: String,
    val size: Long,
    val mtimeMs: Long,
    val addedAtMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String = "",
    val genre: String = "",
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val bitrateKbps: Int = 0,
    val hasEmbeddedArtwork: Boolean = false,
    val folderArtworkPath: String? = null,
) {
    val artworkUrl: String?
        get() = when {
            hasEmbeddedArtwork -> LocalCatalog.LOCAL_ART_PREFIX + path
            !folderArtworkPath.isNullOrBlank() -> runCatching { Paths.get(folderArtworkPath).toUri().toString() }.getOrNull()
            else -> null
        }

    fun toCatalogTrack(): CatalogTrack = CatalogTrack(
        uri = id,
        name = title,
        artist = artist,
        artistUri = LocalCatalog.LOCAL_ARTIST_PREFIX + artist,
        artists = if (artist.isNotBlank()) listOf(CatalogArtist(artist, LocalCatalog.LOCAL_ARTIST_PREFIX + artist)) else emptyList(),
        album = album,
        albumUri = LocalCatalog.LOCAL_ALBUM_PREFIX + LocalCatalog.albumKey(this),
        year = year,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        addedAt = runCatching { Instant.ofEpochMilli(addedAtMs).toString() }.getOrNull(),
        localFile = path,
    )

    fun toDesktopSong(): SubsonicChildSong {
        val p = Paths.get(path)
        val ext = p.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.ifBlank { null }
        return SubsonicChildSong(
            id = id,
            title = title,
            album = album.ifBlank { null },
            artist = artist.ifBlank { null },
            track = trackNumber,
            year = year.take(4).toIntOrNull(),
            genre = genre.ifBlank { null },
            coverArt = artworkUrl,
            size = size,
            suffix = ext,
            duration = (durationMs / 1000L).coerceAtLeast(0L),
            bitRate = bitrateKbps.takeIf { it > 0 },
            path = path,
            discNumber = discNumber,
            albumId = LocalCatalog.LOCAL_ALBUM_PREFIX + LocalCatalog.albumKey(this),
            artistId = LocalCatalog.LOCAL_ARTIST_PREFIX + artist,
            created = runCatching { Instant.ofEpochMilli(addedAtMs).toString() }.getOrNull(),
        )
    }
}

internal object LocalCatalogIo {
    private val FOLDER_ART_CANDIDATES = listOf(
        "folder.jpg", "cover.jpg", "front.jpg",
        "folder.jpeg", "cover.jpeg", "front.jpeg",
        "folder.png", "cover.png", "front.png",
    )

    fun isFolderArtworkFile(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase(Locale.ROOT) ?: return false
        return name in FOLDER_ART_CANDIDATES
    }

    fun findFolderArtwork(dir: Path?, cache: ConcurrentHashMap<Path, String?>): String? {
        if (dir == null) return null
        cache[dir]?.let { return it }
        if (!Files.isDirectory(dir)) return null
        val files = runCatching {
            Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        }.getOrDefault(emptyList())
        val byLowerName = files.associateBy { it.fileName.toString().lowercase(Locale.ROOT) }
        val found = FOLDER_ART_CANDIDATES.firstNotNullOfOrNull { byLowerName[it] }?.toAbsolutePath()?.normalize()?.toString()
        if (found != null) cache[dir] = found
        return found
    }

    fun atomicWriteText(target: Path, text: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun trackIdForPath(path: Path): String {
        val norm = path.toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-1").digest(norm.toByteArray(StandardCharsets.UTF_8))
        return LocalCatalog.LOCAL_TRACK_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }
}
