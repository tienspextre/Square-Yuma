package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicArtistItem
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Content never stretches past this on a wide monitor, as in Apple Music. */
private val MaxContent = 1480.dp
private val Gutter = 32.dp

/** A result, a failure or still loading — keyed on the server generation so Settings → Connect reloads it. */
@Composable
private fun <T> load(state: AppState, vararg keys: Any?, block: suspend () -> Result<T>): Result<T>? =
    produceState<Result<T>?>(null, state.generation, *keys) { value = block() }.value

@Composable
private fun <T> Loaded(result: Result<T>?, content: @Composable (T) -> Unit) {
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        result.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't reach the server: ${result.exceptionOrNull()?.message}", color = InkDim)
        }
        else -> content(result.getOrThrow())
    }
}

/** An adaptive artwork grid, centred and capped at [MaxContent]. */
@Composable
private fun Grid(header: @Composable () -> Unit, items: LazyGridScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Adaptive(176.dp),
            Modifier.widthIn(max = MaxContent).fillMaxSize(),
            contentPadding = PaddingValues(Gutter, 28.dp, Gutter, 40.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items()
        }
    }
}

@Composable
fun Card(title: String, subtitle: String?, cover: String?, round: Boolean = false, onPlay: (() -> Unit)? = null, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.03f else 1f, spring(stiffness = 500f))
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    Column(Modifier.hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).scale(scale).softShadow(shape, if (hovered) 22.dp else 14.dp)) {
            Artwork(cover, Modifier.fillMaxSize().clip(shape))
            if (onPlay != null) {
                androidx.compose.animation.AnimatedVisibility(hovered, Modifier.align(Alignment.BottomEnd).padding(10.dp), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    PlayButton(false, 40.dp, onPlay)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumCard(state: AppState, a: SubsonicAlbumEntry) {
    val scope = rememberCoroutineScope()
    Card(
        a.name, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), state.cover(a.coverArt),
        onPlay = { scope.launch { state.client.getAlbum(a.id).onSuccess { state.player.playQueue(it) } } },
    ) { state.open(Screen.Album(a.id, a.name)) }
}

@Composable
private fun ArtistCard(state: AppState, a: SubsonicArtistItem) =
    Card(a.name, "${a.albumCount} albums", state.cover(a.coverArt), round = true) { state.open(Screen.Artist(a.id, a.name)) }

@Composable
private fun Shelf(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 30.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Ink, modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp))
        content()
    }
}

@Composable
fun HomeScreen(state: AppState) {
    val newest = load(state) { state.client.getAlbumList("newest", 24) }
    val recent = load(state) { state.client.getAlbumList("recent", 24) }
    val frequent = load(state) { state.client.getAlbumList("frequent", 24) }
    val random = load(state) { state.client.getAlbumList("random", 24) }
    Loaded(newest) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
                item { Title("Home", Modifier.padding(horizontal = Gutter).padding(bottom = 18.dp)) }
                listOf("Recently played" to recent, "Recently added" to newest, "Most played" to frequent, "Discover" to random)
                    .forEach { (title, r) ->
                        val albums = r?.getOrNull().orEmpty()
                        if (albums.isNotEmpty()) item(title) {
                            Shelf(title) {
                                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    items(albums, key = { it.id }) { a -> Box(Modifier.width(184.dp)) { AlbumCard(state, a) } }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: AppState) {
    var artists by remember { mutableStateOf(false) }
    val albums = remember(state.generation) { mutableStateListOf<SubsonicAlbumEntry>() }
    var more by remember(state.generation) { mutableStateOf(true) }
    var failed by remember(state.generation) { mutableStateOf<String?>(null) }
    val artistList = load(state, artists) { if (artists) state.client.getArtists() else Result.success(emptyList()) }
    // Albums arrive a page at a time, the next one asked for when the grid reaches the end.
    suspend fun page() {
        state.client.getAlbumList("alphabeticalByName", 120, albums.size)
            .onSuccess { albums.addAll(it); more = it.size == 120 }
            .onFailure { failed = it.message; more = false }
    }
    LaunchedEffect(state.generation) { if (albums.isEmpty()) page() }
    Grid(header = {
        Row(verticalAlignment = Alignment.Bottom) {
            Title("Library", Modifier.weight(1f))
            Segmented(listOf("Albums", "Artists"), if (artists) 1 else 0) { artists = it == 1 }
        }
        failed?.let { Text("Couldn't reach the server: $it", color = InkDim) }
    }) {
        if (artists) {
            items(artistList?.getOrNull().orEmpty(), key = { "ar" + it.id }) { ArtistCard(state, it) }
        } else {
            items(albums, key = { "al" + it.id }) { AlbumCard(state, it) }
            if (more) item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(albums.size) { page() }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Ink.copy(alpha = 0.08f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            Text(
                label, style = MaterialTheme.typography.titleMedium, color = if (i == selected) Ink else InkDim,
                modifier = Modifier.clip(CircleShape).background(bg).clickable { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun PlaylistsScreen(state: AppState) {
    val result = load(state) { state.client.getPlaylists() }
    Loaded(result) { list ->
        Grid(header = { Title("Playlists") }) {
            items(list, key = { it.id }) { p ->
                Card(p.name, "${p.songCount} songs", state.cover(p.coverArt)) { state.open(Screen.Playlist(p.id, p.name)) }
            }
        }
    }
}

@Composable
fun ArtistScreen(state: AppState, screen: Screen.Artist) {
    val result = load(state, screen) { state.client.getArtist(screen.id) }
    Loaded(result) { artist ->
        Grid(header = {
            Column {
                BackButton(state)
                Title(artist.name)
            }
        }) {
            items(artist.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { AlbumCard(state, it) }
        }
    }
}

@Composable
fun AlbumScreen(state: AppState, screen: Screen.Album) {
    val songs = load(state, screen) { state.client.getAlbum(screen.id) }
    Loaded(songs) { list ->
        val first = list.firstOrNull()
        Tracks(
            state, list, screen.name,
            subtitle = listOfNotNull(first?.artist, first?.year?.toString(), first?.genre).joinToString(" · "),
            cover = first?.coverArt, showAlbum = false,
        )
    }
}

@Composable
fun PlaylistScreen(state: AppState, screen: Screen.Playlist) {
    val songs = load(state, screen) { state.client.getPlaylist(screen.id) }
    Loaded(songs) { list ->
        Tracks(state, list, screen.name, subtitle = "${list.size} songs", cover = list.firstOrNull()?.coverArt, showAlbum = true)
    }
}

/** An album or playlist page: the cover and title beside Play/Shuffle, then the songs. */
@Composable
private fun Tracks(state: AppState, songs: List<SubsonicChildSong>, title: String, subtitle: String, cover: String?, showAlbum: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(Gutter, 20.dp, Gutter, 40.dp)) {
            item {
                BackButton(state)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 26.dp)) {
                    Artwork(state.cover(cover, 600), Modifier.size(240.dp).softShadow(RoundedCornerShape(14.dp), 24.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(28.dp))
                    Column {
                        Title(title)
                        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill(Icons.Rounded.PlayArrow, "Play") { state.player.playQueue(songs) }
                            Pill(Icons.Rounded.Shuffle, "Shuffle") { state.player.playQueue(songs.shuffled()) }
                        }
                    }
                }
            }
            itemsIndexed(songs, key = { i, s -> "$i${s.id}" }) { i, s ->
                TrackRow(state, s, if (showAlbum) null else (s.track ?: (i + 1)), showAlbum) { state.player.playQueue(songs, i) }
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (hovered) 1f else 0.9f))
            .hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun TrackRow(state: AppState, s: SubsonicChildSong, number: Int?, showArt: Boolean, onClick: () -> Unit) {
    val current by state.player.current.collectAsState()
    val isCurrent = current?.id == s.id
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Ink.copy(alpha = 0.07f) else Color.Transparent)
    val ink = if (isCurrent) MaterialTheme.colorScheme.primary else Ink
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = if (showArt) 6.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Artwork(state.cover(s.coverArt, 100), Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(14.dp))
        } else {
            Box(Modifier.width(34.dp)) {
                if (hovered) Icon(Icons.Rounded.PlayArrow, null, tint = ink, modifier = Modifier.size(18.dp))
                else Text(number?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) ink else InkDim)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null)
            if (showArt) Text(s.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showArt) Text(s.album.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.7f).padding(horizontal = 12.dp))
        Text("%d:%02d".format(s.duration / 60, s.duration % 60), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}

@Composable
private fun BackButton(state: AppState) {
    if (state.stack.size > 1) {
        Box(Modifier.padding(bottom = 10.dp)) { IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { state.back() } }
    }
}

@Composable
fun SearchScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    val result by produceState<Result<dev.lelonio.square.backend.subsonic.SubsonicSearchResult3>?>(null, query, state.generation) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(280) // typing pause
        value = state.client.search(query)
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val r = result?.getOrNull()
    Grid(header = {
        Column {
            Title("Search", Modifier.padding(bottom = 16.dp))
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { state.typing = it.isFocused },
                placeholder = { Text("Artists, albums, songs") },
                textStyle = MaterialTheme.typography.titleMedium,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardActions = KeyboardActions.Default,
            )
            if (r != null && r.song.isNotEmpty()) {
                SectionLabel("Songs", Modifier.padding(top = 24.dp, bottom = 8.dp))
                r.song.take(8).forEachIndexed { i, s -> TrackRow(state, s, null, true) { state.player.playQueue(r.song, i) } }
            }
            if (r != null && r.artist.isNotEmpty()) {
                SectionLabel("Artists", Modifier.padding(top = 24.dp, bottom = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(r.artist, key = { it.id }) { a -> Box(Modifier.width(150.dp)) { ArtistCard(state, a) } }
                }
            }
            if (r != null && r.album.isNotEmpty()) SectionLabel("Albums", Modifier.padding(top = 24.dp))
            if (result?.isFailure == true) Text("Couldn't reach the server", color = InkDim, modifier = Modifier.padding(top = 16.dp))
        }
    }) {
        items(r?.album.orEmpty(), key = { it.id }) { AlbumCard(state, it) }
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val prefs = state.prefs
    var url by remember { mutableStateOf(prefs.serverUrl.value.ifBlank { "http://" }) }
    var user by remember { mutableStateOf(prefs.username.value) }
    var pass by remember { mutableStateOf(prefs.password.value) }
    var status by remember { mutableStateOf<String?>(null) }
    var crossfade by remember { mutableStateOf(state.player.crossfadeSeconds.toFloat()) }
    val scope = rememberCoroutineScope()
    val field = Modifier.fillMaxWidth().onFocusChanged { state.typing = it.isFocused }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(Gutter, 28.dp, Gutter, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Title("Settings", Modifier.padding(bottom = 10.dp)) }
            item { SectionLabel("Navidrome / Subsonic server") }
            item { OutlinedTextField(url, { url = it }, field, label = { Text("Server URL") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(user, { user = it }, field, label = { Text("Username") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(pass, { pass = it }, field, label = { Text("Password") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium, visualTransformation = PasswordVisualTransformation()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(Icons.Rounded.PlayArrow, "Connect") {
                        prefs.save(url, user, pass)
                        status = "Connecting…"
                        scope.launch {
                            state.client.ping().fold(
                                { status = "Connected · ${it.type} ${it.serverVersion}"; state.generation++ },
                                { status = "Failed: ${it.message}" },
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    status?.let { Text(it, color = InkDim, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item { SectionLabel("Appearance", Modifier.padding(top = 18.dp)) }
            item { Segmented(listOf("Dark", "Light"), state.themeMode.ordinal) { state.setTheme(ThemeMode.entries[it]) } }
            item { SectionLabel("Crossfade", Modifier.padding(top = 18.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(crossfade, { crossfade = it; state.setCrossfade(it.toInt()) }, Modifier.weight(1f), valueRange = 0f..12f, steps = 11)
                    Text(if (crossfade < 1f) "Off" else "${crossfade.toInt()} s", color = Ink, modifier = Modifier.width(60.dp).padding(start = 12.dp))
                }
            }
            item {
                Text(
                    "Keys: Space play/pause · ←/→ seek 10 s · Ctrl+←/→ previous/next · ↑/↓ volume · L lyrics · Ctrl+F search · Esc back",
                    color = InkDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 18.dp),
                )
            }
            item { TextButton(onClick = { state.root(Screen.Home) }) { Text("Done") } }
        }
    }
}
