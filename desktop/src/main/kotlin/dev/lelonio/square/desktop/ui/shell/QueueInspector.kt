package dev.lelonio.square.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.Infinity
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.DropZone
import dev.lelonio.square.desktop.RepeatMode
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.dragReorderHandle
import dev.lelonio.square.desktop.ui.components.rememberDragReorderState
import dev.lelonio.square.desktop.ui.components.reorderableRow
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

@Composable
fun QueueInspectorContent(state: AppState, modifier: Modifier = Modifier) {
    val p = state.player
    val queue by p.queue.collectAsState()
    val index by p.index.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val smartShuffle by p.smartShuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val current by p.current.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(index) {
        if (index in queue.indices) {
            listState.animateScrollToItem(index.coerceAtLeast(0))
        }
    }

    val reorderState = rememberDragReorderState(
        itemCount = { queue.size },
        canDragIndex = { it in queue.indices },
        onMove = { from, to -> p.move(from, to) },
    )

    DisposableEffect(Unit) {
        onDispose { state.queueDropBounds = null }
    }

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { state.queueDropBounds = it.boundsInWindow() }
            .then(
                if (state.dropZoneHighlight == DropZone.QUEUE_INSPECTOR) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        // Queue controls row: Smart Shuffle, Shuffle, Repeat, Radio from song, Autoplay at end, Clear
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Glyph(PhosphorIcons.Regular.Sparkle, "smart_shuffle", active = smartShuffle, size = 16.dp) {
                p.toggleSmartShuffle()
            }
            Glyph(PhosphorIcons.Regular.Shuffle, "shuffle", active = shuffle && !smartShuffle, size = 16.dp) {
                p.toggleShuffle()
            }
            Glyph(
                if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                "repeat",
                active = repeat != RepeatMode.OFF,
                size = 16.dp,
            ) { p.cycleRepeat() }
            Glyph(
                PhosphorIcons.Regular.Infinity,
                "desktop_autoplay_end",
                active = state.autoplayAtEnd,
                size = 16.dp,
            ) { state.useAutoplay(!state.autoplayAtEnd) }
            if (current != null) {
                Glyph(PhosphorIcons.Regular.Broadcast, "desktop_radio_from_song", size = 16.dp) {
                    current?.let(state::startRadio)
                }
            }
            Spacer(Modifier.weight(1f))
            if (queue.size > 1) {
                Glyph(PhosphorIcons.Regular.Broom, "queue_clear", size = 16.dp) { p.clearQueue() }
            }
        }

        // Autoplay & mode status pill
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("queue_next_up")
            if (state.autoplayAtEnd) {
                Text(str("autoplay"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(str("queue_empty"), color = InkDim, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(queue, key = { i, s -> "${s.id}#$i" }) { i, s ->
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    val isCurrent = i == index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .reorderableRow(reorderState, i)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    reorderState.draggingIndex == i -> Ink.copy(alpha = 0.16f)
                                    isCurrent -> Ink.copy(alpha = 0.12f)
                                    hovered -> Ink.copy(alpha = 0.06f)
                                    else -> Color.Transparent
                                },
                            )
                            .hoverable(hover)
                            .clickable { p.play(i) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PhosphorIcons.Regular.DotsSixVertical,
                            str("queue_reorder"),
                            tint = InkDim,
                            modifier = Modifier.dragReorderHandle(reorderState, i).padding(4.dp).size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Artwork(state.cover(s.coverArt, 100), Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                s.artist.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = InkDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (hovered && !isCurrent) {
                            Glyph(PhosphorIcons.Regular.X, "remove_from_queue", size = 14.dp) { p.removeAt(i) }
                        }
                    }
                }
            }
        }
    }
}
