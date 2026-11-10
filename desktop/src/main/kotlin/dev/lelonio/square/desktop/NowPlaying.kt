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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.player.LyricsView
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalInkOverride
import dev.lelonio.square.ui.theme.inkOn
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.softShadow

/** The full player: the cover large on the left, the Android app's lyrics view (or the queue) on the right. */
@Composable
fun NowPlaying(state: AppState) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    val ground = pageColorFor(seed)
    var showQueue by remember { mutableStateOf(false) }
    val lyrics by produceState<Lyrics?>(null, song?.id) {
        value = null
        val s = song ?: return@produceState
        // The server's own lyrics first, then LrcLib, as the phone does.
        value = state.client.getLyrics(s.id).getOrNull().orEmpty()
            .takeIf { it.isNotEmpty() }
            ?.let { lines ->
                val synced = lines.any { it.start > 0 }
                Lyrics(lines.map { LyricLine(it.start.takeIf { synced }, it.value) }, synced = synced, source = "Navidrome")
            }
            ?: dev.lelonio.square.backend.lyrics.LrcLib.lyrics(s.title, s.artist.orEmpty(), s.duration * 1000)
    }

    // The player owns its colour, as on the phone: the ink is whatever reads on it.
    CompositionLocalProvider(LocalInkOverride provides inkOn(ground)) {
        Box(
            Modifier.fillMaxSize().background(ground)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}, // swallow clicks meant for the page below
        ) {
            Artwork(state.cover(song?.coverArt, 600), Modifier.fillMaxSize().scale(1.4f).blur(110.dp))
            Box(Modifier.fillMaxSize().background(ground.copy(alpha = 0.55f)))

            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Rounded.KeyboardArrowDown, "Close (Esc)", size = 30.dp) { state.nowPlaying = false }
                    Spacer(Modifier.weight(1f))
                    IconBtn(Icons.Rounded.Lyrics, "Lyrics", active = !showQueue) { showQueue = false }
                    IconBtn(Icons.Rounded.QueueMusic, "Queue", active = showQueue) { showQueue = true }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth > 900.dp
                    // Room left for the cover once the title and controls below it are laid out.
                    val cover = (maxHeight - 250.dp).coerceIn(160.dp, 560.dp)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(if (wide) 0.9f else 1f).widthIn(max = cover),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // The cover sits back a little while paused, as Apple Music's does.
                            val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.62f, stiffness = 260f))
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).scale(scale).softShadow(RoundedCornerShape(18.dp), 40.dp, spot = 0.45f)) {
                                AnimatedContent(song?.coverArt, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }) { id ->
                                    Artwork(state.cover(id, 900), Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                                }
                            }
                            Spacer(Modifier.height(28.dp))
                            Text(song?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(song?.artist, song?.album).joinToString(" — "),
                                style = MaterialTheme.typography.titleMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(16.dp))
                            ProgressBar(state, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Controls(state, big = true)
                            Spacer(Modifier.height(14.dp))
                            VolumeBar(state, Modifier.fillMaxWidth(0.6f))
                        }
                        if (wide) {
                            Box(Modifier.weight(1.1f).fillMaxHeight()) {
                                when {
                                    showQueue -> Queue(state)
                                    lyrics != null -> {
                                        val position = p.positionMs.collectAsState()
                                        LyricsView(lyrics!!, position, playing, Modifier.fillMaxSize(), onSeek = p::seek)
                                    }
                                    else -> Text("No lyrics for this song", color = InkDim, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Queue(state: AppState) {
    val queue by state.player.queue.collectAsState()
    val index by state.player.index.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(index.coerceAtLeast(0)) }
    Column {
        SectionLabel("Up next", Modifier.padding(bottom = 10.dp))
        LazyColumn(state = list) {
            itemsIndexed(queue, key = { i, s -> "$i${s.id}" }) { i, s ->
                Box(Modifier.background(if (i == index) Ink.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp))) {
                    TrackRow(state, s, null, true) { state.player.play(i) }
                }
            }
        }
    }
}
