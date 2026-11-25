package dev.lelonio.square.desktop.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Quotes
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SpeakerX
import com.adamglin.phosphoricons.regular.Waveform
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.InspectorTab
import dev.lelonio.square.desktop.RepeatMode
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.PlayGlyph
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.InteractiveHighlight
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TransportBar(state: AppState, config: GlassEffectConfig, modifier: Modifier = Modifier) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val chain by p.signalChain.collectAsState()
    val scope = rememberCoroutineScope()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 1.02f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    val glow = remember(scope) { InteractiveHighlight(animationScope = scope) }
    val offsetX = remember { Animatable(0f) }
    val settle = remember { spring<Float>(stiffness = Spring.StiffnessMediumLow) }
    val gone = state.expand.value
    val pillShape = RoundedCornerShape(28.dp)

    Box(
        modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = 1f - gone * 0.6f }
            .liquidGlass(config, pillShape, highlightAlpha = 0.3f)
            .automixNeonBorder(pillShape, chain.mixing)
            .then(glow.modifier)
            .clipToBounds()
            .then(glow.gestureModifier)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, settle) } },
                    onHorizontalDrag = { _, d -> scope.launch { offsetX.snapTo(offsetX.value + d) } },
                    onDragEnd = {
                        if (abs(offsetX.value) > size.width * 0.14f) {
                            if (offsetX.value > 0) p.previous() else p.next()
                        }
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: cover + title + artist + Automix badge
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(press, indication = null, enabled = song != null) { state.nowPlaying = true }
                        .pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .onGloballyPositioned { state.miniArt = it.boundsInRoot() }
                            .graphicsLayer { alpha = if (gone > 0f) 0f else 1f },
                    ) {
                        Artwork(state.cover(song?.coverArt, 120), Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song?.title ?: str("desktop_not_playing"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song?.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (song != null) {
                        Spacer(Modifier.width(8.dp))
                        AutomixBadge(chain, state.smartAutomix) {
                            state.openInspector(InspectorTab.AUDIO)
                        }
                    }
                }

                // Center: transport controls with MixRing on Play
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Glyph(PhosphorIcons.Regular.Shuffle, "shuffle", active = shuffle, size = 16.dp) { p.toggleShuffle() }
                    Glyph(PhosphorIcons.Fill.SkipBack, "previous", size = 20.dp) { p.previous() }
                    Box(Modifier.mixRing(chain.mixing)) {
                        PlayGlyph(playing, 26.dp) { p.toggle() }
                    }
                    Glyph(PhosphorIcons.Fill.SkipForward, "next", size = 20.dp) { p.next() }
                    Glyph(
                        if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                        "repeat",
                        active = repeat != RepeatMode.OFF,
                        size = 16.dp,
                    ) { p.cycleRepeat() }
                }

                // Right: radio + inspector tabs + wheel volume
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (song != null) {
                        Glyph(PhosphorIcons.Regular.Broadcast, "desktop_radio_from_song", size = 16.dp) {
                            song?.let(state::startRadio)
                        }
                    }
                    Glyph(
                        PhosphorIcons.Regular.Quotes,
                        "desktop_lyrics_l",
                        active = state.inspectorOpen && state.inspectorTab == InspectorTab.LYRICS,
                        size = 16.dp,
                    ) { state.toggleInspector(InspectorTab.LYRICS) }
                    Glyph(
                        PhosphorIcons.Regular.Queue,
                        "desktop_queue_q",
                        active = state.inspectorOpen && state.inspectorTab == InspectorTab.QUEUE,
                        size = 16.dp,
                    ) { state.toggleInspector(InspectorTab.QUEUE) }
                    Glyph(
                        PhosphorIcons.Regular.Waveform,
                        "desktop_audio_tab",
                        active = state.inspectorOpen && state.inspectorTab == InspectorTab.AUDIO,
                        size = 16.dp,
                    ) { state.toggleInspector(InspectorTab.AUDIO) }
                    Spacer(Modifier.width(4.dp))
                    Volume(state, Modifier.width(104.dp))
                }
            }

            // Debounced LiquidSlider scrub bar that does not fight Automix or the 100 ms position poller
            TransportScrubSlider(state, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun TransportScrubSlider(state: AppState, modifier: Modifier = Modifier) {
    val song by state.player.current.collectAsState()
    val pos by state.player.positionMs.collectAsState()
    val dur = song?.let(state.local::durationMsOf) ?: 0L
    val backdrop = LocalAppBackdrop.current
    val scope = rememberCoroutineScope()

    var scrubbing by remember(song?.id) { mutableStateOf(false) }
    var scrubMs by remember(song?.id) { mutableStateOf(0L) }
    var pendingCommit by remember(song?.id) { mutableStateOf<Job?>(null) }

    val shownMs = if (scrubbing) scrubMs else pos
    val durationFloat = dur.coerceAtLeast(1L).toFloat()

    LaunchedEffect(song?.id) {
        scrubbing = false
        pendingCommit?.cancel()
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            formatClock(shownMs),
            style = MaterialTheme.typography.labelSmall,
            color = InkDim,
            modifier = Modifier.width(36.dp),
        )
        LiquidSlider(
            value = { shownMs.toFloat().coerceIn(0f, durationFloat) },
            onValueChange = { v ->
                if (dur <= 0L) return@LiquidSlider
                scrubbing = true
                scrubMs = v.toLong().coerceIn(0L, dur)
                // Debounced seek while dragging so rapid pointer motion does not hammer native deckSeek/Automix
                pendingCommit?.cancel()
                pendingCommit = scope.launch {
                    delay(140)
                    state.player.seek(scrubMs)
                }
            },
            onValueChangeFinished = {
                if (dur > 0L && scrubbing) {
                    pendingCommit?.cancel()
                    state.player.seek(scrubMs)
                }
                scrubbing = false
            },
            valueRange = 0f..durationFloat,
            visibilityThreshold = 100f,
            backdrop = backdrop,
            modifier = Modifier.weight(1f).height(20.dp),
            accentColor = Ink,
            trackColor = Ink.copy(alpha = 0.24f),
            thumbSize = DpSize(18.dp, 18.dp),
            thumbRestScale = 0.5f,
            thumbPressedScale = 0.95f,
        )
        Text(
            formatClock(dur),
            style = MaterialTheme.typography.labelSmall,
            color = InkDim,
            modifier = Modifier.width(36.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Volume(state: AppState, modifier: Modifier, backdrop: Backdrop = LocalAppBackdrop.current) {
    val v by state.player.volume.collectAsState()
    Row(
        modifier.onPointerEvent(PointerEventType.Scroll) { e ->
            val deltaY = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (deltaY != 0f) {
                state.setVolume((state.player.volume.value - deltaY * 0.05f).coerceIn(0f, 1f))
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (v == 0f) PhosphorIcons.Regular.SpeakerX else PhosphorIcons.Regular.SpeakerHigh,
            str("desktop_volume"),
            tint = InkDim,
            modifier = Modifier.size(16.dp).clickable {
                state.setVolume(if (v > 0f) 0f else 0.8f)
            }.pointerHoverIcon(PointerIcon.Hand),
        )
        LiquidSlider(
            value = { state.player.volume.value },
            onValueChange = state::setVolume,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            accentColor = Ink,
            trackColor = Ink.copy(alpha = 0.28f),
            thumbSize = DpSize(22.dp, 22.dp),
            thumbRestScale = 0.55f,
            thumbPressedScale = 0.95f,
        )
    }
}

private fun formatClock(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(sec / 60L, sec % 60L)
}
