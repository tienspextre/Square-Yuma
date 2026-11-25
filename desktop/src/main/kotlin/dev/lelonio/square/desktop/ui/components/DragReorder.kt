package dev.lelonio.square.desktop.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/**
 * Lightweight mouse drag-and-drop reorder state for LazyColumn items on desktop.
 * Calls [onMove] live as the dragged row crosses adjacent row midpoints.
 */
class DragReorderState(
    private val itemCount: () -> Int,
    private val canDragIndex: (Int) -> Boolean,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingIndex by mutableIntStateOf(-1)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    private val heights = mutableStateMapOf<Int, Int>()

    fun recordHeight(index: Int, heightPx: Int) {
        if (heightPx > 0) heights[index] = heightPx
    }

    fun start(index: Int) {
        if (!canDragIndex(index)) return
        draggingIndex = index
        dragOffsetY = 0f
    }

    fun dragBy(deltaY: Float) {
        val current = draggingIndex
        if (current < 0) return
        dragOffsetY += deltaY
        val count = itemCount()
        if (dragOffsetY > 0f && current + 1 < count && canDragIndex(current + 1)) {
            val nextH = (heights[current + 1] ?: heights[current] ?: 48).toFloat()
            if (dragOffsetY > nextH * 0.55f) {
                onMove(current, current + 1)
                draggingIndex = current + 1
                dragOffsetY -= nextH
            }
        } else if (dragOffsetY < 0f && current - 1 >= 0 && canDragIndex(current - 1)) {
            val prevH = (heights[current - 1] ?: heights[current] ?: 48).toFloat()
            if (dragOffsetY < -prevH * 0.55f) {
                onMove(current, current - 1)
                draggingIndex = current - 1
                dragOffsetY += prevH
            }
        }
    }

    fun stop() {
        draggingIndex = -1
        dragOffsetY = 0f
    }
}

@Composable
fun rememberDragReorderState(
    itemCount: () -> Int,
    canDragIndex: (Int) -> Boolean = { true },
    onMove: (from: Int, to: Int) -> Unit,
): DragReorderState {
    val latestCount by rememberUpdatedState(itemCount)
    val latestCanDrag by rememberUpdatedState(canDragIndex)
    val latestOnMove by rememberUpdatedState(onMove)
    return remember {
        DragReorderState(
            itemCount = { latestCount() },
            canDragIndex = { latestCanDrag(it) },
            onMove = { from, to -> latestOnMove(from, to) },
        )
    }
}

fun Modifier.reorderableRow(state: DragReorderState, index: Int): Modifier {
    val isDragged = state.draggingIndex == index
    return this
        .onSizeChanged { state.recordHeight(index, it.height) }
        .zIndex(if (isDragged) 2f else 0f)
        .graphicsLayer {
            if (isDragged) {
                translationY = state.dragOffsetY
                scaleX = 1.015f
                scaleY = 1.015f
            }
        }
}

fun Modifier.dragReorderHandle(state: DragReorderState, index: Int): Modifier =
    this
        .pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(index) {
            detectDragGestures(
                onDragStart = { state.start(index) },
                onDragEnd = { state.stop() },
                onDragCancel = { state.stop() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.dragBy(dragAmount.y)
                },
            )
        }
