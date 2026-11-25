package dev.lelonio.square.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.local.AudioMetadataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.SwingUtilities

data class DropClassification(
    val audioFiles: List<Path>,
    val folders: List<Path>,
    val ignored: List<Path>,
) {
    val isEmpty: Boolean get() = audioFiles.isEmpty() && folders.isEmpty()
}

enum class DropZone {
    MAIN,
    QUEUE_INSPECTOR,
    LOCAL_PLAYLIST,
}

object DropTargetHandler {
    fun classifyDroppedPaths(paths: List<Path>): DropClassification {
        val audio = mutableListOf<Path>()
        val folders = mutableListOf<Path>()
        val ignored = mutableListOf<Path>()
        for (raw in paths) {
            val p = raw.toAbsolutePath().normalize()
            when {
                Files.isDirectory(p) -> folders.add(p)
                Files.isRegularFile(p) && AudioMetadataParser.isSupportedAudioFile(p) -> audio.add(p)
                else -> ignored.add(p)
            }
        }
        return DropClassification(audio.distinct(), folders.distinct(), ignored.distinct())
    }

    fun parseUriList(raw: String): List<Path> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                runCatching {
                    val uri = URI(line)
                    if (uri.scheme.equals("file", ignoreCase = true)) Paths.get(uri) else null
                }.getOrNull()
            }
            .toList()

    fun extractPaths(transferable: Transferable): List<Path> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull()
            if (!files.isNullOrEmpty()) return files.map { it.toPath() }
        }
        val uriFlavor = transferable.transferDataFlavors.firstOrNull {
            it.mimeType.startsWith("text/uri-list", ignoreCase = true) && it.isRepresentationClassInputStream ||
                it.mimeType.startsWith("text/uri-list", ignoreCase = true) && it.representationClass == String::class.java
        } ?: DataFlavor.stringFlavor
        if (transferable.isDataFlavorSupported(uriFlavor)) {
            val raw = runCatching { transferable.getTransferData(uriFlavor) }.getOrNull()
            val text = when (raw) {
                is String -> raw
                is java.io.InputStream -> raw.bufferedReader().use { it.readText() }
                else -> null
            }
            if (!text.isNullOrBlank()) return parseUriList(text)
        }
        return emptyList()
    }

    fun determineDropZone(
        xPx: Float,
        yPx: Float,
        queueBounds: Rect?,
        playlistBounds: Rect?,
        inspectorOpen: Boolean,
        inspectorTab: InspectorTab,
        currentScreen: Screen,
    ): DropZone {
        val pt = Offset(xPx, yPx)
        if (inspectorOpen && inspectorTab == InspectorTab.QUEUE && queueBounds != null && queueBounds.contains(pt)) {
            return DropZone.QUEUE_INSPECTOR
        }
        if (currentScreen is Screen.Playlist &&
            currentScreen.id.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX) &&
            playlistBounds != null &&
            playlistBounds.contains(pt)
        ) {
            return DropZone.LOCAL_PLAYLIST
        }
        return DropZone.MAIN
    }

    fun songFromPath(path: Path, catalog: LocalCatalog): SubsonicChildSong {
        val norm = path.toAbsolutePath().normalize()
        catalog.findById(norm.toString())?.let { return it.toDesktopSong() }
        val meta = AudioMetadataParser.parse(norm, includeArtwork = false)
        val fileNameStem = norm.fileName?.toString()?.substringBeforeLast('.') ?: "Unknown"
        val title = meta?.title?.takeIf { it.isNotBlank() } ?: fileNameStem
        val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: meta?.albumArtist?.takeIf { it.isNotBlank() }
        val album = meta?.album?.takeIf { it.isNotBlank() }
        val durationSec = ((meta?.durationMs ?: 0L) / 1000L).coerceAtLeast(0L)
        val trackId = LocalCatalogIo.trackIdForPath(norm)
        return SubsonicChildSong(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            coverArt = if (meta?.hasEmbeddedArtwork == true) LocalCatalog.LOCAL_ART_PREFIX + norm else null,
            duration = durationSec,
            track = meta?.trackNumber,
            year = meta?.year?.toIntOrNull(),
            genre = meta?.genre,
            suffix = norm.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()?.ifBlank { null },
            path = norm.toString(),
            bitRate = meta?.bitrateKbps?.takeIf { it > 0 },
        )
    }

    fun showInFolderCommand(path: Path, osName: String = System.getProperty("os.name")): List<String> {
        val norm = path.toAbsolutePath().normalize()
        return when {
            osName.startsWith("Windows", ignoreCase = true) ->
                listOf("explorer.exe", "/select,$norm")
            osName.startsWith("Mac", ignoreCase = true) ->
                listOf("open", "-R", norm.toString())
            else -> {
                val target = if (Files.isDirectory(norm)) norm else (norm.parent ?: norm)
                listOf("xdg-open", target.toString())
            }
        }
    }

    fun openInFolder(state: AppState, pathStr: String?) {
        if (pathStr.isNullOrBlank()) return
        state.scope.launch(Dispatchers.IO) {
            runCatching {
                val cmd = showInFolderCommand(Paths.get(pathStr))
                ProcessBuilder(cmd).start()
            }.onFailure {
                state.scope.launch { state.say(it.message ?: "Failed to open folder") }
            }
        }
    }

    fun attachWindowDropTarget(window: java.awt.Window, state: AppState, densityProvider: () -> Float): DropTarget {
        val listener = object : DropTargetListener {
            private fun updateZone(dtde: DropTargetDragEvent) {
                val supported = dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    dtde.currentDataFlavors.any { it.mimeType.startsWith("text/uri-list", true) }
                if (!supported) {
                    dtde.rejectDrag()
                    state.dropZoneHighlight = null
                    return
                }
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                val pt = convertPointToWindow(dtde.location, dtde.dropTargetContext.component, window)
                val scale = densityProvider()
                state.dropZoneHighlight = determineDropZone(
                    xPx = pt.x * scale,
                    yPx = pt.y * scale,
                    queueBounds = state.queueDropBounds,
                    playlistBounds = state.playlistDropBounds,
                    inspectorOpen = state.inspectorOpen,
                    inspectorTab = state.inspectorTab,
                    currentScreen = state.currentScreen,
                )
            }

            override fun dragEnter(dtde: DropTargetDragEvent) = updateZone(dtde)
            override fun dragOver(dtde: DropTargetDragEvent) = updateZone(dtde)
            override fun dropActionChanged(dtde: DropTargetDragEvent) = updateZone(dtde)
            override fun dragExit(dte: DropTargetEvent) {
                state.dropZoneHighlight = null
            }

            override fun drop(dtde: DropTargetDropEvent) {
                state.dropZoneHighlight = null
                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                val paths = runCatching { extractPaths(dtde.transferable) }.getOrDefault(emptyList())
                val pt = convertPointToWindow(dtde.location, dtde.dropTargetContext.component, window)
                val scale = densityProvider()
                val zone = determineDropZone(
                    xPx = pt.x * scale,
                    yPx = pt.y * scale,
                    queueBounds = state.queueDropBounds,
                    playlistBounds = state.playlistDropBounds,
                    inspectorOpen = state.inspectorOpen,
                    inspectorTab = state.inspectorTab,
                    currentScreen = state.currentScreen,
                )
                handleDroppedPaths(state, paths, zone)
                dtde.dropComplete(true)
            }
        }
        return DropTarget(window, DnDConstants.ACTION_COPY, listener, true)
    }

    private fun convertPointToWindow(pt: java.awt.Point, comp: Component?, window: java.awt.Window): java.awt.Point =
        if (comp != null && comp !== window) SwingUtilities.convertPoint(comp, pt, window) else pt

    fun handleDroppedPaths(state: AppState, paths: List<Path>, zone: DropZone) {
        val classified = classifyDroppedPaths(paths)
        if (classified.isEmpty) {
            state.say(str("desktop_drop_unsupported"))
            return
        }
        state.scope.launch(Dispatchers.IO) {
            when (zone) {
                DropZone.QUEUE_INSPECTOR -> {
                    val files = collectAudioFiles(classified)
                    val songs = files.map { songFromPath(it, state.local) }
                    if (songs.isNotEmpty()) {
                        state.scope.launch {
                            songs.forEach { state.player.addToQueue(it) }
                            state.say(str("desktop_drop_added_queue", songs.size))
                        }
                    } else if (classified.folders.isNotEmpty()) {
                        state.scope.launch { state.pendingDropFolders = classified.folders }
                    }
                }
                DropZone.LOCAL_PLAYLIST -> {
                    val pl = state.currentScreen as? Screen.Playlist
                    val files = collectAudioFiles(classified)
                    if (pl != null && pl.id.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX) && files.isNotEmpty()) {
                        val entries = files.map { f ->
                            val s = songFromPath(f, state.local)
                            LocalPlaylistEntry(path = f.toString(), title = s.title, artist = s.artist, durationSec = s.duration)
                        }
                        state.local.playlistStore.addTracks(pl.id.removePrefix(LocalCatalog.LOCAL_PLAYLIST_PREFIX), entries)
                        state.scope.launch {
                            state.generation++
                            state.say(str("desktop_drop_added_playlist", entries.size, pl.name))
                        }
                    } else if (classified.folders.isNotEmpty()) {
                        state.scope.launch { state.pendingDropFolders = classified.folders }
                    }
                }
                DropZone.MAIN -> {
                    if (classified.audioFiles.isNotEmpty()) {
                        val songs = classified.audioFiles.map { songFromPath(it, state.local) }
                        state.scope.launch {
                            state.player.playQueue(songs, 0)
                            state.say(str("desktop_drop_playing", songs.size))
                        }
                    }
                    if (classified.folders.isNotEmpty()) {
                        state.scope.launch { state.pendingDropFolders = classified.folders }
                    }
                }
            }
        }
    }

    private fun collectAudioFiles(classified: DropClassification): List<Path> {
        if (classified.folders.isEmpty()) return classified.audioFiles
        val all = classified.audioFiles.toMutableList()
        for (dir in classified.folders) {
            runCatching {
                Files.walk(dir, 4).use { stream ->
                    stream.filter { Files.isRegularFile(it) && AudioMetadataParser.isSupportedAudioFile(it) }
                        .sorted()
                        .forEach { all.add(it) }
                }
            }
        }
        return all.distinct()
    }

    fun confirmAddDroppedFolders(state: AppState) {
        val folders = state.pendingDropFolders
        state.pendingDropFolders = emptyList()
        if (folders.isEmpty()) return
        state.scope.launch {
            var added = 0
            for (f in folders) {
                if (state.local.addFolder(f)) added++
            }
            if (added > 0) state.say("${str("desktop_add_folder")}: $added")
        }
    }
}
