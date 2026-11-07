package dev.lelonio.square.backend.local

import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.DesktopAudioInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object DesktopLocalScanner {

    private val AUDIO_EXTENSIONS = setOf("flac", "wav", "alac", "mp3", "ogg", "opus", "m4a", "aac")
    private val COVER_NAMES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png", "front.jpg")

    data class LocalScanResult(
        val tracks: List<CatalogTrack>,
        val playlists: List<CatalogPlaylist>,
        val playlistTracks: Map<String, List<CatalogTrack>>,
    )

    suspend fun scanFolder(rootPath: String): LocalScanResult = withContext(Dispatchers.IO) {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            return@withContext LocalScanResult(emptyList(), emptyList(), emptyMap())
        }

        val tracks = mutableListOf<CatalogTrack>()
        val trackByPath = mutableMapOf<String, CatalogTrack>()
        val m3uFiles = mutableListOf<File>()

        root.walkTopDown()
            .maxDepth(6)
            .forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                if (ext == "m3u8" || ext == "m3u") {
                    m3uFiles += file
                } else if (ext in AUDIO_EXTENSIONS) {
                    parseAudioFile(file)?.let { track ->
                        tracks += track
                        trackByPath[file.canonicalPath] = track
                    }
                }
            }

        // Group by album and m3u8 playlists
        val playlists = mutableListOf<CatalogPlaylist>()
        val playlistMap = mutableMapOf<String, List<CatalogTrack>>()

        for (m3u in m3uFiles) {
            val uri = "local:playlist:${m3u.absolutePath}"
            val name = m3u.nameWithoutExtension
            val entries = parseM3u(m3u, trackByPath)
            if (entries.isNotEmpty()) {
                val cover = findSidecarCover(m3u.parentFile) ?: entries.firstOrNull()?.artworkUrl
                playlists += CatalogPlaylist(
                    uri = uri,
                    name = name,
                    artworkUrl = cover,
                    subtitle = "${entries.size} tracks • M3U8 Playlist",
                    songCount = entries.size,
                )
                playlistMap[uri] = entries
            }
        }

        // Also build Album collections from scanned local tracks
        tracks.groupBy { it.album.ifBlank { "Local Singles" } }
            .entries
            .sortedBy { it.key }
            .forEach { (albumName, albumTracks) ->
                val uri = "local:album:$albumName"
                val first = albumTracks.first()
                playlists += CatalogPlaylist(
                    uri = uri,
                    name = albumName,
                    artworkUrl = first.artworkUrl,
                    subtitle = "${first.artist} • ${albumTracks.size} tracks",
                    songCount = albumTracks.size,
                )
                playlistMap[uri] = albumTracks
            }

        LocalScanResult(
            tracks = tracks,
            playlists = playlists,
            playlistTracks = playlistMap,
        )
    }

    private fun parseAudioFile(file: File): CatalogTrack? = runCatching {
        val ext = file.extension.lowercase()
        val flac = if (ext == "flac") {
            runCatching { FileInputStream(file).use(DesktopAudioInspection::readFlac) }.getOrNull()
        } else {
            null
        }
        val tags = flac?.tags?.toMap() ?: emptyMap()

        val rawName = file.nameWithoutExtension
        val fallbackTitle = rawName.replace(Regex("""^\d+\s*[-._]\s*"""), "").trim().ifEmpty { rawName }
        val fallbackAlbum = file.parentFile?.name ?: "Local Folder"
        val fallbackArtist = file.parentFile?.parentFile?.name?.takeIf { it != "music" && it != "Playlists" } ?: "Unknown Artist"

        val title = tags["TITLE"]?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val artist = tags["ARTIST"]?.takeIf { it.isNotBlank() }
            ?: tags["ALBUMARTIST"]?.takeIf { it.isNotBlank() }
            ?: fallbackArtist
        val album = tags["ALBUM"]?.takeIf { it.isNotBlank() } ?: fallbackAlbum
        val year = (tags["DATE"] ?: tags["YEAR"]).orEmpty().take(4)

        val sampleRate = flac?.sampleRate ?: 44100
        val bitDepth = flac?.bitsPerSample ?: if (ext == "flac" || ext == "wav" || ext == "alac") 16 else 0
        val durationMs = flac?.durationMs?.takeIf { it > 0 }
            ?: estimateDurationMs(file, ext)
        val bitRateKbps = if (durationMs > 0) ((file.length() * 8) / durationMs).toInt() else 0

        val sidecarCover = findSidecarCover(file.parentFile)

        CatalogTrack(
            uri = "local:file:${file.absolutePath}",
            name = title,
            artist = artist,
            artists = listOf(CatalogArtist(artist)),
            album = album,
            year = year,
            durationMs = durationMs,
            artworkUrl = sidecarCover,
            localFile = file.absolutePath,
            suffix = ext,
            bitRateKbps = bitRateKbps,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
        )
    }.getOrNull()

    private fun findSidecarCover(dir: File?): String? {
        if (dir == null || !dir.isDirectory) return null
        for (name in COVER_NAMES) {
            val f = File(dir, name)
            if (f.exists() && f.isFile) {
                return f.toURI().toString()
            }
        }
        return null
    }

    private fun estimateDurationMs(file: File, ext: String): Long {
        val bytes = file.length()
        val approxBytesPerSec = when (ext) {
            "flac", "alac" -> 115_000L
            "wav" -> 176_400L
            "mp3" -> 40_000L
            else -> 32_000L
        }
        return ((bytes * 1000L) / approxBytesPerSec).coerceAtLeast(10_000L)
    }

    private fun parseM3u(m3uFile: File, trackByPath: Map<String, CatalogTrack>): List<CatalogTrack> {
        val result = mutableListOf<CatalogTrack>()
        val parent = m3uFile.parentFile
        runCatching {
            m3uFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val candidate = if (File(trimmed).isAbsolute) File(trimmed) else File(parent, trimmed)
                val canonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return@forEachLine
                val track = trackByPath[canonical] ?: if (candidate.exists()) parseAudioFile(candidate) else null
                if (track != null) result += track
            }
        }
        return result
    }
}
