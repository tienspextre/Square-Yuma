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
import moe.rukamori.archivetune.audiodsp.DrumGridQaResult
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import org.json.JSONObject
import timber.log.Timber

object TrackAnalyzer : AnalysisStore {
    private const val KICK_OFF_COOLDOWN_MS = 10_000L
    private const val CACHE_VERSION = 5
    private val analyzerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val memoryCache = ConcurrentHashMap<String, TrackAnalysisResult>()
    private val inFlight = ConcurrentHashMap<String, Deferred<TrackAnalysisResult?>>()
    private val lastKickTimestamps = ConcurrentHashMap<String, Long>()
    private val analysisSemaphore = Semaphore(1)

    /** Ids whose cache entry holds a DSP-only result (the model pass was skipped by the battery guard). */
    private val modelSkipped = ConcurrentHashMap.newKeySet<String>()

    /** The two most recently requested ids: the current track, then the next one. Older queued work is dropped. */
    private val recent = ArrayDeque<String>()
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
        ModelSource.init(context)
        ModelPass.shouldSkip = batteryGuard(context.applicationContext)
    }

    /** Battery Saver, or (API 29+) thermal status MODERATE or worse. */
    fun batteryGuard(context: Context): () -> Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return {
            power != null && (
                power.isPowerSaveMode ||
                    (android.os.Build.VERSION.SDK_INT >= 29 &&
                        power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE)
                )
        }
    }

    private fun noteRequest(trackId: String) = synchronized(recent) {
        recent.remove(trackId)
        recent.addLast(trackId)
        while (recent.size > 2) recent.removeFirst()
    }

    private fun isWanted(trackId: String) = synchronized(recent) { trackId in recent }

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

    /** Cached without the model pass (battery guard), and the guard has lifted. */
    fun needsModelUpgrade(trackId: String): Boolean = trackId in modelSkipped && !ModelPass.shouldSkip()

    suspend fun getOrFetchCached(trackId: String): TrackAnalysisResult? {
        if (trackId.isBlank()) return null
        memoryCache[trackId]?.let { return it }

        val diskResult = withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFileFor(trackId)
                if (file != null && file.exists() && file.canRead()) {
                    val text = file.readText(Charsets.UTF_8)
                    val json = JSONObject(text)
                    // Older entries lack a streamed song's tail features (v2) or the model pass (v3); analyse those again.
                    if (json.optInt("v", 1) < CACHE_VERSION) return@runCatching null
                    if (json.optBoolean("modelSkipped", false)) modelSkipped.add(trackId)
                    TrackAnalysisResult(
                        bpm = json.optDouble("bpm", 0.0),
                        mixInTime = json.optDouble("mixInTime", 0.0),
                        mixOutTime = json.optDouble("mixOutTime", 0.0),
                        contentEndTime = json.optDouble("contentEndTime", 0.0),
                        firstBeat = json.optDouble("firstBeat", 0.0),
                        rawJson = json.optString("rawJson", text),
                        loudnessLufs = json.optDouble("loudnessLufs", 0.0),
                        dynamicRangeDb = json.optDouble("dynamicRangeDb", 0.0),
                        tailRawJson = json.optString("tailRawJson", ""),
                        tailOffset = json.optDouble("tailOffset", 0.0),
                        modelJson = json.optString("modelJson", ""),
                        diagnosticsJson = json.optString("diagnosticsJson", "").ifEmpty { null },
                        drumGridQa = json.optJSONObject("drumGridQa")?.let {
                            DrumGridQaResult(it.optDouble("score", 0.0), it.optDouble("driftMs", 0.0), it.optBoolean("passed", false))
                        },
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

    fun putCached(trackId: String, result: TrackAnalysisResult, persist: Boolean = true, skippedModel: Boolean = false) {
        if (skippedModel) modelSkipped.add(trackId) else modelSkipped.remove(trackId)
        memoryCache[trackId] = result
        _analysisEvents.tryEmit(trackId to result)
        if (!persist) return
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
                    put("tailRawJson", result.tailRawJson)
                    put("tailOffset", result.tailOffset)
                    put("modelJson", result.modelJson)
                    result.diagnosticsJson?.let { put("diagnosticsJson", it) }
                    result.drumGridQa?.let {
                        put("drumGridQa", JSONObject().put("score", it.score).put("driftMs", it.driftMs).put("passed", it.passed))
                    }
                    put("modelSkipped", skippedModel)
                    put("v", CACHE_VERSION)
                }
                file.writeText(json.toString(), Charsets.UTF_8)
            }
        }
    }

    fun clearCache() {
        memoryCache.clear()
        modelSkipped.clear()
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
            decodeStereo = { from, to -> AudioDecoder.decodeRegionStereo({ it.setDataSource(file.absolutePath) }, from, to) },
            decodeHeadSamples = { AudioDecoder.decode(file, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && AudioDecoder.tailWindowMs(durationMs) != null) {
                { AudioDecoder.decodeTail(file, durationMs) }
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
            decodeStereo = { from, to -> AudioDecoder.decodeRegionStereo({ it.setDataSource(file.absolutePath) }, from, to) },
            decodeHeadSamples = { AudioDecoder.decode(file, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && AudioDecoder.tailWindowMs(durationMs) != null) {
                { AudioDecoder.decodeTail(file, durationMs) }
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
            decodeStereo = { from, to -> AudioDecoder.decodeRegionStereo({ it.setDataSource(context, uri, null) }, from, to) },
            decodeHeadSamples = { AudioDecoder.decode(context, uri, startMs, endMs) },
            decodeTailSamples = if (durationMs != null && AudioDecoder.tailWindowMs(durationMs) != null) {
                { AudioDecoder.decodeTail(context, uri, durationMs) }
            } else null,
        )
    }

    /** Head + tail windows over HTTP Range requests. */
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
            decodeStereo = { from, to -> AudioDecoder.decodeRegionStereo({ it.setDataSource(url, emptyMap()) }, from, to) },
            decodeHeadSamples = { AudioDecoder.decodeUrl(url, 0L, AudioDecoder.DEFAULT_HEAD_DECODE_MS) },
            decodeTailSamples = AudioDecoder.tailWindowMs(durationMs)?.let { (from, to) ->
                { AudioDecoder.decodeUrl(url, from, to) }
            },
        )
    }

    private suspend fun analyzeWithSource(
        trackId: String,
        durationSeconds: Double?,
        source: String,
        decodeStereo: (Double, Double) -> StereoRegion?,
        decodeHeadSamples: () -> FloatArray?,
        decodeTailSamples: (() -> FloatArray?)? = null,
    ): TrackAnalysisResult? {
        if (trackId.isBlank()) return null
        noteRequest(trackId)
        // A DSP-only entry is final while the guard blocks; once it lifts, only the model pass is owed.
        getOrFetchCached(trackId)?.let { if (trackId !in modelSkipped || ModelPass.shouldSkip()) return it }

        val existingJob = inFlight[trackId]
        if (existingJob != null) {
            return existingJob.await()
        }

        val newJob = analyzerScope.async {
            analysisSemaphore.withPermit {
                if (!isWanted(trackId)) return@withPermit null
                val duration = durationSeconds ?: 0.0
                val cached = getOrFetchCached(trackId)
                if (cached != null && (trackId !in modelSkipped || ModelPass.shouldSkip())) return@withPermit cached

                try {
                    if (cached != null) {
                        // Upgrade: the DSP result stands, only the model pass runs.
                        val model = ModelPass.run(duration, decodeStereo)
                        if (model.isEmpty()) return@withPermit cached
                        val upgraded = cached.copy(modelJson = model)
                        putCached(trackId, upgraded)
                        return@withPermit upgraded
                    }
                    // DSP first, in its own frame so its decoded PCM is gone before the models run.
                    val dsp = analyzeDsp(duration, decodeHeadSamples, decodeTailSamples)
                        ?: return@withPermit null
                    val skipped = ModelPass.shouldSkip()
                    val result = if (skipped) dsp else dsp.copy(modelJson = ModelPass.run(duration, decodeStereo))
                    // A stream whose tail or model pass failed (a dropped connection, an unseekable
                    // transcode) is kept for this run only, so the next play analyses it again.
                    val complete = (skipped || result.modelJson.isNotEmpty()) &&
                        (decodeTailSamples == null || result.tailRawJson.isNotEmpty())
                    putCached(trackId, result, persist = complete, skippedModel = skipped)
                    result
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

    private fun analyzeDsp(
        duration: Double,
        decodeHeadSamples: () -> FloatArray?,
        decodeTailSamples: (() -> FloatArray?)?,
    ): TrackAnalysisResult? {
        val headSamples = decodeHeadSamples() ?: return null
        val headResult = TrackFeatures.analyze(headSamples, duration) ?: return null

        val tailSamples = decodeTailSamples?.invoke()
        val finalResult = if (tailSamples != null && tailSamples.isNotEmpty() && duration > 0.0) {
            val tailSeconds = tailSamples.size / (runCatching { TrackFeatures.sampleRate() }.getOrNull()?.takeIf { it > 0.0 } ?: AudioDecoder.TARGET_SAMPLE_RATE)
            val cliffOffset = TrackFeatures.energyCliff(tailSamples, duration)
            val computedMixOut = if (cliffOffset > 0.0) {
                (duration - tailSeconds + cliffOffset).coerceIn(0.0, duration)
            } else {
                headResult.mixOutTime.takeIf { it > 0.0 } ?: (duration - 6.0).coerceAtLeast(0.0)
            }
            // Loudness from the head alone misreads a quiet intro as a quiet song; power-
            // average it with the tail window, weighted by window length (both 65 s now).
            // The tail on its own clock (its real length), so the pair planner can read
            // the ending's beats, phrases and singing; times shift by tailOffset.
            val tailResult = TrackFeatures.analyze(tailSamples, tailSeconds)
            val tailLufs = tailResult?.loudnessLufs
            val lufs = if (tailLufs != null && tailLufs < 0.0 && headResult.loudnessLufs < 0.0) {
                val hW = AudioDecoder.DEFAULT_HEAD_DECODE_MS.toDouble()
                val tW = AudioDecoder.TAIL_WINDOW_MS.toDouble()
                10.0 * kotlin.math.log10(
                    (hW * Math.pow(10.0, headResult.loudnessLufs / 10.0) + tW * Math.pow(10.0, tailLufs / 10.0)) / (hW + tW),
                )
            } else headResult.loudnessLufs
            headResult.copy(
                mixOutTime = computedMixOut,
                contentEndTime = duration,
                loudnessLufs = lufs,
                tailRawJson = tailResult?.rawJson.orEmpty(),
                tailOffset = duration - tailSeconds,
            )
        } else {
            headResult
        }

        return finalResult
    }
}
