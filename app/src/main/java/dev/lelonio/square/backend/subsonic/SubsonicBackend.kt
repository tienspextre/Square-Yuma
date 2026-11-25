package dev.lelonio.square.backend.subsonic

import androidx.media3.common.Player
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.playback.LocalPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubsonicBackend(
    client: SubsonicClient,
    val offlineStore: OfflineTrackStore,
    val downloadManager: SubsonicDownloadManager,
) : SubsonicCatalog(client, offlineStore), MusicBackend {

    override val useBridge: Boolean = true

    override fun createPlayer(host: PlaybackHost): Player =
        LocalPlayerFactory.create(host)

    override suspend fun keptLyrics(uri: String) = dev.lelonio.square.backend.lyrics.SpotifyLyrics.kept(uri)

    /**
     * Whether an earlier install may have left downloads behind: the index lives in the app's
     * private storage and goes with an uninstall, Music/Square does not.
     * Only a folder check, which needs no permission; [relink] needs the audio one.
     */
    fun hasLeftovers(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            client.preferences.isConfigured() &&
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                "Square",
            ).isDirectory

    /**
     * Indexes again the downloads in Music/Square that the index does not know, each matched
     * back to its song on the server by the path it was saved under, Artist/Album/Title.ext
     * (see SubsonicDownloadManager.publish). Returns the songs found.
     */
    // ponytail: matched by path, not tags; a file under "Unknown Album" or renamed by hand is
    // not found. Tag reading (or a sidecar with the song id) if that ever matters.
    suspend fun relink(context: android.content.Context): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val safe = SubsonicDownloadManager.Companion::safeName
        val known = offlineStore.records.value.values.map { it.localFilePath }.toSet()
        val left = downloadManager.onDevice().filter { it.uri !in known }
        fun CatalogTrack.matches(file: SubsonicDownloadManager.DeviceFile) =
            safe(name, 100, "") == file.title &&
                safe(artist, 60, "Unknown Artist") == file.artist &&
                safe(album, 60, "Unknown Album") == file.album
        val found = mutableListOf<CatalogTrack>()
        for ((album, files) in left.groupBy { it.album }) {
            // The whole album in two calls, rather than a search per song.
            val songs = client.search(album).getOrNull()?.album.orEmpty()
                .filter { safe(it.name, 60, "Unknown Album") == album }
                .flatMap { client.getAlbum(it.id).getOrNull().orEmpty() }
                .map { it.toCatalogTrack(client, offlineStore) }
            for (file in files) {
                val track = songs.firstOrNull { it.matches(file) }
                    ?: client.search(file.title).getOrNull()?.song.orEmpty()
                        .map { it.toCatalogTrack(client, offlineStore) }
                        .firstOrNull { it.matches(file) }
                    ?: continue
                // Downloaded again by this install before it knew of the old file: the copy
                // it made ("Title (1).flac") is this install's own, so it can go.
                offlineStore.records.value[track.uri]?.localFilePath
                    ?.takeIf { it.startsWith("content://media/") }
                    ?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }
                offlineStore.saveRecord(downloadManager.recordOf(track, file))
                found += track.copy(localFile = file.uri)
            }
        }
        android.util.Log.i("SubsonicDownload", "relinked ${found.size} of ${left.size} leftover downloads")
        found
    }
}
