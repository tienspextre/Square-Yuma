package dev.lelonio.square.desktop.nativeaudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.audiodsp.AutomixPlan
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import moe.rukamori.archivetune.audiodsp.pulse.VocalDuckMode
import moe.rukamori.archivetune.playback.smart.TrackFeatures
import moe.rukamori.archivetune.playback.smart.loadAnalysisLibrary

/**
 * JNI bridge to `square_win_native` (`square_win_native.dll` on Windows,
 * `libsquare_win_native.so` on Linux).
 */
object NativeEngine {
    interface Callback {
        fun onEnded(deck: Int)
        fun onDeviceLost(reason: String)
        fun onSmtcButton(button: String)
        fun onError(deck: Int, message: String)
    }

    @Serializable
    data class DspFilterPayload(
        val lowPassHz: Double = 20_000.0,
        val highPassHz: Double = 20.0,
        val bassDb: Double = 0.0,
    )

    @Serializable
    data class DspPayload(
        val speed: Float = 1f,
        val pitch: Float = 1f,
        val eq: List<Float> = List(10) { 0f },
        val preampDb: Float = 0f,
        val reverb: Float = 0f,
        val karaoke: Float = 0f,
        val filter: DspFilterPayload = DspFilterPayload(),
        val loudness: Boolean = false,
        val normalizeGainDb: Float = 0f,
        val volume: Float = 0.8f,
        val bitPerfect: Boolean = false,
    )

    @Serializable
    data class TempoPointPayload(val timeMs: Long, val ratio: Float)

    @Serializable
    data class TempoMapPayload(val points: List<TempoPointPayload>)

    @Serializable
    data class MixPlanPayload(
        val triggerOffsetMs: Long,
        val durationMs: Long,
        val incomingStartMs: Long,
        val enableBassSwap: Boolean,
        val triggerAtMs: Long? = null,
        val tier: String,
        val incomingTempoRatio: Float = 1f,
        val style: String? = null,
        val swapAt: Double = 0.5,
        val swapWidth: Double = 0.1,
        val vocalClash: Double = 0.0,
        val loopStartMs: Long? = null,
        val loopEndMs: Long? = null,
        val tempoMap: TempoMapPayload? = null,
        val vocalDuckMode: String = "NONE",
        val outgoingEnergyFill: Boolean = false,
        val incomingDeck: Int = -1,
        val incomingUri: String = "",
        val incomingHeadersJson: String = "",
    )

    @Serializable
    data class SmtcPayload(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val status: String = "stopped",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Volatile
    private var loaded = false
    @Volatile
    private var activeCallback: Callback? = null

    val hasRegisteredCallback: Boolean get() = activeCallback != null
    val registeredCallbackCount: Int get() = if (activeCallback != null) 1 else 0

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                loadAnalysisLibrary()
                loaded = true
            }
        }
    }

    fun init(): Boolean {
        ensureLoaded()
        return nativeInit()
    }

    fun shutdown() {
        activeCallback = null
        if (!loaded) return
        nativeShutdown()
    }

    fun deckLoad(deck: Int, uri: String, headersJson: String? = null, startMs: Long = 0L): Boolean {
        ensureLoaded()
        return nativeDeckLoad(deck, uri, headersJson, startMs)
    }

    fun deckPlay(deck: Int) {
        ensureLoaded()
        nativeDeckPlay(deck)
    }

    fun deckPause(deck: Int) {
        ensureLoaded()
        nativeDeckPause(deck)
    }

    fun deckSeek(deck: Int, ms: Long) {
        ensureLoaded()
        nativeDeckSeek(deck, ms)
    }

    fun setOutput(mode: String, deviceId: String? = null): Boolean {
        ensureLoaded()
        return nativeSetOutput(mode, deviceId)
    }

    fun listDevices(): String {
        ensureLoaded()
        return nativeListDevices()
    }

    fun setDsp(jsonStr: String) {
        ensureLoaded()
        nativeSetDsp(jsonStr)
    }

    fun setDsp(payload: DspPayload) = setDsp(json.encodeToString(payload))

    fun scheduleMix(planJson: String): Boolean {
        ensureLoaded()
        return nativeScheduleMix(planJson)
    }

    fun scheduleMix(
        plan: AutomixPlan,
        incomingDeck: Int = -1,
        incomingUri: String = "",
        incomingHeadersJson: String = "",
        vocalDuckMode: VocalDuckMode = VocalDuckMode.NONE,
        outgoingEnergyFill: Boolean = false,
    ): Boolean {
        val payload = MixPlanPayload(
            triggerOffsetMs = plan.triggerOffsetMs,
            durationMs = plan.durationMs,
            incomingStartMs = plan.incomingStartMs,
            enableBassSwap = plan.enableBassSwap,
            triggerAtMs = plan.triggerAtMs,
            tier = plan.tier.name,
            incomingTempoRatio = plan.incomingTempoRatio,
            style = plan.style?.name,
            swapAt = plan.swapAt,
            swapWidth = plan.swapWidth,
            vocalClash = plan.vocalClash,
            loopStartMs = plan.loopStartMs,
            loopEndMs = plan.loopEndMs,
            tempoMap = plan.tempoMap?.let { tm ->
                TempoMapPayload(tm.points.map { TempoPointPayload(it.timeMs, it.ratio) })
            },
            vocalDuckMode = vocalDuckMode.name,
            outgoingEnergyFill = outgoingEnergyFill,
            incomingDeck = incomingDeck,
            incomingUri = incomingUri,
            incomingHeadersJson = incomingHeadersJson,
        )
        return scheduleMix(json.encodeToString(payload))
    }

    fun setSleepTimer(ms: Long, fadeMs: Long, endOfTrack: Boolean) {
        ensureLoaded()
        nativeSetSleepTimer(ms, fadeMs, endOfTrack)
    }

    fun position(deck: Int): Long {
        ensureLoaded()
        return nativePosition(deck)
    }

    fun status(): String {
        ensureLoaded()
        return nativeStatus()
    }

    fun smtcUpdate(jsonStr: String, artwork: ByteArray? = null) {
        ensureLoaded()
        nativeSmtcUpdate(jsonStr, artwork)
    }

    fun smtcUpdate(payload: SmtcPayload, artwork: ByteArray? = null) =
        smtcUpdate(json.encodeToString(payload), artwork)

    fun setCallback(cb: Callback?) {
        activeCallback = cb
        ensureLoaded()
        nativeSetCallback(cb)
    }

    fun analyze(samples: FloatArray, duration: Double, sampleRate: Double = TrackFeatures.sampleRate()): TrackAnalysisResult? {
        ensureLoaded()
        return TrackFeatures.analyze(samples, duration, sampleRate)
    }

    fun probeTrack(uri: String, headersJson: String? = null): String {
        ensureLoaded()
        return nativeProbeTrack(uri, headersJson)
    }

    fun decodeToFloat(
        uri: String,
        headersJson: String? = null,
        fromMs: Long = 0L,
        durationMs: Long = 0L,
        targetRate: Int = 0,
        targetChannels: Int = 0,
    ): FloatArray? {
        ensureLoaded()
        return nativeDecodeToFloat(uri, headersJson, fromMs, durationMs, targetRate, targetChannels)
    }

    /** Computes integrated EBU R128 LUFS via native libebur128; returns null if unavailable/silent. */
    fun measureTrackLufs(uri: String, headersJson: String? = null): Double? {
        ensureLoaded()
        val lufs = nativeMeasureTrackLufs(uri, headersJson)
        return lufs.takeIf { it.isFinite() && it > -70.0 && it < 12.0 }
    }

    fun selfCheckRender(): String {
        ensureLoaded()
        return nativeSelfCheckRender()
    }

    /** Extracts HWND via JAWT on Windows (returns 0 on Linux/macOS). */
    fun getWindowHwnd(awtWindow: java.awt.Window): Long {
        if (!System.getProperty("os.name").startsWith("Windows")) return 0L
        ensureLoaded()
        runCatching { System.loadLibrary("jawt") }
        return nativeGetWindowHwnd(awtWindow)
    }

    /** Installs the Win32 frameless subclass on [hwnd] (no-op returning false outside Windows). */
    fun installFrame(
        hwnd: Long,
        captionHeightPx: Int,
        resizeBorderPx: Int,
        minWidthPx: Int,
        minHeightPx: Int,
        buttonRects: IntArray,
        interactiveRects: IntArray,
    ): Boolean {
        if (hwnd == 0L || !System.getProperty("os.name").startsWith("Windows")) return false
        ensureLoaded()
        return nativeInstallFrame(hwnd, captionHeightPx, resizeBorderPx, minWidthPx, minHeightPx, buttonRects, interactiveRects)
    }

    fun updateFrameLayout(
        hwnd: Long,
        captionHeightPx: Int,
        resizeBorderPx: Int,
        minWidthPx: Int,
        minHeightPx: Int,
        buttonRects: IntArray,
        interactiveRects: IntArray,
    ) {
        if (hwnd == 0L || !System.getProperty("os.name").startsWith("Windows")) return
        ensureLoaded()
        nativeUpdateFrameLayout(hwnd, captionHeightPx, resizeBorderPx, minWidthPx, minHeightPx, buttonRects, interactiveRects)
    }

    fun uninstallFrame(hwnd: Long) {
        if (hwnd == 0L || !System.getProperty("os.name").startsWith("Windows")) return
        if (!loaded) return
        nativeUninstallFrame(hwnd)
    }

    @JvmStatic private external fun nativeInit(): Boolean
    @JvmStatic private external fun nativeShutdown()
    @JvmStatic private external fun nativeDeckLoad(deck: Int, uri: String, headersJson: String?, startMs: Long): Boolean
    @JvmStatic private external fun nativeDeckPlay(deck: Int)
    @JvmStatic private external fun nativeDeckPause(deck: Int)
    @JvmStatic private external fun nativeDeckSeek(deck: Int, ms: Long)
    @JvmStatic private external fun nativeSetOutput(mode: String, deviceId: String?): Boolean
    @JvmStatic private external fun nativeListDevices(): String
    @JvmStatic private external fun nativeSetDsp(json: String)
    @JvmStatic private external fun nativeScheduleMix(planJson: String): Boolean
    @JvmStatic private external fun nativeSetSleepTimer(ms: Long, fadeMs: Long, endOfTrack: Boolean)
    @JvmStatic private external fun nativePosition(deck: Int): Long
    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeSmtcUpdate(json: String, artwork: ByteArray?)
    @JvmStatic private external fun nativeSetCallback(cb: Callback?)
    @JvmStatic private external fun nativeProbeTrack(uri: String, headersJson: String?): String
    @JvmStatic private external fun nativeDecodeToFloat(
        uri: String,
        headersJson: String?,
        fromMs: Long,
        durationMs: Long,
        targetRate: Int,
        targetChannels: Int,
    ): FloatArray?
    @JvmStatic private external fun nativeMeasureTrackLufs(uri: String, headersJson: String?): Double
    @JvmStatic private external fun nativeSelfCheckRender(): String
    @JvmStatic private external fun nativeGetWindowHwnd(awtWindow: java.awt.Window): Long
    @JvmStatic private external fun nativeInstallFrame(
        hwnd: Long,
        captionHeightPx: Int,
        resizeBorderPx: Int,
        minWidthPx: Int,
        minHeightPx: Int,
        buttonRects: IntArray,
        interactiveRects: IntArray,
    ): Boolean
    @JvmStatic private external fun nativeUpdateFrameLayout(
        hwnd: Long,
        captionHeightPx: Int,
        resizeBorderPx: Int,
        minWidthPx: Int,
        minHeightPx: Int,
        buttonRects: IntArray,
        interactiveRects: IntArray,
    )
    @JvmStatic private external fun nativeUninstallFrame(hwnd: Long)
}
