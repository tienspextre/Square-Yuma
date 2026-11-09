/*
 * Window choice, seam handling and per-side grids adapted from BitChord's
 * TrackAnalyzer (GPL-3.0-or-later), Copyright (C) 2026 Kushagra Singh.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */

package moe.rukamori.archivetune.playback.smart

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The ONNX pass (Beat This! grid + open-unmix vocal mask) over a track's opening and ending,
 * producing [moe.rukamori.archivetune.audiodsp.TrackAnalysisResult.modelJson]:
 * `{"headBpm","tailBpm","conf","downbeats":[..],"vocal":[{"start","fps","v":[..]}..]}`, all times
 * on the track clock. Entirely optional: any failure is "" and the DSP result stands.
 */
internal object ModelPass {
    private const val TAG = "ModelPass"

    /** Windows over the opening (~43 s) and the ending (~65 s), as BitChord runs them. */
    const val HEAD_WINDOWS = 2
    const val TAIL_WINDOWS = 3
    const val WINDOW_OVERLAP_SECONDS = 1.0
    private const val INFERENCE_THREADS = 2

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // One pass at a time: the vocal model alone holds ~32 MB of native tensors per inference, and
    // the analyzer runs two tracks at once.
    private val mutex = Mutex()

    private val beats by lazy { BeatTracker({ unpackedAsset(BeatTracker.MODEL_ASSET) }, { INFERENCE_THREADS }) }
    private val vocals by lazy { VocalTracker({ unpackedAsset(VocalTracker.MODEL_ASSET) }, { INFERENCE_THREADS }) }

    /**
     * ONNX Runtime wants a file it can map; an APK asset is not one. Unpacked once by name, through
     * a temporary file so a copy cut short by a kill is never taken for the real one.
     */
    private fun unpackedAsset(name: String): String {
        val context = appContext ?: error("ModelPass.init not called")
        val file = File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            val tmp = File(context.filesDir, "$name.tmp")
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) error("could not move $tmp into place")
        }
        return file.absolutePath
    }

    /** Seconds one window covers: the vocal model's fixed input, so a window is one inference of each model. */
    fun windowSeconds(): Double =
        if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else {
            BeatTracker.WINDOW_SECONDS
        }

    fun headEnd(duration: Double, span: Double) =
        minOf(duration, HEAD_WINDOWS * span - (HEAD_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)

    fun tailStart(duration: Double, span: Double) =
        max(0.0, duration - (TAIL_WINDOWS * span - (TAIL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))

    /**
     * Runs both models over the head and tail windows of a [duration]-second track, decoding each
     * window with [decode] (start, end seconds). Returns the model JSON, or "" when nothing came of it.
     */
    suspend fun run(duration: Double, decode: (Double, Double) -> AudioDecoder.StereoRegion?): String {
        // ponytail: an unknown duration skips the pass rather than probing the container for one.
        if (appContext == null || duration <= 0.0 || !MelSpectrogram.available) return ""
        return mutex.withLock {
            runCatching {
                val started = System.currentTimeMillis()
                val span = windowSeconds()
                val headEnd = headEnd(duration, span)
                val tailStart = tailStart(duration, span)
                val windows = modelWindows(headEnd, tailStart, duration, span)
                    .mapNotNull { (from, to) -> window(decode, from, to) }
                val json = buildJson(windows, headEnd, tailStart, duration, VocalSpectrogram.frameRate)
                Timber.tag(TAG).d(
                    "model pass ${System.currentTimeMillis() - started}ms windows=${windows.size} " +
                        "grids=${windows.count { it.grid != null }} vocal=${windows.count { it.vocal != null }}",
                )
                json
            }.onFailure { Timber.tag(TAG).w(it, "Model pass failed") }.getOrDefault("")
        }
    }

    /** What one window contributes, once its audio has been let go of. */
    class Window(val start: Double, val seconds: Double, val grid: BeatTracker.Grid?, val vocal: FloatArray?)

    /**
     * Decodes one window and runs both models on it. A function of its own so the stereo region and
     * its downmix are collectible the moment it returns, not held through the next window's decode.
     */
    private fun window(decode: (Double, Double) -> AudioDecoder.StereoRegion?, from: Double, to: Double): Window? {
        val region = decode(from, to) ?: return null
        if (region.left.size < region.sampleRate) return null
        val grid = beatInput(region)?.let { beats.track(it, offsetSeconds = region.start) }
        return Window(region.start, region.left.size / region.sampleRate, grid, vocalValues(region))
    }

    /** Mono at the mel front end's rate; the downmix dies with this frame. */
    private fun beatInput(region: AudioDecoder.StereoRegion): FloatArray? {
        val mono = FloatArray(region.left.size) { (region.left[it] + region.right[it]) * 0.5f }
        return if (abs(region.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, region.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }
    }

    /** The vocal model refuses input wider than its graph, so a window that came back long is trimmed. */
    private fun vocalValues(region: AudioDecoder.StereoRegion): FloatArray? {
        if (!VocalSpectrogram.available) return null
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * region.sampleRate).toInt().coerceAtMost(region.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < region.left.size) region.left.copyOf(maxSamples) else region.left
        val right = if (maxSamples < region.right.size) region.right.copyOf(maxSamples) else region.right
        return vocals.track(left, right, region.sampleRate)
    }

    /**
     * [HEAD_WINDOWS] from the start up to [headEnd] and [TAIL_WINDOWS] from [tailStart] to the end,
     * each [span] long and overlapping its neighbour by at least [WINDOW_OVERLAP_SECONDS] so the beat
     * at a seam is seen from both sides. A short track, where the spans meet, is covered once.
     */
    fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) listOf(0.0 to duration) else listOf(0.0 to headEnd, tailStart to duration)
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    fun seamBeats(windows: List<Window>): Beats {
        val graded = windows.filter { it.grid != null }.sortedBy { it.start }
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, window) in graded.withIndex()) {
            val grid = window.grid!!
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + window.start) / 2 }
                ?.takeIf { it > window.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (window.start + window.seconds + it.start) / 2 }
                ?.takeIf { it < window.start + window.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    /** Tempo and downbeats over [from]..[to], or null where the model found too few beats there. */
    private fun side(all: Beats, from: Double, to: Double): Pair<Double, List<Double>>? {
        val bpm = BeatTracker.tempoFromBeats(all.beats.filter { it in from..to })
        if (bpm <= 0) return null
        return bpm to all.downbeats.filter { it in from..to }
    }

    fun buildJson(windows: List<Window>, headEnd: Double, tailStart: Double, duration: Double, vocalFps: Double): String {
        if (windows.none { it.grid != null || it.vocal != null }) return ""
        val all = seamBeats(windows)
        val head = side(all, 0.0, headEnd)
        val tail = side(all, tailStart, duration)
        val downbeats = (head?.second.orEmpty() + tail?.second.orEmpty()).distinct().sorted()
        val vocal = JSONArray()
        for (window in windows) {
            val values = window.vocal ?: continue
            vocal.put(
                JSONObject()
                    .put("start", window.start)
                    .put("fps", vocalFps)
                    .put("v", JSONArray().apply { values.forEach { put(if (it.isFinite()) (it * 1000).roundToInt() / 1000.0 else 0.0) } }),
            )
        }
        return JSONObject()
            .put("headBpm", head?.first ?: 0.0)
            .put("tailBpm", tail?.first ?: 0.0)
            .put("conf", all.confidence)
            .put("downbeats", JSONArray().apply { downbeats.forEach { put(it) } })
            .put("vocal", vocal)
            .toString()
    }
}
