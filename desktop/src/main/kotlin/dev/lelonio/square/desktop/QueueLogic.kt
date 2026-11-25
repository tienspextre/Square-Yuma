package dev.lelonio.square.desktop

import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlin.random.Random

/**
 * Pure queue reorder helper keeping track identity for [currentIndex] consistent.
 * Returns the updated list and the new index of the currently playing track.
 */
fun <T> moveQueueItem(
    queue: List<T>,
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int,
): Pair<List<T>, Int> {
    if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) {
        return queue to currentIndex
    }
    val currentItem = queue.getOrNull(currentIndex)
    val mutable = queue.toMutableList()
    val moved = mutable.removeAt(fromIndex)
    mutable.add(toIndex, moved)
    val nextIndex = if (currentItem == null) {
        currentIndex
    } else {
        mutable.indexOfFirst { it === currentItem }.takeIf { it >= 0 } ?: currentIndex
    }
    return mutable to nextIndex
}

/**
 * Artist-aware smart shuffle: places the current song at index 0 and spreads remaining
 * tracks so the same artist does not appear back-to-back when alternatives exist.
 */
fun <T> smartShuffleOrder(
    items: List<T>,
    currentIndex: Int,
    artistOf: (T) -> String?,
    random: Random = Random.Default,
): List<T> {
    if (items.size <= 2) {
        val cur = items.getOrNull(currentIndex) ?: return items
        return listOf(cur) + items.filterIndexed { i, _ -> i != currentIndex }
    }
    val current = items.getOrNull(currentIndex)
    val pool = items.filterIndexed { i, _ -> i != currentIndex }.shuffled(random).toMutableList()
    val result = ArrayList<T>(items.size)
    if (current != null) result.add(current)

    while (pool.isNotEmpty()) {
        val prevArtist = result.lastOrNull()?.let(artistOf)?.trim()?.lowercase()
        val pickIdx = if (!prevArtist.isNullOrEmpty()) {
            pool.indexOfFirst { item ->
                val a = artistOf(item)?.trim()?.lowercase()
                a != prevArtist
            }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        result.add(pool.removeAt(pickIdx))
    }
    return result
}

/**
 * Formats a concise audio signal-chain summary from [NativeEngine.status] JSON.
 */
data class AudioSignalChain(
    val initialized: Boolean = false,
    val playing: Boolean = false,
    val sourceCodec: String = "",
    val sourceSampleRate: Int = 0,
    val sourceBitsPerSample: Int = 0,
    val sourceChannels: Int = 0,
    val outputMode: String = "shared",
    val exclusiveActive: Boolean = false,
    val fallbackReason: String = "",
    val deviceName: String = "",
    val deviceSampleRate: Int = 0,
    val deviceFormat: String = "",
    val deviceChannels: Int = 0,
    val bufferedMs: Long = 0L,
    val mixing: Boolean = false,
    val mixProgress: Double = 0.0,
    val lufs: Double = Double.NaN,
    val normalizeGain: Double = 1.0,
    val underruns: Long = 0L,
) {
    val qualityLabel: String?
        get() {
            if (sourceCodec.isBlank() || sourceSampleRate <= 0) return null
            val khz = if (sourceSampleRate % 1000 == 0) {
                "${sourceSampleRate / 1000}"
            } else {
                "%.1f".format(sourceSampleRate / 1000.0)
            }
            val bits = if (sourceBitsPerSample > 0) "${sourceBitsPerSample}/" else ""
            return "${sourceCodec.uppercase()} · ${bits}${khz} kHz"
        }

    companion object {
        fun fromJson(raw: String): AudioSignalChain {
            if (raw.isBlank() || raw == "{}") return AudioSignalChain()
            return runCatching {
                val j = org.json.JSONObject(raw)
                AudioSignalChain(
                    initialized = j.optBoolean("initialized", false),
                    playing = j.optBoolean("playing", false),
                    sourceCodec = j.optString("sourceCodec", ""),
                    sourceSampleRate = j.optInt("sourceSampleRate", 0),
                    sourceBitsPerSample = j.optInt("sourceBitsPerSample", 0),
                    sourceChannels = j.optInt("sourceChannels", 0),
                    outputMode = j.optString("outputMode", "shared"),
                    exclusiveActive = j.optBoolean("exclusiveActive", false),
                    fallbackReason = j.optString("fallbackReason", ""),
                    deviceName = j.optString("deviceName", ""),
                    deviceSampleRate = j.optInt("deviceSampleRate", 0),
                    deviceFormat = j.optString("deviceFormat", ""),
                    deviceChannels = j.optInt("deviceChannels", 0),
                    bufferedMs = j.optLong("bufferedMs", 0L),
                    mixing = j.optBoolean("mixing", false),
                    mixProgress = j.optDouble("mixProgress", 0.0),
                    lufs = j.optDouble("lufs", Double.NaN),
                    normalizeGain = j.optDouble("normalizeGain", 1.0),
                    underruns = j.optLong("underruns", 0L),
                )
            }.getOrDefault(AudioSignalChain())
        }
    }
}

/** Picks local fallback radio tracks matching genre/artist when server similar-songs is unavailable. */
fun localSimilarSongs(seed: SubsonicChildSong, library: List<SubsonicChildSong>, limit: Int = 20): List<SubsonicChildSong> {
    if (library.isEmpty()) return emptyList()
    val seedArtist = seed.artist?.trim()?.lowercase()
    val seedGenre = seed.genre?.trim()?.lowercase()
    return library
        .asSequence()
        .filter { it.id != seed.id }
        .sortedByDescending { candidate ->
            var score = 0
            if (!seedGenre.isNullOrEmpty() && candidate.genre?.trim()?.lowercase() == seedGenre) score += 3
            if (!seedArtist.isNullOrEmpty() && candidate.artist?.trim()?.lowercase() == seedArtist) score += 2
            score
        }
        .take(limit)
        .toList()
}
