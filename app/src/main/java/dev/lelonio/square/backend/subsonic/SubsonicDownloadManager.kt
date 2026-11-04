package dev.lelonio.square.backend.subsonic

import android.content.Context
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _activeDownloads = MutableStateFlow(0)
    val isDownloading: StateFlow<Boolean> = MutableStateFlow(false)

    private val baseDownloadDir: File
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

    private suspend fun downloadTrackInternal(songId: String, track: CatalogTrack) = withContext(Dispatchers.IO) {
        val downloadUrl = client.buildDownloadUrl(songId)
        if (downloadUrl.isBlank()) return@withContext

        val safeArtist = track.artist.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(60).ifBlank { "Unknown Artist" }
        val safeAlbum = track.album.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(60).ifBlank { "Unknown Album" }
        val safeTitle = track.name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(80).ifBlank { "Track_$songId" }

        val targetDir = File(baseDownloadDir, "$safeArtist/$safeAlbum")
        targetDir.mkdirs()

        val partFile = File(targetDir, "$safeTitle.flac.part")
        val finalFile = File(targetDir, "$safeTitle.flac")

        try {
            _downloadProgress.value = _downloadProgress.value + (track.uri to 0.01f)

            val request = Request.Builder().url(downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP download failed: ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalRead.toFloat() / contentLength).coerceIn(0.01f, 0.99f)
                                _downloadProgress.value = _downloadProgress.value + (track.uri to progress)
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (finalFile.exists()) finalFile.delete()
            partFile.renameTo(finalFile)

            // Save into offline index
            val record = OfflineTrackRecord(
                subsonicId = songId,
                trackUri = track.uri,
                localFilePath = finalFile.absolutePath,
                title = track.name,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                coverArtUrl = track.artworkUrl,
                fileSize = finalFile.length(),
            )
            store.saveRecord(record)

            _downloadProgress.value = _downloadProgress.value - track.uri

            // Immediately run C++ TrackAnalyzer in background to cache BPM & Automix grids!
            runCatching {
                TrackAnalyzer.analyze(
                    trackId = track.uri,
                    file = finalFile,
                    durationSeconds = if (track.durationMs > 0) track.durationMs / 1000.0 else null,
                )
            }
        } catch (e: Exception) {
            partFile.delete()
            _downloadProgress.value = _downloadProgress.value - track.uri
        }
    }
}
