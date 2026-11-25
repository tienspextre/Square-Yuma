package dev.lelonio.square.desktop.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CornersIn
import com.adamglin.phosphoricons.regular.CornersOut
import com.adamglin.phosphoricons.regular.Moon
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Quotes
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Translate
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.RepeatMode
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.AmbientBackground
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.rememberArtworkColor
import dev.lelonio.square.desktop.ui.lyrics.LyricsInspectorContent
import dev.lelonio.square.desktop.ui.shell.QueueInspectorContent
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.player.GlassProgressBar
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalInkOverride
import dev.lelonio.square.ui.theme.inkOn
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.softShadow
import kotlinx.coroutines.delay

val ExpandSpec = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
private const val FULLSCREEN_IDLE_HIDE_MS = 3_000L

private val BlankPointerIcon: PointerIcon by lazy {
    val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val cursor = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "blank")
    PointerIcon(cursor)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NowPlaying(state: AppState, onDrag: (Float) -> Unit, onRelease: (Float) -> Unit) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val chain by p.signalChain.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    val ground = pageColorFor(seed)
    val playerBackdrop = rememberLayerBackdrop()

    var activityTick by remember { mutableIntStateOf(0) }
    var idleHidden by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.fullscreen, activityTick) {
        if (!state.fullscreen) {
            idleHidden = false
            return@LaunchedEffect
        }
        idleHidden = false
        delay(FULLSCREEN_IDLE_HIDE_MS)
        idleHidden = true
    }

    val controlsAlpha by animateFloatAsState(if (idleHidden) 0f else 1f, tween(320))

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Move) { activityTick++; idleHidden = false }
            .onPointerEvent(PointerEventType.Press) { activityTick++; idleHidden = false }
            .onPointerEvent(PointerEventType.Scroll) { activityTick++; idleHidden = false }
            .pointerHoverIcon(if (idleHidden) BlankPointerIcon else PointerIcon.Default),
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val e = state.expand.value
        val drag = rememberDraggableState { onDrag(it / heightPx) }
        CompositionLocalProvider(LocalInkOverride provides inkOn(ground), LocalAppBackdrop provides playerBackdrop) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - e) * heightPx
                        shape = RoundedCornerShape(((1f - e).coerceIn(0f, 1f) * 40f).dp)
                        clip = true
                    }
                    .draggable(drag, Orientation.Vertical, onDragStopped = { v -> onRelease(v / heightPx) })
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
                    AmbientBackground(state.cover(song?.coverArt, 600), ground)
                }

                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp)) {
                    Row(
                        Modifier.alpha(controlsAlpha),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(PhosphorIcons.Regular.CaretDown, "desktop_close_esc", size = 24.dp) {
                            state.exitFullscreen()
                            state.nowPlaying = false
                        }
                        Spacer(Modifier.weight(1f))
                        AutomixBadge(chain, state.smartAutomix)
                        Spacer(Modifier.width(8.dp))
                        SleepTimer(state)
                        Glyph(
                            PhosphorIcons.Regular.Translate,
                            if (state.translateLyrics) "desktop_hide_translation" else "desktop_translate_lyrics",
                            active = state.translateLyrics,
                        ) { state.useTranslation(!state.translateLyrics) }
                        Glyph(PhosphorIcons.Regular.Quotes, "lyrics", active = !state.showQueue) { state.showQueue = false }
                        Glyph(PhosphorIcons.Regular.Queue, "queue", active = state.showQueue) { state.showQueue = true }
                        Glyph(
                            if (state.fullscreen) PhosphorIcons.Regular.CornersIn else PhosphorIcons.Regular.CornersOut,
                            if (state.fullscreen) "desktop_exit_fullscreen" else "desktop_fullscreen",
                            active = state.fullscreen,
                        ) { state.toggleFullscreen() }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth > 900.dp
                        val cover = (maxHeight - 290.dp).coerceIn(160.dp, 560.dp)
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(if (wide) 0.9f else 1f).widthIn(max = cover + 60.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.62f, stiffness = 260f))
                                Box(
                                    Modifier.size(cover)
                                        .onGloballyPositioned {
                                            state.bigArt = it.boundsInRoot().translate(0f, -(1f - state.expand.value) * heightPx)
                                        }
                                        .graphicsLayer { alpha = if (state.expand.value < 0.999f) 0f else 1f }
                                        .scale(scale).softShadow(RoundedCornerShape(18.dp), 40.dp, spot = 0.45f)
                                        .pointerInput(Unit) {
                                            detectTapGestures(onDoubleTap = { state.toggleFullscreen() })
                                        },
                                ) {
                                    AnimatedContent(song?.coverArt, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }) { id ->
                                        Artwork(state.cover(id, 900), Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                                    }
                                }
                                Spacer(Modifier.height(26.dp))
                                Text(song?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(song?.artist, song?.album).joinToString(" — "),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Column(
                                    Modifier.fillMaxWidth().alpha(controlsAlpha),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Spacer(Modifier.height(14.dp))
                                    val posState = p.positionMs.collectAsState()
                                    val durMs = song?.let(state.local::durationMsOf) ?: 0L
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(formatClockNp(scrubMs ?: posState.value), style = MaterialTheme.typography.labelSmall, color = InkDim, modifier = Modifier.width(38.dp))
                                        GlassProgressBar(
                                            positionMs = posState,
                                            durationMs = durMs,
                                            onSeek = p::seek,
                                            accentColor = Ink,
                                            trackColor = Ink.copy(alpha = 0.28f),
                                            modifier = Modifier.weight(1f),
                                            backdrop = playerBackdrop,
                                            onScrub = { scrubMs = it },
                                        )
                                        Text(formatClockNp(durMs), style = MaterialTheme.typography.labelSmall, color = InkDim, modifier = Modifier.width(38.dp))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        Glyph(PhosphorIcons.Regular.Shuffle, "shuffle", active = shuffle, size = 22.dp) { p.toggleShuffle() }
                                        RoundGlassButton(playerBackdrop, 62, onClick = p::previous) {
                                            Icon(PhosphorIcons.Fill.SkipBack, str("previous"), tint = Ink, modifier = Modifier.size(26.dp))
                                        }
                                        Box(Modifier.mixRing(chain.mixing)) {
                                            RoundGlassButton(playerBackdrop, 76, onClick = p::toggle) {
                                                Icon(
                                                    if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                                                    str(if (playing) "pause" else "play"),
                                                    tint = Ink,
                                                    modifier = Modifier.size(34.dp),
                                                )
                                            }
                                        }
                                        RoundGlassButton(playerBackdrop, 62, onClick = p::next) {
                                            Icon(PhosphorIcons.Fill.SkipForward, str("next"), tint = Ink, modifier = Modifier.size(26.dp))
                                        }
                                        Glyph(
                                            if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                                            "repeat",
                                            active = repeat != RepeatMode.OFF,
                                            size = 22.dp,
                                        ) { p.cycleRepeat() }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Volume(state, Modifier.width(260.dp), playerBackdrop)
                                }
                            }
                            if (wide) {
                                Box(Modifier.weight(1.1f).fillMaxHeight().padding(vertical = 12.dp)) {
                                    GlassSurface(playerBackdrop, RoundedCornerShape(26.dp), Modifier.fillMaxSize()) {
                                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                                            if (state.showQueue) QueueInspectorContent(state)
                                            else LyricsInspectorContent(state)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatClockNp(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(sec / 60L, sec % 60L)
}

@Composable
private fun RoundGlassButton(backdrop: Backdrop, size: Int, onClick: () -> Unit = {}, content: @Composable () -> Unit) =
    LiquidButton(onClick, backdrop, Modifier.size(size.dp), contentHeight = size.dp, contentPadding = 0.dp) { content() }

@Composable
private fun SleepTimer(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val endOfTrack by state.player.stopAfterCurrent.collectAsState()
    val endsAt = state.sleepEndsAt
    val left by produceState("", endsAt, endOfTrack) {
        while (true) {
            value = when {
                endOfTrack -> str("sleep_timer_track_end")
                endsAt != null -> ((endsAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1).let { str("sleep_timer_minutes", it.toInt()) }
                else -> ""
            }
            if (endsAt == null) break
            delay(10_000)
        }
    }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left.isNotEmpty()) Text(left, color = InkDim, style = MaterialTheme.typography.bodyMedium)
            Glyph(PhosphorIcons.Regular.Moon, "sleep_timer", active = left.isNotEmpty()) { open = true }
        }
        GlassMenu(open, IntOffset(0, 40), onDismiss = { open = false }) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                GlassMenuItem(str("sleep_timer_minutes", m), PhosphorIcons.Regular.Timer) { state.sleepIn(m); open = false }
            }
            GlassMenuItem(str("sleep_timer_track_end"), PhosphorIcons.Regular.Moon) { state.sleepAtEndOfTrack(); open = false }
            if (left.isNotEmpty()) GlassMenuItem(str("desktop_turn_off"), PhosphorIcons.Regular.X) { state.sleepIn(null); open = false }
        }
    }
}
