package dev.lelonio.square.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.audiodsp.TrackAnalysisResult
import moe.rukamori.archivetune.audiodsp.TransitionTier

data class AutomixStatus(
    val isEnabled: Boolean = false,
    val isCrossfading: Boolean = false,
    val crossfadeProgress: Float = 0f,
    val currentTrackAnalysis: TrackAnalysisResult? = null,
    val nextTrackAnalysis: TrackAnalysisResult? = null,
    val nextTrackTitle: String? = null,
    val nextTrackArtist: String? = null,
    val nextTrackArtworkUrl: String? = null,
    val triggerAtMs: Long? = null,
    val durationMs: Long? = null,
    val tier: TransitionTier? = null,
    val isBassSwapped: Boolean = false,
    /** What is actually playing, e.g. "Hi-Res Lossless · 24/96" or "AAC · 256 kbps"; null when unknown. */
    val quality: String? = null,
)

object AutomixStateHolder {
    private val _state = MutableStateFlow(AutomixStatus())
    val state: StateFlow<AutomixStatus> = _state.asStateFlow()

    fun update(transform: (AutomixStatus) -> AutomixStatus) {
        _state.value = transform(_state.value)
    }

    fun setCrossfading(isCrossfading: Boolean, progress: Float = 0f) {
        _state.value = _state.value.copy(
            isCrossfading = isCrossfading,
            crossfadeProgress = progress,
        )
    }
}
