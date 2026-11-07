package dev.lelonio.square.backend.subsonic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.os.Environment
import dev.lelonio.square.data.CatalogTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.smart.TrackAnalyzer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class SubsonicDownloadManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val client: SubsonicClient,
    val store: OfflineTrackStore,
    private val prefs: SubsonicPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _activeDownloads = MutableStateFlow(0)
    val isDownloading: StateFlow<Boolean> = MutableStateFlow(false)

    /** Pre-MediaStore devices (API < 29) only: the old app-private location. */
    private val legacyDownloadDir: File
        get() {
            val ext = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val dir = File(ext ?: context.filesDir, "SquareOffline")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun downloadTrack(track: CatalogTrack) {
        if (store.isDownloaded(track.uri)) return
        val songId = track.uri.removePrefix("subsonic:track:")
        scope.launch {
            downloadTrackInternal(songId, track)
        }
    }

    fun downloadTracks(tracks: List<CatalogTrack>) {
        scope.launch {
            for (track in tracks) {
                if (!store.isDownloaded(track.uri)) {
                    val songId = track.uri.removePrefix("subsonic:track:")
                    downloadTrackInternal(songId, track)
                }
            }
        }
    }

    private suspend fun downloadTrackInternal(songId: String, track: CatalogTrack) {
        runCatching { downloadNow(track) }
            .onFailure { android.util.Log.w("SubsonicDownload", "download failed for ${track.uri}: ${it.message}") }
    }

    /** Deletes a downloaded copy (from Music/ or the picked folder) and its index entry. */
    fun forget(trackUri: String) {
        scope.launch { store.deleteRecord(trackUri) }
    }

    /**
     * Downloads one track to the device and indexes it; returns the record (the existing one when
     * it is already here). Throws on failure. Used by the app-wide DownloadQueue so a Subsonic
     * song downloaded from a playlist lands in the device's music folder and in Downloads.
     */
    suspend fun downloadNow(
        track: CatalogTrack,
        onProgress: (Float) -> Unit = {},
    ): OfflineTrackRecord = withContext(Dispatchers.IO) {
        store.records.value[track.uri]?.takeIf { store.isDownloaded(track.uri) }?.let { return@withContext it }
        val songId = track.uri.removePrefix("subsonic:track:")
        val downloadUrl = client.buildDownloadUrl(songId)
        if (downloadUrl.isBlank()) throw IOException("Subsonic server not configured")

        val artist = safeName(track.artist, 60, "Unknown Artist")
        val album = safeName(track.album, 60, "Unknown Album")
        val title = safeName(track.name, 100, "Track_$songId")

        // Already in Music/Square, from an earlier install or an earlier try: index that file
        // rather than download it again beside itself as "Title (1)".
        if (prefs.downloadTreeUri.value == null) {
            onDevice("$artist/$album").firstOrNull { it.title == title }?.let { file ->
                val record = recordOf(track, file)
                store.saveRecord(record)
                return@withContext record
            }
        }

        // Downloaded to private scratch first, then published in one go: a half-written file
        // never shows up in the device's Music library, and a failure leaves nothing behind.
        val partFile = File(context.cacheDir, "subsonic_dl_$songId.part")

        try {
            _downloadProgress.value = _downloadProgress.value + (track.uri to 0.01f)

            var mime = "audio/flac"
            val request = Request.Builder().url(downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP download failed: ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty body")
                body.contentType()?.let { if (it.type == "audio") mime = "${it.type}/${it.subtype}" }
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalRead.toFloat() / contentLength).coerceIn(0.01f, 0.99f)
                                _downloadProgress.value = _downloadProgress.value + (track.uri to progress)
                                onProgress(progress)
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Analyse from the local scratch copy while we have it: fastest path, and the
            // published copy may be a content:// URI.
            runCatching {
                TrackAnalyzer.analyze(
                    trackId = track.uri,
                    file = partFile,
                    durationSeconds = if (track.durationMs > 0) track.durationMs / 1000.0 else null,
                )
            }

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?: if (mime.contains("flac")) "flac" else "mp3"
            val fileName = "$title.$ext"
            val stored = publish(partFile, mime, artist, album, fileName)

            val record = OfflineTrackRecord(
                subsonicId = songId,
                trackUri = track.uri,
                localFilePath = stored,
                title = track.name,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                coverArtUrl = track.artworkUrl,
                fileSize = partFile.length(),
                format = ext,
            )
            store.saveRecord(record)
            record
        } finally {
            partFile.delete()
            _downloadProgress.value = _downloadProgress.value - track.uri
        }
    }

    /**
     * Copies the finished file to its home and returns what the offline index stores:
     *  - a folder the user picked in Settings (SAF tree) -> <folder>/<Artist>/<Album>/<file>
     *  - otherwise the device's public Music/Square/<Artist>/<Album>/ via MediaStore (API 29+),
     *    which needs no storage permission and survives uninstall
     *  - API < 29: the old app-private folder.
     */
    private fun publish(src: File, mime: String, artist: String, album: String, fileName: String): String {
        val tree = prefs.downloadTreeUri.value?.let(Uri::parse)
        if (tree != null) {
            val resolver = context.contentResolver
            var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (name in listOf(artist, album)) dir = findOrCreateDir(tree, dir, name)
            val doc = DocumentsContract.createDocument(resolver, dir, mime, fileName)
                ?: throw IOException("Cannot create $fileName in picked folder")
            resolver.openOutputStream(doc)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot write $fileName")
            return doc.toString()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Square/$artist/$album")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore refused $fileName")
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Cannot write $fileName")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri.toString()
        }

        val target = File(legacyDownloadDir, "$artist/$album").apply { mkdirs() }.let { File(it, fileName) }
        src.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    /** A file in Music/Square, by the parts of the path it was saved under. */
    data class DeviceFile(val uri: String, val artist: String, val album: String, val title: String, val ext: String, val size: Long)

    /**
     * The audio files in Music/Square, or in one Artist/Album folder of it. Files an earlier
     * install made are listed only with the audio permission; without it, only this install's.
     */
    fun onDevice(folder: String? = null): List<DeviceFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val media = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val root = "${Environment.DIRECTORY_MUSIC}/Square/"
        val out = mutableListOf<DeviceFile>()
        runCatching {
            context.contentResolver.query(
                media,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                ),
                if (folder == null) "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" else "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                arrayOf(if (folder == null) "$root%" else "$root$folder/"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    // Music / Square / Artist / Album
                    val dirs = c.getString(1).orEmpty().trimEnd('/').split('/')
                    if (dirs.size != 4) continue
                    val name = c.getString(2).orEmpty()
                    out += DeviceFile(
                        uri = android.content.ContentUris.withAppendedId(media, c.getLong(0)).toString(),
                        artist = dirs[2],
                        album = dirs[3],
                        title = name.substringBeforeLast('.'),
                        ext = name.substringAfterLast('.', ""),
                        size = c.getLong(3),
                    )
                }
            }
        }.onFailure { android.util.Log.w("SubsonicDownload", "Music/Square unreadable: ${it.message}") }
        return out
    }

    fun recordOf(track: CatalogTrack, file: DeviceFile) = OfflineTrackRecord(
        subsonicId = track.uri.removePrefix("subsonic:track:"),
        trackUri = track.uri,
        localFilePath = file.uri,
        title = track.name,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        coverArtUrl = track.artworkUrl,
        fileSize = file.size,
        format = file.ext,
    )

    private fun findOrCreateDir(tree: Uri, parent: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create folder $name")
    }

    companion object {
        // Keeps Vietnamese and every other script; strips only what file systems reject.
        // Also how SubsonicBackend.relink reads a file's path back, so the two must agree.
        internal fun safeName(raw: String, max: Int, fallback: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(max).ifBlank { fallback }
    }
}
