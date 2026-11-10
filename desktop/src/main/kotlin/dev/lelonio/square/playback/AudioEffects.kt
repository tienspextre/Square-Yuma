package dev.lelonio.square.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The one AudioEffects setting the shared lyrics timing reads; the rest are Android-engine only. */
object AudioEffects {
    val lyricsSyncOffsetMs: StateFlow<Long> = MutableStateFlow(0L)
}
