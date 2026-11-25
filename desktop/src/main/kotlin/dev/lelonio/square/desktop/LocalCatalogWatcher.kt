package dev.lelonio.square.desktop

import dev.lelonio.square.data.local.AudioMetadataParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Recursive `WatchService` watcher with debounce for [LocalCatalog].
 */
internal class LocalCatalogWatcher(
    private val scope: CoroutineScope,
    private val foldersProvider: () -> List<Path>,
    private val indexByPath: ConcurrentHashMap<String, IndexedLocalTrack>,
    private val parseTrack: (Path, Long, Long, ConcurrentHashMap<Path, String?>) -> IndexedLocalTrack?,
    private val onOverflowRescan: suspend () -> Unit,
    private val onBatchChanged: () -> Unit,
) : Closeable {
    private enum class WatchKind { UPSERT, DELETE }

    private var watchService: WatchService? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val watchedDirs = ConcurrentHashMap.newKeySet<Path>()
    private var watchThread: Thread? = null
    private val pendingEvents = ConcurrentHashMap<Path, WatchKind>()
    @Volatile private var overflowPending = false
    private var debounceJob: Job? = null
    private val debounceLock = Any()
    @Volatile private var closed = false

    fun start() {
        val ws = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return
        watchService = ws
        watchThread = Thread({
            while (!closed) {
                val key = try {
                    ws.take()
                } catch (_: InterruptedException) {
                    break
                } catch (_: ClosedWatchServiceException) {
                    break
                }
                val dir = watchKeys[key]
                if (dir == null) {
                    key.reset()
                    continue
                }
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == OVERFLOW) {
                        overflowPending = true
                        scheduleDebounce()
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val rel = (event.context() as? Path) ?: continue
                    val full = dir.resolve(rel).toAbsolutePath().normalize()
                    when (kind) {
                        ENTRY_CREATE -> {
                            if (Files.isDirectory(full)) registerTree(full)
                            pendingEvents[full] = WatchKind.UPSERT
                        }
                        ENTRY_MODIFY -> pendingEvents[full] = WatchKind.UPSERT
                        ENTRY_DELETE -> pendingEvents[full] = WatchKind.DELETE
                    }
                    scheduleDebounce()
                }
                if (!key.reset()) {
                    watchKeys.remove(key)
                    watchedDirs.remove(dir)
                }
            }
        }, "local-library-watcher").apply {
            isDaemon = true
            start()
        }
    }

    fun syncWatchers() {
        watchService ?: return
        val roots = foldersProvider().filter { Files.isDirectory(it) }
        watchKeys.entries.toList().forEach { (key, dir) ->
            if (roots.none { dir.startsWith(it) } || !Files.isDirectory(dir)) {
                key.cancel()
                watchKeys.remove(key)
                watchedDirs.remove(dir)
            }
        }
        roots.forEach { registerTree(it) }
    }

    fun registerTree(root: Path) {
        val ws = watchService ?: return
        if (!Files.isDirectory(root)) return
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val norm = dir.toAbsolutePath().normalize()
                        if (watchedDirs.add(norm)) {
                            runCatching {
                                val key = norm.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                                watchKeys[key] = norm
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
                },
            )
        }
    }

    private fun scheduleDebounce() = synchronized(debounceLock) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(WATCH_DEBOUNCE_MS)
            flushWatchEvents()
        }
    }

    private suspend fun flushWatchEvents() {
        if (closed) return
        if (overflowPending) {
            overflowPending = false
            pendingEvents.clear()
            onOverflowRescan()
            return
        }
        val batch = HashMap<Path, WatchKind>()
        for ((p, k) in pendingEvents) {
            if (pendingEvents.remove(p, k)) batch[p] = k
        }
        if (batch.isEmpty()) return

        var changed = false
        val folderArtCache = ConcurrentHashMap<Path, String?>()

        for ((path, kind) in batch) {
            if (kind == WatchKind.DELETE || !Files.exists(path)) {
                val str = path.toString()
                if (indexByPath.remove(str) != null) changed = true
                val prefix = if (str.endsWith(File.separator)) str else "$str${File.separator}"
                indexByPath.keys.toList().forEach { k ->
                    if (k.startsWith(prefix)) {
                        indexByPath.remove(k)
                        changed = true
                    }
                }
                if (LocalCatalogIo.isFolderArtworkFile(path)) {
                    val parent = path.parent
                    val updatedArt = LocalCatalogIo.findFolderArtwork(parent, folderArtCache)
                    indexByPath.values.filter { Paths.get(it.path).parent == parent }.forEach { t ->
                        indexByPath[t.path] = t.copy(folderArtworkPath = updatedArt)
                        changed = true
                    }
                }
                continue
            }

            if (Files.isDirectory(path)) {
                registerTree(path)
                Files.walkFileTree(
                    path,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (attrs.isRegularFile && AudioMetadataParser.isSupportedAudioFile(file)) {
                                val parsed = parseTrack(file.toAbsolutePath().normalize(), attrs.size(), attrs.lastModifiedTime().toMillis(), folderArtCache)
                                if (parsed != null) {
                                    indexByPath[parsed.path] = parsed
                                    changed = true
                                }
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } else if (Files.isRegularFile(path)) {
                if (AudioMetadataParser.isSupportedAudioFile(path)) {
                    val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull() ?: continue
                    val parsed = parseTrack(path, attrs.size(), attrs.lastModifiedTime().toMillis(), folderArtCache)
                    if (parsed != null) {
                        indexByPath[parsed.path] = parsed
                        changed = true
                    }
                } else if (LocalCatalogIo.isFolderArtworkFile(path)) {
                    val parent = path.parent
                    val art = LocalCatalogIo.findFolderArtwork(parent, folderArtCache)
                    indexByPath.values.filter { Paths.get(it.path).parent == parent }.forEach { t ->
                        indexByPath[t.path] = t.copy(folderArtworkPath = art)
                        changed = true
                    }
                }
            }
        }

        if (changed) onBatchChanged()
    }

    override fun close() {
        closed = true
        debounceJob?.cancel()
        runCatching { watchService?.close() }
        watchKeys.keys.forEach { runCatching { it.cancel() } }
        watchKeys.clear()
        watchedDirs.clear()
        watchThread?.interrupt()
    }

    companion object {
        private const val WATCH_DEBOUNCE_MS = 350L
    }
}
