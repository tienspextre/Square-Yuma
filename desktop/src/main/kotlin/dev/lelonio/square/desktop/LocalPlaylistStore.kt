package dev.lelonio.square.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale

data class LocalPlaylistEntry(
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSec: Long = -1L,
)

data class LocalPlaylist(
    val id: String,
    val name: String,
    val file: Path,
    val entries: List<LocalPlaylistEntry>,
)

/**
 * Manages local UTF-8 `.m3u8` playlists stored in a user playlists folder.
 * Track paths are written relative to the playlist directory whenever possible
 * (same root / drive) and resolved back portably on read.
 */
class LocalPlaylistStore(
    val dir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "playlists"),
) {
    private val lock = Any()
    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    init {
        refresh()
    }

    fun refresh(): List<LocalPlaylist> = synchronized(lock) {
        if (!Files.exists(dir)) {
            _playlists.value = emptyList()
            return emptyList()
        }
        val list = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".m3u8", ignoreCase = true) }
                .map { parseFile(it) }
                .sorted(Comparator.comparing<LocalPlaylist, String> { it.name.lowercase(Locale.ROOT) })
                .toList()
        }
        _playlists.value = list
        list
    }

    fun list(): List<LocalPlaylist> = refresh()

    fun read(id: String): LocalPlaylist? = synchronized(lock) {
        val file = resolvePlaylistFile(id) ?: return null
        if (!Files.isRegularFile(file)) return null
        parseFile(file)
    }

    fun create(name: String): LocalPlaylist = synchronized(lock) {
        Files.createDirectories(dir)
        val cleanName = name.trim().ifBlank { "Untitled Playlist" }
        val base = safeFileStem(cleanName)
        var candidate = dir.resolve("$base.m3u8")
        var n = 2
        while (Files.exists(candidate)) {
            candidate = dir.resolve("$base ($n).m3u8")
            n++
        }
        val pl = LocalPlaylist(
            id = candidate.fileName.toString().removeSuffix(".m3u8"),
            name = cleanName,
            file = candidate,
            entries = emptyList(),
        )
        writeFile(pl)
        refresh()
        pl
    }

    fun rename(id: String, newName: String): LocalPlaylist = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("Playlist not found: $id")
        val cleanName = newName.trim().ifBlank { current.name }
        val newStem = safeFileStem(cleanName)
        var target = dir.resolve("$newStem.m3u8")
        if (target != current.file) {
            var n = 2
            while (Files.exists(target)) {
                target = dir.resolve("$newStem ($n).m3u8")
                n++
            }
        }
        val updated = current.copy(
            id = target.fileName.toString().removeSuffix(".m3u8"),
            name = cleanName,
            file = target,
        )
        writeFile(updated)
        if (target != current.file) {
            Files.deleteIfExists(current.file)
        }
        refresh()
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val file = resolvePlaylistFile(id) ?: return false
        val deleted = Files.deleteIfExists(file)
        refresh()
        deleted
    }

    fun addTracks(id: String, entries: List<LocalPlaylistEntry>): LocalPlaylist = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("Playlist not found: $id")
        val updated = current.copy(entries = current.entries + entries)
        writeFile(updated)
        refresh()
        updated
    }

    fun removeAt(id: String, index: Int): LocalPlaylist = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("Playlist not found: $id")
        if (index !in current.entries.indices) return current
        val mutable = current.entries.toMutableList().apply { removeAt(index) }
        val updated = current.copy(entries = mutable)
        writeFile(updated)
        refresh()
        updated
    }

    fun removeByPath(id: String, trackPath: String): LocalPlaylist = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("Playlist not found: $id")
        val targetNorm = normalizePathString(trackPath)
        val idx = current.entries.indexOfFirst { normalizePathString(it.path) == targetNorm }
        if (idx < 0) return current
        removeAt(id, idx)
    }

    fun reorder(id: String, fromIndex: Int, toIndex: Int): LocalPlaylist = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("Playlist not found: $id")
        if (fromIndex !in current.entries.indices || toIndex !in current.entries.indices || fromIndex == toIndex) {
            return current
        }
        val mutable = current.entries.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        val updated = current.copy(entries = mutable)
        writeFile(updated)
        refresh()
        updated
    }

    private fun resolvePlaylistFile(id: String): Path? {
        val stem = id.removePrefix("local:playlist:").removeSuffix(".m3u8")
        val direct = dir.resolve("$stem.m3u8")
        if (Files.isRegularFile(direct)) return direct
        if (!Files.exists(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().removeSuffix(".m3u8").equals(stem, ignoreCase = true)
            }.findFirst().orElse(null)
        }
    }

    private fun parseFile(file: Path): LocalPlaylist {
        val rawLines = Files.readAllLines(file, StandardCharsets.UTF_8)
        val stem = file.fileName.toString().removeSuffix(".m3u8")
        var displayName = stem
        val entries = mutableListOf<LocalPlaylistEntry>()

        var pendingDur = -1L
        var pendingArtist: String? = null
        var pendingTitle: String? = null

        for ((idx, rawLine) in rawLines.withIndex()) {
            val line = (if (idx == 0) rawLine.removePrefix("\uFEFF") else rawLine).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                        val n = line.substringAfter(':').trim()
                        if (n.isNotEmpty()) displayName = n
                    }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        val rest = line.substringAfter(':')
                        val comma = rest.indexOf(',')
                        if (comma >= 0) {
                            pendingDur = rest.substring(0, comma).trim().toDoubleOrNull()?.toLong() ?: -1L
                            val label = rest.substring(comma + 1).trim()
                            val sep = label.indexOf(" - ")
                            if (sep > 0) {
                                pendingArtist = label.substring(0, sep).trim()
                                pendingTitle = label.substring(sep + 3).trim()
                            } else {
                                pendingArtist = null
                                pendingTitle = label.ifBlank { null }
                            }
                        }
                    }
                }
                continue
            }

            val resolved = resolveEntryPath(line)
            entries += LocalPlaylistEntry(
                path = resolved,
                title = pendingTitle,
                artist = pendingArtist,
                durationSec = pendingDur,
            )
            pendingDur = -1L
            pendingArtist = null
            pendingTitle = null
        }

        return LocalPlaylist(id = stem, name = displayName, file = file, entries = entries)
    }

    private fun writeFile(playlist: LocalPlaylist) {
        Files.createDirectories(dir)
        val lines = buildList {
            add("#EXTM3U")
            add("#PLAYLIST:${playlist.name}")
            for (entry in playlist.entries) {
                val label = listOfNotNull(
                    entry.artist?.takeIf { it.isNotBlank() },
                    entry.title?.takeIf { it.isNotBlank() },
                ).joinToString(" - ").ifBlank {
                    Paths.get(entry.path).fileName?.toString()?.substringBeforeLast('.') ?: "Unknown"
                }
                add("#EXTINF:${entry.durationSec},$label")
                add(relativizePortable(entry.path))
            }
        }
        val tmp = dir.resolve("${playlist.file.fileName}.tmp")
        Files.write(tmp, lines, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, playlist.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, playlist.file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun relativizePortable(rawPath: String): String {
        val target = Paths.get(rawPath).toAbsolutePath().normalize()
        val base = dir.toAbsolutePath().normalize()
        return try {
            base.relativize(target).toString()
        } catch (_: IllegalArgumentException) {
            target.toString()
        }
    }

    private fun resolveEntryPath(rawLine: String): String {
        val nativeSeparators = rawLine.replace('/', File.separatorChar).replace('\\', File.separatorChar)
        val p = Paths.get(nativeSeparators)
        val resolved = if (p.isAbsolute) p.normalize() else dir.toAbsolutePath().normalize().resolve(p).normalize()
        return resolved.toString()
    }

    private fun normalizePathString(raw: String): String =
        runCatching { Paths.get(raw).toAbsolutePath().normalize().toString() }.getOrDefault(raw)

    private fun safeFileStem(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim()
            .trimEnd('.')
            .take(100)
            .ifBlank { "playlist" }
}
