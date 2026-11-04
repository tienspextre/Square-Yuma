package moe.rukamori.archivetune.playback.smart

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiodsp.AnalysisStore
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import org.json.JSONObject
import timber.log.Timber

object TrackAnalyzer : AnalysisStore {
    private const val KICK_OFF_COOLDOWN_MS = 10_000L
    private val analyzerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val memoryCache = ConcurrentHashMap<String, TrackAnalysisResult>()
    private val inFlight = ConcurrentHashMap<String, Deferred<TrackAnalysisResult?>>()
    private val lastKickTimestamps = ConcurrentHashMap<String, Long>()
    private val analysisSemaphore = Semaphore(2)
    private val _analysisEvents = MutableSharedFlow<Pair<String, TrackAnalysisResult>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val analysisEvents: SharedFlow<Pair<String, TrackAnalysisResult>> = _analysisEvents.asSharedFlow()

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) {
            val dir = File(context.filesDir, "track_analysis")
            if (!dir.exists()) dir.mkdirs()
            cacheDir = dir
        }
    }

    private fun cacheFileFor(trackId: String): File? {
        val dir = cacheDir ?: return null
        val hash = runCatching {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(trackId.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        }.getOrDefault(trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
        return File(dir, "$hash.json")
    }

    fun shouldThrottleKickOff(trackId: String): Boolean {
        if (trackId.isBlank()) return true
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastKickTimestamps[trackId]
        if (last != null && now - last < KICK_OFF_COOLDOWN_MS) {
            return true
        }
        lastKickTimestamps[trackId] = now
        return false
    }

    override fun getCached(trackId: String): TrackAnalysisResult? = memoryCache[trackId]

    fun hasCached(trackId: String): Boolean = memoryCache.containsKey(trackId)

    suspend fun getOrFetchCached(trackId: String): TrackAnalysisResult? {
        if (trackId.isBlank()) return null
        memoryCache[trackId]?.let { return it }

        val diskResult = withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFileFor(trackId)
                if (file != null && file.exists() && file.canRead()) {
                    val text = file.readText(Charsets.UTF_8)
                    val json = JSONObject(text)
                    TrackAnalysisResult(
                        bpm = json.optDouble("bpm", 0.0),
                        mixInTime = json.optDouble("mixInTime", 0.0),
                        mixOutTime = json.optDouble("mixOutTime", 0.0),
                        contentEndTime = json.optDouble("contentEndTime", 0.0),
                        firstBeat = json.optDouble("firstBeat", 0.0),
                        rawJson = json.optString("rawJson", text),
                        loudnessLufs = json.optDouble("loudnessLufs", 0.0),
                        dynamicRangeDb = json.optDouble("dynamicRangeDb", 0.0),
                    )
                } else null
            }.getOrNull()
        }

        if (diskResult != null) {
            memoryCache[trackId] = diskResult
            _analysisEvents.tryEmit(trackId to diskResult)
            return diskResult
        }
        return null
    }

    fun putCached(trackId: String, result: TrackAnalysisResult) {
        memoryCache[trackId] = result
        _analysisEvents.tryEmit(trackId to result)
        analyzerScope.async(Dispatchers.IO) {
            runCatching {
                val file = cacheFileFor(trackId) ?: return@runCatching
                val json = JSONObject().apply {
                    put("bpm", result.bpm)
                    put("mixInTime", result.mixInTime)
                    put("mixOutTime", result.mixOutTime)
                    put("contentEndTime", result.contentEndTime)
                    put("firstBeat", result.firstBeat)
                    put("rawJson", result.rawJson)
                    put("loudnessLufs", result.loudnessLufs)
                    put("dynamicRangeDb", result.dynamicRangeDb)
                }
                file.writeText(json.toString(), Charsets.UTF_8)
            }
        }
    }

    fun clearCache() {
        memoryCache.clear()
        analyzerScope.async(Dispatchers.IO) {
            runCatching { cacheDir?.listFiles()?.forEach { it.delete() } }
        }
    }

    suspend fun analyze(
        trackId: String,
        filePath: String,
        durationSeconds: Double? = null,
        startMs: Long = 0L,
        endMs: Long = AudioDecoder.DEFAULT_HEAD_DECODE_MS,
    ): TrackAnalysisResult? {
        if (filePath.startsWith("http://", ignoreCase = true) || filePath.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return null
        val durationMs = if (durationSeconds != null && durationSeconds > 0.0) (durationSeconds * 1000.0).roundToLong() else null
        return analyzeWithSource(
            trackId = trackId,
            durationSeconds = durationSeconds,
            source = "filePath",
            decodeHeadSamples = { AudioDecoder.decode(file, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && durationMs > AudioDecoder.TAIL_WINDOW_MS) {
                { AudioDecoder.decodeTail(file, durationMs) }
            } else null,
            decodeFullSamples = if (AudioDecoder.canDoFullPass(durationMs)) {
                { AudioDecoder.decodeFull(file, durationMs!!) }
            } else null,
        )
    }

    suspend fun analyze(
        trackId: String,
        file: File,
        durationSeconds: Double? = null,
        startMs: Long = 0L,
        endMs: Long = AudioDecoder.DEFAULT_HEAD_DECODE_MS,
    ): TrackAnalysisResult? {
        if (!file.exists() || !file.canRead()) return null
        val durationMs = if (durationSeconds != null && durationSeconds > 0.0) (durationSeconds * 1000.0).roundToLong() else null
        return analyzeWithSource(
            trackId = trackId,
            durationSeconds = durationSeconds,
            source = "file",
            decodeHeadSamples = { AudioDecoder.decode(file, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && durationMs > AudioDecoder.TAIL_WINDOW_MS) {
                { AudioDecoder.decodeTail(file, durationMs) }
            } else null,
            decodeFullSamples = if (AudioDecoder.canDoFullPass(durationMs)) {
                { AudioDecoder.decodeFull(file, durationMs!!) }
            } else null,
        )
    }

    suspend fun analyze(
        trackId: String,
        context: Context,
        uri: Uri,
        durationSeconds: Double? = null,
        startMs: Long = 0L,
        endMs: Long = AudioDecoder.DEFAULT_HEAD_DECODE_MS,
    ): TrackAnalysisResult? {
        init(context)
        val durationMs = if (durationSeconds != null && durationSeconds > 0.0) (durationSeconds * 1000.0).roundToLong() else null
        return analyzeWithSource(
            trackId = trackId,
            durationSeconds = durationSeconds,
            source = "uri",
            decodeHeadSamples = { AudioDecoder.decode(context, uri, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && durationMs > AudioDecoder.TAIL_WINDOW_MS) {
                { AudioDecoder.decodeTail(context, uri, durationMs) }
            } else null,
            decodeFullSamples = if (AudioDecoder.canDoFullPass(durationMs)) {
                { AudioDecoder.decodeFull(context, uri, durationMs!!) }
            } else null,
        )
    }

    /** Head + tail over HTTP; no full pass, which would download the whole file. */
    suspend fun analyzeUrl(
        trackId: String,
        url: String,
        durationSeconds: Double,
    ): TrackAnalysisResult? {
        val durationMs = (durationSeconds * 1000.0).roundToLong()
        return analyzeWithSource(
            trackId = trackId,
            durationSeconds = durationSeconds,
            source = "url",
            decodeHeadSamples = { AudioDecoder.decodeUrl(url, 0L, AudioDecoder.DEFAULT_HEAD_DECODE_MS) },
            decodeTailSamples = if (durationMs > AudioDecoder.TAIL_WINDOW_MS) {
                { AudioDecoder.decodeUrl(url, durationMs - AudioDecoder.TAIL_WINDOW_MS, durationMs) }
            } else null,
        )
    }

    private suspend fun analyzeWithSource(
        trackId: String,
        durationSeconds: Double?,
        source: String,
        decodeHeadSamples: () -> FloatArray?,
        decodeTailSamples: (() -> FloatArray?)? = null,
        decodeFullSamples: (() -> FloatArray?)? = null,
    ): TrackAnalysisResult? {
        if (trackId.isBlank()) return null
        getOrFetchCached(trackId)?.let { return it }

        val existingJob = inFlight[trackId]
        if (existingJob != null) {
            return existingJob.await()
        }

        val newJob = analyzerScope.async {
            analysisSemaphore.withPermit {
                getOrFetchCached(trackId)?.let { return@withPermit it }

                try {
                    val duration = durationSeconds ?: 0.0
                    val fullSamples = decodeFullSamples?.invoke()
                    if (fullSamples != null && fullSamples.isNotEmpty()) {
                        val result = TrackFeatures.analyze(fullSamples, duration)
                        if (result != null) {
                            putCached(trackId, result)
                            return@withPermit result
                        }
                    }

                    val headSamples = decodeHeadSamples() ?: return@withPermit null
                    val headResult = TrackFeatures.analyze(headSamples, duration) ?: return@withPermit null

                    val tailSamples = decodeTailSamples?.invoke()
                    val finalResult = if (tailSamples != null && tailSamples.isNotEmpty() && duration > 0.0) {
                        val cliffOffset = TrackFeatures.energyCliff(tailSamples, duration)
                        val computedMixOut = if (cliffOffset > 0.0) {
                            (duration - (AudioDecoder.TAIL_WINDOW_MS / 1000.0) + cliffOffset).coerceIn(0.0, duration)
                        } else {
                            headResult.mixOutTime.takeIf { it > 0.0 } ?: (duration - 6.0).coerceAtLeast(0.0)
                        }
                        headResult.copy(
                            mixOutTime = computedMixOut,
                            contentEndTime = duration,
                        )
                    } else {
                        headResult
                    }

                    putCached(trackId, finalResult)
                    finalResult
                } catch (e: Exception) {
                    Timber.tag("TrackAnalyzer").e(e, "Analysis failed for trackId=$trackId source=$source")
                    null
                }
            }
        }

        inFlight[trackId] = newJob
        try {
            return newJob.await()
        } finally {
            inFlight.remove(trackId)
        }
    }
}
