package dev.lelonio.square.ui.glass

// The desktop's awaitFrame for the shared glass code (Android's comes from kotlinx-coroutines-android).
suspend fun awaitFrame() = androidx.compose.runtime.withFrameNanos { it }
