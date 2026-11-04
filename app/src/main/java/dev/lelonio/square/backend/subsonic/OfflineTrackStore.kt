package dev.lelonio.square.backend.subsonic

import android.content.Context
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
    val localFilePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverArtUrl: String? = null,
    val fileSize: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis(),
)

class OfflineTrackStore(context: Context) {

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
            runCatching { File(record.localFilePath).delete() }
            val updated = _records.value - trackUri
            _records.value = updated
            writeToDisk(updated.values.toList())
            true
        }
    }

    fun isDownloaded(trackUri: String): Boolean {
        val record = _records.value[trackUri] ?: return false
        return File(record.localFilePath).exists()
    }

    fun getLocalFile(trackUri: String): File? {
        val record = _records.value[trackUri] ?: return null
        val f = File(record.localFilePath)
        return if (f.exists()) f else null
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
