package dev.lelonio.square.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Effect and engine settings shared between the player UI and the playback service.
 */
object AudioEffects {

    private var prefs: android.content.SharedPreferences? = null

    private val _reverb = MutableStateFlow(0f)
    val reverb: StateFlow<Float> = _reverb.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(1f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _karaoke = MutableStateFlow(0f)
    val karaoke: StateFlow<Float> = _karaoke.asStateFlow()

    // Audiophile & Dual-Deck Engine settings
    private val _bassBoost = MutableStateFlow(0)
    val bassBoost: StateFlow<Int> = _bassBoost.asStateFlow()

    private val _virtualizer = MutableStateFlow(0)
    val virtualizer: StateFlow<Int> = _virtualizer.asStateFlow()

    private val _equalizerEnabled = MutableStateFlow(false)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _outputGainMb = MutableStateFlow(0)
    val outputGainMb: StateFlow<Int> = _outputGainMb.asStateFlow()

    private val _audioOffload = MutableStateFlow(true)
    val audioOffload: StateFlow<Boolean> = _audioOffload.asStateFlow()

    private val _smartAutomix = MutableStateFlow(true)
    val smartAutomix: StateFlow<Boolean> = _smartAutomix.asStateFlow()

    private val _automixBassSwap = MutableStateFlow(true)
    val automixBassSwap: StateFlow<Boolean> = _automixBassSwap.asStateFlow()

    private val _crossfadeSeconds = MutableStateFlow(6)
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    private val _lyricsSyncOffsetMs = MutableStateFlow(0)
    val lyricsSyncOffsetMs: StateFlow<Int> = _lyricsSyncOffsetMs.asStateFlow()

    fun setKaraoke(amount: Float) {
        val wanted = amount.coerceIn(0f, 1f)
        if (wanted == _karaoke.value) return
        _karaoke.value = wanted
        prefs?.edit()?.putFloat(KEY_KARAOKE, wanted)?.commit()
    }

    fun setReverb(amount: Float) {
        val wanted = amount.coerceIn(0f, 1f)
        if (wanted == _reverb.value) return
        _reverb.value = wanted
        prefs?.edit()?.putFloat(KEY_REVERB, wanted)?.commit()
    }

    fun setBassBoost(strength: Int) {
        val wanted = strength.coerceIn(0, 1000)
        if (wanted == _bassBoost.value) return
        _bassBoost.value = wanted
        prefs?.edit()?.putInt(KEY_BASS_BOOST, wanted)?.commit()
    }

    fun setVirtualizer(strength: Int) {
        val wanted = strength.coerceIn(0, 1000)
        if (wanted == _virtualizer.value) return
        _virtualizer.value = wanted
        prefs?.edit()?.putInt(KEY_VIRTUALIZER, wanted)?.commit()
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        if (enabled == _equalizerEnabled.value) return
        _equalizerEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_EQ_ENABLED, enabled)?.commit()
    }

    fun setOutputGainMb(gainMb: Int) {
        val wanted = gainMb.coerceIn(-1500, 1500)
        if (wanted == _outputGainMb.value) return
        _outputGainMb.value = wanted
        prefs?.edit()?.putInt(KEY_OUTPUT_GAIN, wanted)?.commit()
    }

    fun setAudioOffload(enabled: Boolean) {
        if (enabled == _audioOffload.value) return
        _audioOffload.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUDIO_OFFLOAD, enabled)?.commit()
    }

    fun setSmartAutomix(enabled: Boolean) {
        if (enabled == _smartAutomix.value) return
        _smartAutomix.value = enabled
        prefs?.edit()?.putBoolean(KEY_SMART_AUTOMIX, enabled)?.commit()
    }

    fun setAutomixBassSwap(enabled: Boolean) {
        if (enabled == _automixBassSwap.value) return
        _automixBassSwap.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTOMIX_BASS_SWAP, enabled)?.commit()
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val wanted = seconds.coerceIn(0, 20)
        if (wanted == _crossfadeSeconds.value) return
        _crossfadeSeconds.value = wanted
        prefs?.edit()?.putInt(KEY_CROSSFADE_SECONDS, wanted)?.commit()
    }

    fun setLyricsSyncOffsetMs(offsetMs: Int) {
        val wanted = offsetMs.coerceIn(-2000, 2000)
        if (wanted == _lyricsSyncOffsetMs.value) return
        _lyricsSyncOffsetMs.value = wanted
        prefs?.edit()?.putInt(KEY_LYRICS_SYNC_OFFSET, wanted)?.commit()
    }

    fun load(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = store
        _reverb.value = store.getFloat(KEY_REVERB, 0f)
        _speed.value = store.getFloat(KEY_SPEED, 1f)
        _pitch.value = store.getFloat(KEY_PITCH, 1f)
        _karaoke.value = store.getFloat(KEY_KARAOKE, 0f)

        _bassBoost.value = store.getInt(KEY_BASS_BOOST, 0)
        _virtualizer.value = store.getInt(KEY_VIRTUALIZER, 0)
        _equalizerEnabled.value = store.getBoolean(KEY_EQ_ENABLED, false)
        _outputGainMb.value = store.getInt(KEY_OUTPUT_GAIN, 0)
        _audioOffload.value = store.getBoolean(KEY_AUDIO_OFFLOAD, true)
        _smartAutomix.value = store.getBoolean(KEY_SMART_AUTOMIX, true)
        _automixBassSwap.value = store.getBoolean(KEY_AUTOMIX_BASS_SWAP, true)
        _crossfadeSeconds.value = store.getInt(KEY_CROSSFADE_SECONDS, 6)
        _lyricsSyncOffsetMs.value = store.getInt(KEY_LYRICS_SYNC_OFFSET, 0)

        android.util.Log.i(
            "SquareAudio",
            "effects restored: reverb=${_reverb.value} speed=${_speed.value} pitch=${_pitch.value} offload=${_audioOffload.value} automix=${_smartAutomix.value}",
        )
    }

    fun rememberSpeedAndPitch(speed: Float, pitch: Float) {
        val store = prefs ?: return
        if (speed == _speed.value && pitch == _pitch.value) return
        _speed.value = speed
        _pitch.value = pitch
        store.edit()
            .putFloat(KEY_SPEED, speed)
            .putFloat(KEY_PITCH, pitch)
            .commit()
    }

    private const val FILE_NAME = "spot_audio_effects"
    private const val KEY_REVERB = "reverb"
    private const val KEY_SPEED = "speed"
    private const val KEY_PITCH = "pitch"
    private const val KEY_KARAOKE = "karaoke"

    private const val KEY_BASS_BOOST = "bass_boost"
    private const val KEY_VIRTUALIZER = "virtualizer"
    private const val KEY_EQ_ENABLED = "eq_enabled"
    private const val KEY_OUTPUT_GAIN = "output_gain_mb"
    private const val KEY_AUDIO_OFFLOAD = "audio_offload"
    private const val KEY_SMART_AUTOMIX = "smart_automix"
    private const val KEY_AUTOMIX_BASS_SWAP = "automix_bass_swap"
    private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
    private const val KEY_LYRICS_SYNC_OFFSET = "lyrics_sync_offset_ms"
}
