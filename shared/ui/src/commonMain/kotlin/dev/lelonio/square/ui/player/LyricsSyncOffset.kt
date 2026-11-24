package dev.lelonio.square.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The listener's lyrics sync offset (ms); the app installs its own setting, the default is none. */
object LyricsSyncOffset {
    @Volatile var source: StateFlow<Int> = MutableStateFlow(0)
}
