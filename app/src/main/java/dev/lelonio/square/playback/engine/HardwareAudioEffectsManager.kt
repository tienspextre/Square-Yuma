package dev.lelonio.square.playback.engine

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import dev.lelonio.square.playback.AudioEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HardwareAudioEffectsManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var sessionId: Int = 0
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var hardwareEqualizer: Equalizer? = null
    private var observersJob: Job? = null

    fun attachSession(newSessionId: Int) {
        if (newSessionId <= 0 || newSessionId == sessionId) return
        detachSession()
        sessionId = newSessionId

        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                },
            )
        }

        runCatching {
            bassBoost = BassBoost(0, newSessionId).apply {
                enabled = AudioEffects.bassBoost.value > 0
                setStrength(AudioEffects.bassBoost.value.toShort())
            }
        }

        runCatching {
            virtualizer = Virtualizer(0, newSessionId).apply {
                enabled = AudioEffects.virtualizer.value > 0
                setStrength(AudioEffects.virtualizer.value.toShort())
            }
        }

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(newSessionId).apply {
                val gain = AudioEffects.outputGainMb.value
                enabled = gain != 0
                setTargetGain(gain)
            }
        }

        startObservers()
    }

    private fun startObservers() {
        observersJob?.cancel()
        val job = Job()
        observersJob = job
        val observerScope = CoroutineScope(scope.coroutineContext + job + Dispatchers.Main)

        AudioEffects.bassBoost.onEach { strength ->
            runCatching {
                bassBoost?.let {
                    it.enabled = strength > 0
                    it.setStrength(strength.toShort())
                }
            }
        }.launchIn(observerScope)

        AudioEffects.virtualizer.onEach { strength ->
            runCatching {
                virtualizer?.let {
                    it.enabled = strength > 0
                    it.setStrength(strength.toShort())
                }
            }
        }.launchIn(observerScope)

        AudioEffects.outputGainMb.onEach { gain ->
            runCatching {
                loudnessEnhancer?.let {
                    it.enabled = gain != 0
                    it.setTargetGain(gain)
                }
            }
        }.launchIn(observerScope)
    }

    fun detachSession() {
        observersJob?.cancel()
        observersJob = null
        if (sessionId > 0) {
            val oldId = sessionId
            sessionId = 0
            runCatching {
                context.sendBroadcast(
                    Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    },
                )
            }
        }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { hardwareEqualizer?.release() }
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        hardwareEqualizer = null
    }
}
