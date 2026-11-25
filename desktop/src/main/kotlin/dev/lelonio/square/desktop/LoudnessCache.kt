package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Serializable
data class CachedLoudness(val trackKey: String, val lufs: Double)

/**
 * Computes and caches per-track integrated EBU R128 loudness (LUFS) via `NativeEngine` (`libebur128`)
 * in `~/.square-yuma/analysis/` on a single low-priority background thread (`Thread.MIN_PRIORITY`).
 */
class LoudnessCache(
    private val cacheDir: Path = Paths.get(System.getProperty("user.home"), ".square-yuma", "analysis"),
    private val sourceResolver: (SubsonicChildSong) -> String,
    private val measureFn: (String) -> Double? = { uri -> NativeEngine.measureTrackLufs(uri) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, Double>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "loudness-ebur128-worker").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun get(song: SubsonicChildSong): Double? {
        val key = trackKey(song)
        memory[key]?.let { return it }
        val file = cacheFile(key)
        if (!Files.isRegularFile(file)) return null
        val loaded = runCatching {
            json.decodeFromString<CachedLoudness>(Files.readString(file, StandardCharsets.UTF_8)).lufs
        }.getOrNull()?.takeIf { it.isFinite() && it > -70.0 && it < 12.0 }
        if (loaded != null) memory[key] = loaded
        return loaded
    }

    fun put(song: SubsonicChildSong, lufs: Double) {
        if (!lufs.isFinite() || lufs <= -70.0 || lufs >= 12.0) return
        val key = trackKey(song)
        memory[key] = lufs
        saveToDisk(key, lufs)
    }

    fun gainDbFor(song: SubsonicChildSong): Float? =
        get(song)?.let { gainDbFromLufs(it) }

    fun request(song: SubsonicChildSong, onMeasured: (trackId: String, gainDb: Float) -> Unit) {
        val existing = get(song)
        if (existing != null) {
            onMeasured(song.id, gainDbFromLufs(existing))
            return
        }
        val key = trackKey(song)
        if (!inflight.add(key)) return
        val uri = sourceResolver(song)
        if (uri.isBlank()) {
            inflight.remove(key)
            return
        }
        executor.submit {
            try {
                val measured = runCatching { measureFn(uri) }.getOrNull()
                if (measured != null && measured.isFinite() && measured > -70.0 && measured < 12.0) {
                    put(song, measured)
                    onMeasured(song.id, gainDbFromLufs(measured))
                }
            } finally {
                inflight.remove(key)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun saveToDisk(key: String, lufs: Double) {
        runCatching {
            Files.createDirectories(cacheDir)
            val target = cacheFile(key)
            val tmp = target.resolveSibling("${target.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(CachedLoudness(key, lufs)), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun cacheFile(key: String): Path = cacheDir.resolve("lufs_$key.json")

    companion object {
        const val TARGET_LUFS = -16.0

        /** Gain in dB to bring [lufs] to [TARGET_LUFS], clamped to native LoudnessStage limits `[-12, +6]` dB. */
        fun gainDbFromLufs(lufs: Double): Float =
            (TARGET_LUFS - lufs).toFloat().coerceIn(-12f, 6f)

        fun trackKey(song: SubsonicChildSong): String {
            return if (song.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)) {
                val raw = song.path ?: song.id
                "local_" + sha1(raw)
            } else {
                "sub_" + song.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                    .ifBlank { sha1(song.id) }
            }
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
