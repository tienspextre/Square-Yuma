package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.ArrowDown
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.Moon
import com.adamglin.phosphoricons.regular.Translate
import com.adamglin.phosphoricons.regular.Sparkle
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adamglin.phosphoricons.regular.Quotes
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.player.GlassProgressBar
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.player.LyricsView
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalInkOverride
import dev.lelonio.square.ui.theme.inkOn
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.softShadow

/**
 * The full player, as a sheet that rises out of the transport bar on the phone's
 * expand spring and can be dragged back down. Its own room behind it (the cover,
 * blurred and drifting), recorded so its controls are glass over that room: the
 * phone's 62/76 dp round LiquidButtons and its glass progress bar.
 */
@Composable
fun NowPlaying(state: AppState, onDrag: (Float) -> Unit, onRelease: (Float) -> Unit) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    val ground = pageColorFor(seed)
    val playerBackdrop = rememberLayerBackdrop()
    // Bumped by the source picker, so the same song is asked again.
    var lyricsAsk by remember { mutableStateOf(0) }
    val found by produceState<Pair<LyricSource, Lyrics>?>(null, song?.id, lyricsAsk) {
        value = null
        val s = song ?: return@produceState
        value = bestLyrics(state, s)
    }
    val lyrics by produceState<Lyrics?>(null, found, state.translateLyrics, state.translateTo) {
        val l = found?.second
        value = l
        if (l != null && state.translateLyrics) value = translated(l, state.translateTo)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
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
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}, // swallow clicks meant for the page below
            ) {
                Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
                    AmbientBackground(state.cover(song?.coverArt, 600), ground)
                }

                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(PhosphorIcons.Regular.CaretDown, "Close (Esc)", size = 24.dp) { state.nowPlaying = false }
                        Spacer(Modifier.weight(1f))
                        SleepTimer(state)
                        Glyph(PhosphorIcons.Regular.Translate, if (state.translateLyrics) "Hide translation" else "Translate lyrics", active = state.translateLyrics) {
                            state.useTranslation(!state.translateLyrics)
                        }
                        Glyph(PhosphorIcons.Regular.Quotes, "Lyrics", active = !state.showQueue) { state.showQueue = false }
                        Glyph(PhosphorIcons.Regular.Queue, "Queue", active = state.showQueue) { state.showQueue = true }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth > 900.dp
                        // Room left for the cover once the title and controls below it are laid out.
                        val cover = (maxHeight - 290.dp).coerceIn(160.dp, 560.dp)
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(if (wide) 0.9f else 1f).widthIn(max = cover + 60.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // The cover sits back a little while paused, as Apple Music's does.
                                val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.62f, stiffness = 260f))
                                Box(
                                    Modifier.size(cover)
                                        .onGloballyPositioned {
                                            // Where the cover will be once the sheet is up: the flight's destination.
                                            state.bigArt = it.boundsInRoot().translate(0f, -(1f - state.expand.value) * heightPx)
                                        }
                                        .graphicsLayer { alpha = if (state.expand.value < 0.999f) 0f else 1f }
                                        .scale(scale).softShadow(RoundedCornerShape(18.dp), 40.dp, spot = 0.45f),
                                ) {
                                    AnimatedContent(song?.coverArt, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }) { id ->
                                        Artwork(state.cover(id, 900), Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                                    }
                                }
                                Spacer(Modifier.height(26.dp))
                                Text(song?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(song?.artist, song?.album).joinToString(" — "),
                                    style = MaterialTheme.typography.titleMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(14.dp))
                                GlassProgressBar(
                                    positionMs = p.positionMs.collectAsState(), durationMs = (song?.duration ?: 0L) * 1000, onSeek = p::seek,
                                    accentColor = Ink, trackColor = Ink.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth(), backdrop = playerBackdrop,
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Glyph(PhosphorIcons.Regular.Shuffle, "Shuffle", active = shuffle, size = 22.dp) { p.toggleShuffle() }
                                    RoundGlassButton(playerBackdrop, 62, onClick = p::previous) { Icon(PhosphorIcons.Fill.SkipBack, "Previous", tint = Ink, modifier = Modifier.size(26.dp)) }
                                    RoundGlassButton(playerBackdrop, 76, onClick = p::toggle) {
                                        Icon(if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, if (playing) "Pause" else "Play", tint = Ink, modifier = Modifier.size(34.dp))
                                    }
                                    RoundGlassButton(playerBackdrop, 62, onClick = p::next) { Icon(PhosphorIcons.Fill.SkipForward, "Next", tint = Ink, modifier = Modifier.size(26.dp)) }
                                    Glyph(if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat, "Repeat", active = repeat != RepeatMode.OFF, size = 22.dp) { p.cycleRepeat() }
                                }
                                Spacer(Modifier.height(14.dp))
                                Volume(state, Modifier.width(260.dp), playerBackdrop)
                            }
                            if (wide) {
                                Box(Modifier.weight(1.1f).fillMaxHeight().padding(vertical = 12.dp)) {
                                    when {
                                        state.showQueue -> GlassSurface(playerBackdrop, RoundedCornerShape(26.dp), Modifier.fillMaxSize()) { Queue(state) }
                                        lyrics != null -> Column(Modifier.fillMaxSize()) {
                                            LyricsSourceRow(state, song, found?.first) { lyricsAsk++ }
                                            val raw = p.positionMs.collectAsState()
                                            val position = remember(raw) { derivedStateOf { raw.value - state.lyricsOffsetMs } }
                                            val seek = { ms: Long -> p.seek(ms + state.lyricsOffsetMs) }
                                            val show = state.translateLyrics
                                            if (state.classicLyrics) dev.lelonio.square.ui.player.ClassicLyricsView(lyrics!!, position, Modifier.fillMaxSize(), showTranslation = show, onSeek = seek)
                                            else LyricsView(lyrics!!, position, playing, Modifier.fillMaxSize(), showTranslation = show, sharpWhileBrowsingMs = 2500, onSeek = seek)
                                        }
                                        else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(if (found == null && song != null) "No lyrics for this song" else "", color = InkDim)
                                            LyricsSourceRow(state, song, null) { lyricsAsk++ }
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

/** The phone's RoundGlassButton: a LiquidButton exactly its size, with no padding to squeeze the icon. */
@Composable
private fun RoundGlassButton(backdrop: Backdrop, size: Int, onClick: () -> Unit = {}, content: @Composable () -> Unit) =
    LiquidButton(onClick, backdrop, Modifier.size(size.dp), contentHeight = size.dp, contentPadding = 0.dp) { content() }

@Composable
private fun Queue(state: AppState) {
    val queue by state.player.queue.collectAsState()
    val index by state.player.index.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(index.coerceAtLeast(0)) }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            SectionLabel("Up next", Modifier.weight(1f))
            if (queue.size > 1) Glyph(PhosphorIcons.Regular.Broom, "Clear the queue") { state.player.clearQueue() }
        }
        LazyColumn(state = list) {
            itemsIndexed(queue, key = { i, s -> "$i${s.id}" }) { i, s ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.hoverable(hover).background(if (i == index) Ink.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { TrackRow(state, s, null, true) { state.player.play(i) } }
                    // Up, down and out, shown on hover beside every song but the one playing.
                    if (hovered && i != index) {
                        if (i > 0) Glyph(PhosphorIcons.Regular.ArrowUp, "Move up") { state.player.move(i, i - 1) }
                        if (i < queue.size - 1) Glyph(PhosphorIcons.Regular.ArrowDown, "Move down") { state.player.move(i, i + 1) }
                        Glyph(PhosphorIcons.Regular.X, "Remove from queue") { state.player.removeAt(i) }
                    }
                }
            }
        }
    }
}

/** The phone's sleep timer: pause after a while, or at the end of the song. */
@Composable
private fun SleepTimer(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val endOfTrack by state.player.stopAfterCurrent.collectAsState()
    val endsAt = state.sleepEndsAt
    val left by produceState("", endsAt, endOfTrack) {
        while (true) {
            value = when {
                endOfTrack -> "End of song"
                endsAt != null -> ((endsAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1).let { "$it min" }
                else -> ""
            }
            if (endsAt == null) break
            kotlinx.coroutines.delay(10_000)
        }
    }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left.isNotEmpty()) Text(left, color = InkDim, style = MaterialTheme.typography.bodyMedium)
            Glyph(PhosphorIcons.Regular.Moon, "Sleep timer", active = left.isNotEmpty()) { open = true }
        }
        GlassMenu(open, androidx.compose.ui.unit.IntOffset(0, 40), onDismiss = { open = false }) {
            listOf(15, 30, 45, 60, 90).forEach { m -> GlassMenuItem("$m minutes", PhosphorIcons.Regular.Timer) { state.sleepIn(m); open = false } }
            GlassMenuItem("End of song", PhosphorIcons.Regular.Moon) { state.sleepAtEndOfTrack(); open = false }
            if (left.isNotEmpty()) GlassMenuItem("Turn off", PhosphorIcons.Regular.X) { state.sleepIn(null); open = false }
        }
    }
}

/** Where these words came from, and the phone's "More sources": try another, and keep that choice for this song. */
@Composable
private fun LyricsSourceRow(state: AppState, song: dev.lelonio.square.backend.subsonic.SubsonicChildSong?, from: LyricSource?, onPicked: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val s = song ?: return
    Box(Modifier.padding(bottom = 6.dp)) {
        Text(
            (if (from != null) "Lyrics from ${from.label}" else "Try another source") + "  ·  Change",
            color = InkDim, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        GlassMenu(open, androidx.compose.ui.unit.IntOffset(0, 28), onDismiss = { open = false }) {
            GlassMenuItem("Automatic", PhosphorIcons.Regular.Sparkle) { state.setLyricsPick(s.id, null); open = false; onPicked() }
            LyricSource.entries.forEach { src ->
                GlassMenuItem(src.label + if (src == from) "  ✓" else "", PhosphorIcons.Regular.Quotes) { state.setLyricsPick(s.id, src); open = false; onPicked() }
            }
        }
    }
}
