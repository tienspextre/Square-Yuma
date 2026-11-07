package dev.lelonio.square.ui.glass

import androidx.compose.runtime.withFrameNanos

/** Suspends until the next frame is drawn. */
suspend fun awaitFrame(): Long = withFrameNanos { it }
