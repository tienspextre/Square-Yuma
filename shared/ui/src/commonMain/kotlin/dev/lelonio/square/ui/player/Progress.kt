package dev.lelonio.square.ui.player

/** Guarded against the duration being unknown while a track loads. */
fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
