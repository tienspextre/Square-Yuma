package dev.lelonio.square.ui.player

// ponytail: MiniPlayer.kt's one shared helper; the rest of that file is Android's.
fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
