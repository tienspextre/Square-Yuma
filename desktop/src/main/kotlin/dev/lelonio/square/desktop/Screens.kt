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
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Shuffle
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import dev.lelonio.square.backend.subsonic.SubsonicMixes
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.SortAscending
import com.adamglin.phosphoricons.regular.SortDescending
import com.adamglin.phosphoricons.regular.SquaresFour
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.XCircle
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.regular.MinusCircle
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.User
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
            contentPadding = PaddingValues(Gutter, 28.dp, Gutter, BarClearance),
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
                    LiquidButton(onPlay, LocalAppBackdrop.current, Modifier.size(42.dp), contentHeight = 42.dp, contentPadding = 0.dp, flat = true) {
                        Icon(PhosphorIcons.Fill.Play, "Play", tint = Ink, modifier = Modifier.size(18.dp))
                    }
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
    val starred = load(state) { state.client.getAlbumList("starred", 24) }
    val quick = load(state) { state.client.getRandomSongs(24) }
    val mixes = load(state) { state.client.getGenres().map { SubsonicMixes.build(it) } }
    val playlists = load(state) { state.client.getPlaylists() }
    if (newest == null) { Loaded<Unit>(null) {}; return }
    run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = BarClearance)) {
                item { Title("Home", Modifier.padding(horizontal = Gutter).padding(bottom = 18.dp)) }
                if (newest.isFailure) item("offline") {
                    Text("Couldn't reach the server — showing what is on this computer.", color = InkDim, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
                }
                val kept = state.downloads.kept.map { it.song }.reversed()
                if (kept.isNotEmpty()) item("downloaded") {
                    Shelf("Downloaded") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(kept.take(30), key = { _, s -> "d" + s.id }) { i, s ->
                                Box(Modifier.width(160.dp)) { Card(s.title, s.artist, state.cover(s.coverArt), onPlay = { state.player.playQueue(kept, i) }) { state.player.playQueue(kept, i) } }
                            }
                            item { Box(Modifier.width(160.dp).aspectRatio(1f), contentAlignment = Alignment.Center) { TextButton({ state.open(Screen.Downloads) }) { Text("See all") } } }
                        }
                    }
                }
                quick?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { songs ->
                    item("quick") {
                        Shelf("Quick play") {
                            LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                itemsIndexed(songs, key = { _, s -> "q" + s.id }) { i, s ->
                                    Box(Modifier.width(160.dp)) {
                                        Card(s.title, s.artist, state.cover(s.coverArt), onPlay = { state.player.playQueue(songs, i) }) { state.player.playQueue(songs, i) }
                                    }
                                }
                            }
                        }
                    }
                }
                mixes?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                    item("mixes") {
                        Shelf("Moods & genres") {
                            LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(list, key = { it.key }) { m -> MixTile(m) { state.open(Screen.Mix(m.key, m.title)) } }
                            }
                        }
                    }
                }
                playlists?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                    item("playlists") {
                        Shelf("Playlists on NAS") {
                            LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                items(list, key = { "p" + it.id }) { p ->
                                    Box(Modifier.width(184.dp)) { Card(p.name, "${p.songCount} songs", state.cover(p.coverArt)) { state.open(Screen.Playlist(p.id, p.name)) } }
                                }
                            }
                        }
                    }
                }
                listOf("Recently played" to recent, "Recently added" to newest, "Most played" to frequent, "Discover" to random, "Favorites" to starred)
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

/** A mood or genre: a tile in a colour of its own, as on the phone's Moods & Genres shelf. */
@Composable
private fun MixTile(m: SubsonicMixes.Mix, onClick: () -> Unit) {
    val hue = ((m.title.hashCode() % 360) + 360) % 360
    val colour = Color.hsv(hue.toFloat(), 0.55f, 0.62f)
    Column(
        Modifier.width(184.dp).height(96.dp).clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colour, colour.copy(alpha = 0.55f))))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(14.dp),
    ) {
        Text(m.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(if (m.kind == "MOOD") "Mood · ${m.songCount} songs" else "${m.songCount} songs", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
fun MixScreen(state: AppState, screen: Screen.Mix) {
    val songs = load(state, screen) {
        state.client.getGenres().mapCatching { genres ->
            val mix = SubsonicMixes.find(SubsonicMixes.build(genres), screen.key) ?: error("No such mix")
            mix.rawGenres.flatMap { state.client.getSongsByGenre(it, 500).getOrDefault(emptyList()) }.distinctBy { it.id }
        }
    }
    Loaded(songs) { list ->
        Tracks(state, list, screen.name, subtitle = "${list.size} songs", cover = list.firstOrNull()?.coverArt, showAlbum = true)
    }
}

@Composable
fun DownloadsScreen(state: AppState) {
    val songs = state.downloads.kept.map { it.song }.reversed()
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing downloaded yet. Right-click a song, or use Download on an album or playlist.", color = InkDim)
        }
    } else Tracks(state, songs, "Downloaded", "${songs.size} songs · %.1f GB".format(state.downloads.bytes() / 1e9), songs.first().coverArt, showAlbum = true)
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
            LayoutToggle(state)
            Spacer(Modifier.width(12.dp))
            Segmented(listOf("Albums", "Artists", "Downloaded"), if (artists) 1 else 0) { if (it == 2) state.open(Screen.Downloads) else artists = it == 1 }
        }
        failed?.let { Text("Couldn't reach the server: $it", color = InkDim) }
    }) {
        if (artists) {
            items(artistList?.getOrNull().orEmpty(), key = { "ar" + it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) {
                if (state.listMode) ListEntry(state.cover(it.coverArt, 100), it.name, "${it.albumCount} albums", round = true) { state.open(Screen.Artist(it.id, it.name)) }
                else ArtistCard(state, it)
            }
        } else {
            items(albums, key = { "al" + it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) {
                if (state.listMode) ListEntry(state.cover(it.coverArt, 100), it.name, listOfNotNull(it.artist, it.year?.toString()).joinToString(" · ")) { state.open(Screen.Album(it.id, it.name)) }
                else AlbumCard(state, it)
            }
            if (more) item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(albums.size) { page() }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
    }
}

/** The phone's grid/list switch. */
@Composable
private fun LayoutToggle(state: AppState) =
    Glyph(if (state.listMode) PhosphorIcons.Regular.SquaresFour else PhosphorIcons.Regular.ListBullets, if (state.listMode) "Show as grid" else "Show as list", size = 20.dp) { state.toggleListMode() }

/** One row of the list layout: a small cover, the name, a line under it. */
@Composable
private fun ListEntry(cover: String?, title: String, subtitle: String, round: Boolean = false, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Ink.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(cover, Modifier.size(48.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
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
    Loaded(result) { all ->
        val pinned = state.pins.map { it.id }.toSet()
        val list = all.sortedByDescending { it.id in pinned }
        Grid(header = { Row(verticalAlignment = Alignment.Bottom) { Title("Playlists", Modifier.weight(1f)); LayoutToggle(state) } }) {
            items(list, key = { it.id }, span = { if (state.listMode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { p ->
                val pin = p.id in pinned
                val open = { state.open(Screen.Playlist(p.id, p.name)) }
                if (state.listMode) ListEntry(state.cover(p.coverArt, 100), p.name, "${p.songCount} songs" + if (pin) " · Pinned" else "", trailing = {
                    Glyph(PhosphorIcons.Regular.PushPin, if (pin) "Unpin" else "Pin", active = pin) { state.togglePin(p.id, p.name, p.coverArt) }
                }, onClick = open)
                else Card(p.name, "${p.songCount} songs" + if (pin) " · Pinned" else "", state.cover(p.coverArt), onClick = open)
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
        Tracks(state, list, screen.name, subtitle = "${list.size} songs", cover = list.firstOrNull()?.coverArt, showAlbum = true, playlistId = screen.id)
    }
}

/** An album or playlist page: the cover and title beside Play/Shuffle, then the songs. */
@Composable
private fun Tracks(state: AppState, all: List<SubsonicChildSong>, title: String, subtitle: String, cover: String?, showAlbum: Boolean, playlistId: String? = null) {
    var find by remember(all) { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) }
    var descending by remember { mutableStateOf(false) }
    val songs = remember(all, find, sort, descending) {
        val q = find.trim().lowercase()
        val hits = if (q.isEmpty()) all else all.filter { s -> listOfNotNull(s.title, s.artist, s.album).any { q in it.lowercase() } }
        val sorted = when (SORTS[sort]) {
            "Title" -> hits.sortedBy { it.title.lowercase() }
            "Artist" -> hits.sortedBy { it.artist.orEmpty().lowercase() }
            "Album" -> hits.sortedBy { it.album.orEmpty().lowercase() }
            "Added" -> hits.sortedByDescending { it.created.orEmpty() }
            "Length" -> hits.sortedBy { it.duration }
            else -> hits
        }
        if (descending) sorted.reversed() else sorted
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MaxContent).fillMaxSize(), contentPadding = PaddingValues(Gutter, 20.dp, Gutter, BarClearance)) {
            item {
                BackButton(state)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 26.dp)) {
                    Artwork(state.cover(cover, 600), Modifier.size(240.dp).softShadow(RoundedCornerShape(14.dp), 24.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(28.dp))
                    Column {
                        Title(title)
                        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = InkDim, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill(PhosphorIcons.Fill.Play, "Play", accent = true) { state.player.playQueue(songs) }
                            Pill(PhosphorIcons.Regular.Shuffle, "Shuffle") { state.player.playQueue(songs.shuffled()) }
                            val missing = all.count { !state.downloads.has(it.id) }
                            if (missing > 0) Pill(PhosphorIcons.Regular.DownloadSimple, "Download") { state.download(all) }
                            if (playlistId != null) {
                                val pinned = state.pins.any { it.id == playlistId }
                                Pill(PhosphorIcons.Regular.PushPin, if (pinned) "Unpin" else "Pin") { state.togglePin(playlistId, title, cover) }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    OutlinedTextField(
                        find, { find = it }, Modifier.width(260.dp).onFocusChanged { state.typing = it.isFocused },
                        placeholder = { Text("Find in ${if (showAlbum) "list" else "album"}") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Segmented(SORTS, sort) { sort = it }
                    Glyph(if (descending) PhosphorIcons.Regular.SortDescending else PhosphorIcons.Regular.SortAscending, if (descending) "Descending" else "Ascending", size = 20.dp) { descending = !descending }
                }
            }
            itemsIndexed(songs, key = { i, s -> "$i${s.id}" }) { i, s ->
                TrackRow(state, s, if (showAlbum) null else (s.track ?: (i + 1)), showAlbum, playlistId) { state.player.playQueue(songs, i) }
            }
        }
    }
}

private val SORTS = listOf("Order", "Title", "Artist", "Album", "Added", "Length")

/** The phone's glass chip: a LiquidButton, lit with the accent when it is the main action. */
@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    // The phone's Play: a solid pill of the ink, its label in the page's colour.
    val ink = if (accent) (if (dev.lelonio.square.ui.theme.LocalLightTheme.current) Color.White else Color.Black) else Ink
    LiquidButton(
        onClick, LocalAppBackdrop.current, flat = true, contentHeight = 40.dp, contentPadding = 20.dp,
        surfaceColor = if (accent) Ink else Color.Unspecified,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = ink)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackRow(state: AppState, s: SubsonicChildSong, number: Int?, showArt: Boolean, playlistId: String? = null, onClick: () -> Unit) {
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun remove(fromPlaylist: String?, deleteFile: Boolean, done: String) = scope.launch {
        state.client.bridgeRemove(s.id, fromPlaylist, deleteFile).fold(
            { state.say(done); state.generation++ },
            { state.say("Couldn't: ${it.message}") },
        )
    }
    Box {
        TrackRowBody(state, s, number, showArt, onClick, Modifier.onPointerEvent(PointerEventType.Press) { e ->
            if (e.buttons.isSecondaryPressed) e.changes.first().position.let { menuAt = IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
        })
        GlassMenu(menuAt != null, menuAt ?: IntOffset.Zero, onDismiss = { menuAt = null }) {
            GlassMenuItem("Play next", PhosphorIcons.Regular.Queue) { state.player.playNext(s); menuAt = null }
            GlassMenuItem("Add to queue", PhosphorIcons.Regular.ListPlus) { state.player.addToQueue(s); menuAt = null }
            s.albumId?.let { id -> GlassMenuItem("Go to album", PhosphorIcons.Regular.Disc) { menuAt = null; state.open(Screen.Album(id, s.album.orEmpty())) } }
            s.artistId?.let { id -> GlassMenuItem("Go to artist", PhosphorIcons.Regular.User) { menuAt = null; state.open(Screen.Artist(id, s.artist.orEmpty())) } }
            if (state.downloads.has(s.id)) GlassMenuItem("Remove download", PhosphorIcons.Regular.XCircle) { menuAt = null; state.downloads.remove(s.id) }
            else GlassMenuItem("Download", PhosphorIcons.Regular.DownloadSimple) { menuAt = null; state.download(listOf(s)) }
            GlassMenuItem("Inspect", PhosphorIcons.Regular.Waveform) { menuAt = null; inspecting = true }
            GlassMenuItem("Copy link", PhosphorIcons.Regular.LinkSimple) {
                menuAt = null
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(state.linkFor(s)), null)
                state.say("Link copied")
            }
            playlistId?.let { pid -> GlassMenuItem("Remove from playlist", PhosphorIcons.Regular.MinusCircle) { menuAt = null; remove(pid, false, "Removed from playlist") } }
            GlassMenuItem("Delete from NAS", PhosphorIcons.Regular.Trash) { menuAt = null; confirmDelete = true }
        }
        if (inspecting) InspectDialog(state, s) { inspecting = false }
        if (confirmDelete) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${s.title}” from the NAS?") },
            text = { Text("The file is deleted from the server and taken out of every playlist. This can't be undone.") },
            confirmButton = { TextButton({ confirmDelete = false; remove(null, true, "Deleted “${s.title}”") }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InspectDialog(state: AppState, s: SubsonicChildSong, onDismiss: () -> Unit) {
    val result by produceState<Result<Inspection>?>(null, s.id) {
        value = runCatching { inspect(s, state.downloads.fileFor(s.id)?.path ?: state.client.buildDownloadUrl(s.id)) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val r = result
                when {
                    r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Reading the file and its spectrum…")
                    }
                    r.isFailure -> Text("Couldn't inspect: ${r.exceptionOrNull()?.message}")
                    else -> {
                        val i = r.getOrThrow()
                        Text(i.verdict, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        i.facts.forEach { (k, v) ->
                            Row { Text(k, color = InkDim, modifier = Modifier.width(90.dp)); Text(v, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TrackRowBody(state: AppState, s: SubsonicChildSong, number: Int?, showArt: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val current by state.player.current.collectAsState()
    val isCurrent = current?.id == s.id
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Ink.copy(alpha = 0.07f) else Color.Transparent)
    val ink = Ink
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = if (showArt) 6.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Artwork(state.cover(s.coverArt, 100), Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(14.dp))
        } else {
            Box(Modifier.width(34.dp)) {
                if (hovered) Icon(PhosphorIcons.Fill.Play, null, tint = ink, modifier = Modifier.size(16.dp))
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
        state.downloads.progress[s.id]?.let { CircularProgressIndicator({ it }, Modifier.size(14.dp), color = Ink, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
        if (state.downloads.has(s.id)) { Icon(PhosphorIcons.Fill.CheckCircle, "Downloaded", tint = InkDim, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(10.dp)) }
        Text("%d:%02d".format(s.duration / 60, s.duration % 60), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}

@Composable
private fun BackButton(state: AppState) {
    if (state.stack.size > 1) {
        Box(Modifier.padding(bottom = 10.dp)) { Glyph(PhosphorIcons.Regular.CaretLeft, "Back", size = 22.dp) { state.back() } }
    }
}

@Composable
fun SearchScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    val result by produceState<Result<dev.lelonio.square.backend.subsonic.SubsonicSearchResult3>?>(null, query, state.generation) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(280) // typing pause
        value = state.client.search(query)
        if (value?.getOrNull()?.let { it.song.isNotEmpty() || it.album.isNotEmpty() || it.artist.isNotEmpty() } == true) {
            delay(1500) // only what the listener settled on, not every prefix typed on the way
            state.remember(query)
        }
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
            if (query.isBlank() && state.searchHistory.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                    SectionLabel("Recent searches", Modifier.weight(1f))
                    TextButton({ state.clearSearches() }) { Text("Clear") }
                }
                state.searchHistory.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { query = q }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PhosphorIcons.Regular.ClockCounterClockwise, null, tint = InkDim, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(q, color = Ink, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
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
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(Gutter, 28.dp, Gutter, BarClearance), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Title("Settings", Modifier.padding(bottom = 10.dp)) }
            item { SectionLabel("Navidrome / Subsonic server") }
            item { OutlinedTextField(url, { url = it }, field, label = { Text("Server URL") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(user, { user = it }, field, label = { Text("Username") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(pass, { pass = it }, field, label = { Text("Password") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium, visualTransformation = PasswordVisualTransformation()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(PhosphorIcons.Fill.Play, "Connect", accent = true) {
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
            item {
                TextButton(onClick = { scope.launch { status = if (state.client.startScan().getOrDefault(false)) "Library rescan started" else "Rescan failed" } }) {
                    Text("Rescan server library")
                }
            }
            item { SectionLabel("Downloads", Modifier.padding(top = 18.dp)) }
            item {
                Column {
                    Text("${state.downloads.kept.size} songs · %.2f GB in ${state.downloads.root.path}".format(state.downloads.bytes() / 1e9), color = InkDim, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton({ runCatching { state.downloads.root.mkdirs(); java.awt.Desktop.getDesktop().open(state.downloads.root) } }) { Text("Open folder") }
                        if (state.downloads.kept.isNotEmpty()) TextButton({ state.downloads.removeAll(); state.say("Removed all downloads") }) { Text("Remove all downloads") }
                    }
                }
            }
            item { SectionLabel("Appearance", Modifier.padding(top = 18.dp)) }
            item { Segmented(listOf("Dark", "Light"), state.themeMode.ordinal) { state.setTheme(ThemeMode.entries[it]) } }
            item { SectionLabel("Glass", Modifier.padding(top = 18.dp)) }
            item { Segmented(GlassProfile.entries.map { it.name }, state.glassProfile.ordinal) { state.setGlass(GlassProfile.entries[it]) } }
            item { SectionLabel("Renderer (applies after a restart)", Modifier.padding(top = 18.dp)) }
            item { Segmented(Renderer.entries.map { it.label }, state.renderer.ordinal) { state.chooseRenderer(Renderer.entries[it]) } }
            item { SectionLabel("Crossfade", Modifier.padding(top = 18.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A plain slider, as in the phone's settings: glass inside the page would sample the layer it is drawn into.
                    Slider(
                        crossfade, { crossfade = it; state.setCrossfade(it.roundToInt()) }, Modifier.weight(1f), valueRange = 0f..12f, steps = 11,
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
                    )
                    Text(if (crossfade < 0.5f) "Off" else "${crossfade.roundToInt()} s", color = Ink, modifier = Modifier.width(60.dp).padding(start = 12.dp))
                }
            }
            item { SectionLabel("Equalizer", Modifier.padding(top = 18.dp)) }
            item {
                val gains = state.eqGains
                val preset = Eq.PRESETS.indexOfFirst { it.second.contentEquals(gains) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eq.PRESETS.chunked(4).forEach { row ->
                        Segmented(row.map { it.first }, row.indexOfFirst { Eq.PRESETS.indexOf(it) == preset }) { i -> state.setEq(row[i].second.copyOf()) }
                    }
                    Eq.BANDS.forEachIndexed { b, hz ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Eq.label(hz), color = InkDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
                            Slider(
                                gains[b], { v -> state.setEq(gains.copyOf().also { it[b] = (v * 2).roundToInt() / 2f }, restart = false) }, Modifier.weight(1f),
                                valueRange = -12f..12f, onValueChangeFinished = { state.player.reapply() },
                                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
                            )
                            Text("%+.1f dB".format(gains[b]), color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp).padding(start = 10.dp))
                        }
                    }
                }
            }
            item { SectionLabel("Lyrics timing", Modifier.padding(top = 18.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        state.lyricsOffsetMs / 1000f, { state.setLyricsOffset(((it * 4).roundToInt() * 250).toLong()) }, Modifier.weight(1f), valueRange = -3f..3f,
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
                    )
                    Text("%+.2f s".format(state.lyricsOffsetMs / 1000f), color = Ink, modifier = Modifier.width(80.dp).padding(start = 12.dp))
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
