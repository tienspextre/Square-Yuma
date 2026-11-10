package dev.lelonio.square.desktop

import android.content.Context
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.audiodsp.AutomixPlan
import moe.rukamori.archivetune.audiodsp.MixFeatures
import moe.rukamori.archivetune.audiodsp.MixStyle
import moe.rukamori.archivetune.audiodsp.MixStyles
import moe.rukamori.archivetune.audiodsp.PairPlanner
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import moe.rukamori.archivetune.audiodsp.TransitionPlanner
import moe.rukamori.archivetune.audiodsp.TransitionRules
import moe.rukamori.archivetune.audiodsp.TransitionTier
import moe.rukamori.archivetune.audiodsp.incomingBassGainDb
import moe.rukamori.archivetune.audiodsp.incomingStageGain
import moe.rukamori.archivetune.audiodsp.outgoingBassGainDb
import moe.rukamori.archivetune.audiodsp.outgoingLowPassHz
import moe.rukamori.archivetune.audiodsp.outgoingStageGain
import moe.rukamori.archivetune.playback.smart.AudioDecoder
import moe.rukamori.archivetune.playback.smart.ModelPass
import moe.rukamori.archivetune.playback.smart.TrackFeatures
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Loads the phone's analysis library from the app's resources (beside ffmpeg); throws UnsatisfiedLinkError when absent. */
fun loadYumaAnalysis() {
    val name = System.mapLibraryName("yuma_analysis")
    val file = System.getProperty("compose.application.resources.dir")?.let { File(it, name) }?.takeIf { it.isFile }
        ?: throw UnsatisfiedLinkError("$name not in the app's resources")
    System.load(file.absolutePath)
}

/**
 * The phone's Smart Automix analysis for the desktop: the same C++ features and
 * ONNX beat/vocal models, fed by ffmpeg. A whole song is decoded to 11025 Hz mono
 * (the phone streams only head and tail; here the full pass is cheap), then the
 * model pass reads its five stereo windows. Results are kept in
 * ~/.square-yuma/analysis, apart from the phone's (a different decoder).
 */
class Analyses(private val source: (SubsonicChildSong) -> String) {
    private val dir = File(System.getProperty("user.home"), ".square-yuma/analysis").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, TrackAnalysisResult>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { ModelPass.init(Context()) }

    fun get(id: String): TrackAnalysisResult? = memory[id] ?: load(id)?.also { memory[id] = it }

    /** Starts analysing [song] unless it is known or under way. */
    fun request(song: SubsonicChildSong) {
        if (song.duration < 30 || get(song.id) != null || !running.add(song.id)) return
        scope.launch {
            try {
                gate.withPermit { analyze(song)?.let { memory[song.id] = it; save(song.id, it) } }
            } catch (t: Throwable) {
                System.err.println("automix analysis failed for ${song.title}: $t")
            } finally { running.remove(song.id) }
        }
    }

    private suspend fun analyze(song: SubsonicChildSong): TrackAnalysisResult? {
        val url = source(song)
        val rate = TrackFeatures.sampleRate()
        val mono = floats(url, null, null, 1, rate.toInt()) ?: return null
        val duration = mono.size / rate
        val dsp = TrackFeatures.analyze(mono, duration) ?: return null
        val model = ModelPass.run(duration) { from, to ->
            val st = floats(url, from, to - from, 2, 44_100) ?: return@run null
            val n = st.size / 2
            AudioDecoder.StereoRegion(FloatArray(n) { st[2 * it] }, FloatArray(n) { st[2 * it + 1] }, 44_100.0, from)
        }
        return dsp.copy(modelJson = model)
    }

    /** f32le PCM from ffmpeg: [channels] at [rate], optionally [seconds] from [from]. */
    private fun floats(url: String, from: Double?, seconds: Double?, channels: Int, rate: Int): FloatArray? {
        val args = mutableListOf(DesktopPlayer.ffmpegPath, "-nostdin", "-loglevel", "error")
        from?.let { args += listOf("-ss", "%.3f".format(java.util.Locale.ROOT, it)) }
        args += listOf("-i", url)
        seconds?.let { args += listOf("-t", "%.3f".format(java.util.Locale.ROOT, it)) }
        args += listOf("-ac", "$channels", "-ar", "$rate", "-f", "f32le", "-")
        val bytes = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start().inputStream.use { it.readBytes() }
        if (bytes.size < rate * channels * 4) return null
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun file(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    private fun save(id: String, r: TrackAnalysisResult) = runCatching {
        file(id).writeText(
            JSONObject().put("v", VERSION).put("bpm", r.bpm).put("mixIn", r.mixInTime).put("mixOut", r.mixOutTime)
                .put("end", r.contentEndTime).put("firstBeat", r.firstBeat).put("raw", r.rawJson).put("lufs", r.loudnessLufs)
                .put("dr", r.dynamicRangeDb).put("tail", r.tailRawJson).put("tailOffset", r.tailOffset).put("model", r.modelJson).toString(),
        )
    }

    private fun load(id: String): TrackAnalysisResult? = runCatching {
        val o = JSONObject(file(id).takeIf { it.isFile }?.readText() ?: return null)
        if (o.optInt("v") != VERSION) return null
        TrackAnalysisResult(
            o.getDouble("bpm"), o.getDouble("mixIn"), o.getDouble("mixOut"), o.getDouble("end"), o.getDouble("firstBeat"),
            o.getString("raw"), o.getDouble("lufs"), o.getDouble("dr"), o.optString("tail"), o.optDouble("tailOffset", 0.0), o.optString("model"),
        )
    }.getOrNull()

    /**
     * The phone's choice of transition (YumaDualDeckPlayer): the pair planner when
     * both songs are analysed, else the one-sided smart plan, else null (the
     * player's plain crossfade). Spoken or live tracks and albums played through
     * are not mixed.
     */
    fun plan(out: SubsonicChildSong, inc: SubsonicChildSong, queue: List<SubsonicChildSong>, shuffled: Boolean, fadeMs: Long, bassSwap: Boolean, loopMix: Boolean): AutomixPlan? {
        if (listOf(out, inc).any { TransitionRules.isSpokenOrLive(it.title, it.artist.orEmpty(), it.album.orEmpty()) }) return null
        if (TransitionRules.isAlbumPlaythrough(queue.map { it.album to it.artist }, shuffled)) return null
        val a = get(out.id) ?: return null
        val b = get(inc.id)
        val durationMs = out.duration * 1000
        if (b != null) runCatching {
            val o = MixFeatures.parse(a.rawJson, a.tailRawJson, a.tailOffset, a.modelJson)
            val i = MixFeatures.parse(b.rawJson, modelJson = b.modelJson)
            if (o != null && i != null) PairPlanner.plan(o, i, durationMs, fadeMs, bassSwap, loopMix)?.let { return it }
        }
        return runCatching { TransitionPlanner.planSmartTransition(a, b, durationMs, fadeMs) }.getOrNull()
            ?.takeIf { it.tier != TransitionTier.HARD_CUT && it.tier != TransitionTier.GAPLESS && it.durationMs > 0 }
    }

    companion object { const val VERSION = 1 }
}

/** One deck's DJ filter at progress p of a mix: linear gain, low-pass, high-pass (Hz), low-shelf (dB). */
data class Shape(val gain: Float, val lowPassHz: Double = MixStyles.OPEN_HZ, val highPassHz: Double = MixStyles.OFF_HZ, val bassDb: Double = 0.0)

/** The phone's ExoDeck.applyAutomation for both decks: (outgoing, incoming). */
fun automation(plan: AutomixPlan, progress: Float): Pair<Shape, Shape> {
    val p = progress.coerceIn(0f, 1f)
    val style = plan.style
    if (style != null && style != MixStyle.BLEND) {
        val (o, i) = MixStyles.shape(style, p, plan.swapAt, plan.swapWidth, plan.vocalClash)
        return Shape(o.gain, o.lowPassHz, o.highPassHz, o.bassDb) to Shape(i.gain, i.lowPassHz, i.highPassHz, i.bassDb)
    }
    val staged = plan.tier != TransitionTier.PLAIN_CROSSFADE || plan.enableBassSwap
    val inGain = if (staged) incomingStageGain(p) else sin(p * PI.toFloat() / 2)
    val outGain = if (staged) minOf(outgoingStageGain(p), 1f - inGain) else cos(p * PI.toFloat() / 2)
    return if (plan.enableBassSwap) {
        Shape(outGain, outgoingLowPassHz(p), bassDb = outgoingBassGainDb(p)) to Shape(inGain, bassDb = incomingBassGainDb(p))
    } else Shape(outGain) to Shape(inGain)
}

/**
 * The phone's DjFilterAudioProcessor on interleaved stereo floats: RBJ
 * Butterworth low-pass and high-pass, a 200 Hz low shelf. Coefficients move per
 * block; the delay lines carry over so a sweep does not click.
 */
class DjFilter(private val rate: Double = 44_100.0) {
    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        val x1 = DoubleArray(2); val x2 = DoubleArray(2); val y1 = DoubleArray(2); val y2 = DoubleArray(2)
        fun run(x: Double, c: Int): Double {
            val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = y
            return y
        }
        fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0
        }
    }
    private val lp = Biquad(); private val hp = Biquad(); private val shelf = Biquad()
    private var lpHz = -1.0; private var hpHz = -1.0; private var db = Double.NaN

    fun process(buf: FloatArray, n: Int, s: Shape) {
        val useLp = s.lowPassHz < 19_999; val useHp = s.highPassHz > 30; val useShelf = kotlin.math.abs(s.bassDb) >= 0.01
        if (!useLp && !useHp && !useShelf) return
        if (useLp && kotlin.math.abs(s.lowPassHz - lpHz) > 1) { lpHz = s.lowPassHz; pass(lp, lpHz, low = true) }
        if (useHp && kotlin.math.abs(s.highPassHz - hpHz) > 1) { hpHz = s.highPassHz; pass(hp, hpHz, low = false) }
        if (useShelf && !(kotlin.math.abs(s.bassDb - db) <= 0.05)) { db = s.bassDb; lowShelf(db) }
        for (i in 0 until n) {
            val c = i and 1
            var x = buf[i].toDouble()
            if (useLp) x = lp.run(x, c)
            if (useHp) x = hp.run(x, c)
            if (useShelf) x = shelf.run(x, c)
            buf[i] = x.toFloat()
        }
    }

    private fun pass(f: Biquad, hz: Double, low: Boolean) {
        val w = 2 * PI * hz.coerceIn(30.0, 0.45 * rate) / rate
        val alpha = sin(w) / (2 * 0.70710678); val cw = cos(w)
        if (low) f.set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
        else f.set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    private fun lowShelf(db: Double) {
        val a = sqrt(10.0.pow(db / 20)).coerceIn(0.001, 4.0)
        val w = 2 * PI * 200 / rate; val cw = cos(w); val sw = sin(w)
        val alpha = sw / 2 * sqrt(2.0) // S = 1
        val k = 2 * sqrt(a) * alpha
        shelf.set(
            a * ((a + 1) - (a - 1) * cw + k), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - k),
            (a + 1) + (a - 1) * cw + k, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - k,
        )
    }
}

/**
 * The incoming deck's tempo over its own clock: matched for the mix, then eased
 * back to the listener's speed over 6.5 s in 26 smoothstep steps, as the phone
 * does. Pairs of (seconds from the deck's start, speed).
 */
fun tempoSchedule(matched: Float, user: Float, mixMs: Long): List<Pair<Double, Float>> {
    val out = mutableListOf(0.0 to matched)
    var t = mixMs / 1000.0 * matched
    for (k in 1..26) {
        val f = k / 26f
        val s = matched + (user - matched) * f * f * (3 - 2 * f)
        out += t to s
        t += 0.25 * s
    }
    return out
}

/** The DJ filter does what its shape says and stays stable through a sweep; the tempo schedule lands on the listener's speed. */
internal fun automixCheck() {
    fun level(hz: Double, s: Shape): Double {
        val f = DjFilter(); val n = 44_100 * 2
        val buf = FloatArray(n) { sin(2 * PI * hz * (it / 2) / 44_100).toFloat() * 0.5f }
        var peak = 0.0
        for (at in 0 until n step 2048) {
            val block = buf.copyOfRange(at, minOf(n, at + 2048)); f.process(block, block.size, s)
            if (at > n / 2) block.forEach { peak = maxOf(peak, kotlin.math.abs(it.toDouble())) }
        }
        return 20 * kotlin.math.log10(peak / 0.5)
    }
    check(level(50.0, Shape(1f, bassDb = -24.0)) < -15) { "low shelf" }
    check(level(100.0, Shape(1f, highPassHz = 1200.0)) < -20) { "high-pass" }
    check(level(8000.0, Shape(1f, lowPassHz = 1000.0)) < -20) { "low-pass" }
    check(kotlin.math.abs(level(1000.0, Shape(1f, lowPassHz = 18000.0))) < 0.5) { "pass band" }
    val f = DjFilter(); val rnd = java.util.Random(1)
    for (k in 0 until 400) {
        val block = FloatArray(2048) { (rnd.nextFloat() - 0.5f) }
        f.process(block, block.size, Shape(1f, MixStyles.glide(7000.0, 300.0, k / 400.0), 20.0 + k, -24.0 * k / 400))
        check(block.all { it.isFinite() && kotlin.math.abs(it) < 4 }) { "sweep unstable" }
    }
    check(kotlin.math.abs(tempoSchedule(1.14f, 1f, 9000).last().second - 1f) < 1e-4f) { "ease-back" }
}

/** The native analysis and ONNX Runtime load on this machine: a 120 BPM click track gets features, and the beat model opens. */
internal fun nativeCheck() {
    val rate = TrackFeatures.sampleRate()
    val clicks = FloatArray((rate * 30).toInt()) { if (it % (rate / 2).toInt() < 40) 0.8f else 0f }
    val r = TrackFeatures.analyze(clicks, 30.0) ?: error("native analysis returned nothing")
    check(r.bpm > 0) { "no tempo from the click track" }
    check(moe.rukamori.archivetune.playback.smart.MelSpectrogram.available) { "mel front end missing" }
    val model = File(Context().filesDir, "check.onnx")
    Context::class.java.getResourceAsStream("/models/beat_this_final0_q8.onnx")!!.use { i -> model.outputStream().use { i.copyTo(it) } }
    ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(model.path).close()
    model.delete()
}
