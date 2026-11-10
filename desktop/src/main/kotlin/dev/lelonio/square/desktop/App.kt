package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.pageGround
import dev.lelonio.square.ui.theme.softShadow

@Composable
fun App(state: AppState) {
    val song by state.player.current.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    SquareTheme(seed = seed, darkTheme = state.themeMode == ThemeMode.DARK) {
        // The page is the colour of the record that is playing, as on the phone.
        val top by animateColorAsState(pageColorFor(seed), tween(600))
        val ground = pageGround()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, ground, ground)))) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    Sidebar(state)
                    Content(state, Modifier.weight(1f).fillMaxHeight())
                }
                TransportBar(state)
            }
            AnimatedVisibility(
                state.nowPlaying,
                enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it } + fadeIn(),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
            ) { NowPlaying(state) }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    val root = state.stack.first()
    Column(
        Modifier.width(230.dp).fillMaxHeight().background(Ink.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Text(
            "Square-Yuma",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            modifier = Modifier.padding(start = 10.dp, bottom = 22.dp),
        )
        NavItem(Icons.Rounded.Search, "Search", root == Screen.Search) { state.root(Screen.Search) }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Music", Modifier.padding(start = 10.dp, bottom = 6.dp))
        NavItem(Icons.Rounded.Home, "Home", root == Screen.Home) { state.root(Screen.Home) }
        NavItem(Icons.Rounded.LibraryMusic, "Library", root == Screen.Library) { state.root(Screen.Library) }
        NavItem(Icons.Rounded.QueueMusic, "Playlists", root == Screen.Playlists) { state.root(Screen.Playlists) }
        Spacer(Modifier.weight(1f))
        NavItem(Icons.Rounded.Settings, "Settings", root == Screen.Settings) { state.root(Screen.Settings) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> Ink.copy(alpha = 0.12f)
            hovered -> Ink.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)
            .hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else InkDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

@Composable
private fun Content(state: AppState, modifier: Modifier) {
    AnimatedContent(
        targetState = state.stack.last(),
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith fadeOut(tween(160))
        },
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(state)
            Screen.Library -> LibraryScreen(state)
            Screen.Playlists -> PlaylistsScreen(state)
            Screen.Search -> SearchScreen(state)
            Screen.Settings -> SettingsScreen(state)
            is Screen.Album -> AlbumScreen(state, screen)
            is Screen.Artist -> ArtistScreen(state, screen)
            is Screen.Playlist -> PlaylistScreen(state, screen)
        }
    }
}

@Composable
private fun TransportBar(state: AppState) {
    val p = state.player
    val song by p.current.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = song != null) { state.nowPlaying = true }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.cover(song?.coverArt, 120), Modifier.size(54.dp).softShadow(RoundedCornerShape(8.dp), 10.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "Not playing", style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1.3f).widthIn(max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Controls(state, big = false)
            ProgressBar(state, Modifier.fillMaxWidth())
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.Lyrics, "Lyrics (L)", active = state.nowPlaying) { state.nowPlaying = !state.nowPlaying }
            Spacer(Modifier.width(8.dp))
            VolumeBar(state, Modifier.width(130.dp))
        }
    }
}

@Composable
fun Controls(state: AppState, big: Boolean) {
    val p = state.player
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val s = if (big) 1.5f else 1f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
        IconBtn(Icons.Rounded.Shuffle, "Shuffle", active = shuffle, size = 20.dp * s) { p.toggleShuffle() }
        IconBtn(Icons.Rounded.SkipPrevious, "Previous", size = 26.dp * s) { p.previous() }
        PlayButton(playing, (40 * s).dp) { p.toggle() }
        IconBtn(Icons.Rounded.SkipNext, "Next", size = 26.dp * s) { p.next() }
        IconBtn(
            if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat", active = repeat != RepeatMode.OFF, size = 20.dp * s,
        ) { p.cycleRepeat() }
    }
}

@Composable
fun PlayButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.08f else 1f, spring(stiffness = 600f))
    Box(
        Modifier.size(size).scale(scale).softShadow(CircleShape, 12.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary).hoverable(hover).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
fun IconBtn(icon: ImageVector, label: String, active: Boolean = false, size: Dp = 22.dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val tint by animateColorAsState(
        when {
            active -> MaterialTheme.colorScheme.primary
            hovered -> Ink
            else -> Ink.copy(alpha = 0.78f)
        },
    )
    Box(
        Modifier.clip(CircleShape).background(if (hovered) Ink.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size))
    }
}

/** A thin bar that thickens under the pointer, Apple Music style; click or drag to seek. */
@Composable
fun ProgressBar(state: AppState, modifier: Modifier, ink: Color = Ink) {
    val p = state.player
    val pos by p.positionMs.collectAsState()
    val song by p.current.collectAsState()
    val dur = (song?.duration ?: 0L) * 1000
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TimeText(if (drag != null) (drag!! * dur).toLong() else pos, ink)
        Slim(fraction, Modifier.weight(1f).padding(horizontal = 10.dp), ink,
            onDrag = { drag = it }, onDone = { f -> drag = null; p.seek((f * dur).toLong()) })
        TimeText(dur, ink)
    }
}

@Composable
private fun TimeText(ms: Long, ink: Color) {
    val s = ms / 1000
    Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.66f), modifier = Modifier.width(40.dp))
}

@Composable
fun VolumeBar(state: AppState, modifier: Modifier, ink: Color = Ink) {
    val v by state.player.volume.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (v == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Volume", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Slim(v, Modifier.weight(1f).padding(start = 8.dp), ink, onDrag = state::setVolume, onDone = state::setVolume)
    }
}

@Composable
private fun Slim(fraction: Float, modifier: Modifier, ink: Color, onDrag: (Float) -> Unit, onDone: (Float) -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val thick by animateDpAsState(if (hovered || dragging) 7.dp else 4.dp)
    var last by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier.height(18.dp).hoverable(hover).pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { onDone((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true; last = (it.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                    onDragEnd = { dragging = false; onDone(last) },
                    onDragCancel = { dragging = false; onDone(last) },
                ) { change, _ -> last = (change.position.x / size.width).coerceIn(0f, 1f); onDrag(last) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thick).clip(CircleShape).background(ink.copy(alpha = 0.18f)))
        Box(Modifier.width(maxWidth * fraction).height(thick).clip(CircleShape).background(ink.copy(alpha = if (hovered) 0.95f else 0.75f)))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = modifier)
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = Ink, fontWeight = FontWeight.Bold, modifier = modifier)
}
