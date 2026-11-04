package dev.lelonio.square.backend.subsonic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.lelonio.square.data.CatalogTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class OfflineTrackRecord(
    val subsonicId: String,
    val trackUri: String,
    /** An absolute path (legacy app-private downloads) or a content:// URI (Music/ or a picked folder). */
    val localFilePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverArtUrl: String? = null,
    val fileSize: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis(),
    val format: String = "flac",
)

class OfflineTrackStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    private val file = File(context.filesDir, "subsonic_offline_tracks.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val lock = Mutex()

    private val _records = MutableStateFlow<Map<String, OfflineTrackRecord>>(emptyMap())
    val records: StateFlow<Map<String, OfflineTrackRecord>> = _records.asStateFlow()

    init {
        loadSync()
    }

    private fun loadSync() {
        if (!file.exists()) return
        runCatching {
            val text = file.readText()
            val list = json.decodeFromString(ListSerializer(OfflineTrackRecord.serializer()), text)
            _records.value = list.associateBy { it.trackUri }
        }
    }

    suspend fun saveRecord(record: OfflineTrackRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            val updated = _records.value + (record.trackUri to record)
            _records.value = updated
            writeToDisk(updated.values.toList())
        }
    }

    suspend fun deleteRecord(trackUri: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = _records.value[trackUri] ?: return@withLock false
            runCatching {
                val path = record.localFilePath
                if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    if (DocumentsContract.isDocumentUri(null, uri) || DocumentsContract.isTreeUri(uri)) {
                        DocumentsContract.deleteDocument(resolver, uri)
                    } else {
                        resolver.delete(uri, null, null)
                    }
                } else {
                    File(path).delete()
                }
            }
            val updated = _records.value - trackUri
            _records.value = updated
            writeToDisk(updated.values.toList())
            true
        }
    }

    // ponytail: a content:// download is trusted from the index here (no IPC per list row);
    // getLocalUri() is the one that checks it still opens, at play time.
    fun isDownloaded(trackUri: String): Boolean {
        val path = _records.value[trackUri]?.localFilePath ?: return false
        return path.startsWith("content://") || File(path).exists()
    }

    fun localPath(trackUri: String): String? =
        _records.value[trackUri]?.localFilePath?.takeIf { isDownloaded(trackUri) }

    /** A playable URI for the downloaded copy, or null when it is gone (then stream instead). */
    fun getLocalUri(trackUri: String): Uri? {
        val path = _records.value[trackUri]?.localFilePath ?: return null
        if (!path.startsWith("content://")) return File(path).takeIf { it.exists() }?.let(Uri::fromFile)
        val uri = Uri.parse(path)
        val opens = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        return uri.takeIf { opens }
    }

    fun getAllCatalogTracks(): List<CatalogTrack> {
        return _records.value.values.map { record ->
            CatalogTrack(
                uri = record.trackUri,
                name = record.title,
                artist = record.artist,
                album = record.album,
                durationMs = record.durationMs,
                artworkUrl = record.coverArtUrl,
                localFile = record.localFilePath,
            )
        }
    }

    private fun writeToDisk(list: List<OfflineTrackRecord>) {
        runCatching {
            val serialized = json.encodeToString(ListSerializer(OfflineTrackRecord.serializer()), list)
            file.writeText(serialized)
        }
    }
}
