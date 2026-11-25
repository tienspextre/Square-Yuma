package dev.lelonio.square.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.MinusCircle
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.User
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.XCircle
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.DropTargetHandler
import dev.lelonio.square.desktop.Inspection
import dev.lelonio.square.desktop.LocalCatalog
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.ShortcutDispatch
import dev.lelonio.square.desktop.inspect
import dev.lelonio.square.desktop.str
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackRow(
    state: AppState,
    s: SubsonicChildSong,
    number: Int?,
    showArt: Boolean,
    playlistId: String? = null,
    onClick: () -> Unit,
) {
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var focused by remember { mutableStateOf(false) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    val isLocal = s.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)
    val scope = rememberCoroutineScope()

    fun removeFromPlaylist(pid: String) = scope.launch {
        runCatching {
            if (pid.startsWith(LocalCatalog.LOCAL_PLAYLIST_PREFIX)) {
                state.local.removeFromPlaylist(pid, s.id)
            } else {
                state.subsonicCatalog.removeFromPlaylist("subsonic:playlist:$pid", "subsonic:track:${s.id}")
            }
        }.fold(
            { state.say(str("desktop_removed_from_playlist")); state.generation++ },
            { state.say("${it.message}") },
        )
    }

    fun deleteFromNas() = scope.launch {
        state.client.bridgeRemove(s.id, null, true).fold(
            { state.say(str("delete_from_nas_done", s.title)); state.generation++ },
            { state.say("${it.message}") },
        )
    }

    Box {
        TrackRowBody(
            state, s, number, showArt, onClick,
            Modifier
                .onSizeChanged { rowSize = it }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .then(if (focused) Modifier.border(1.dp, Ink.copy(alpha = 0.28f), RoundedCornerShape(8.dp)) else Modifier)
                .onPointerEvent(PointerEventType.Press) { e ->
                    if (e.buttons.isSecondaryPressed) {
                        e.changes.first().position.let { menuAt = IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (ShortcutDispatch.isContextMenuShortcut(e)) {
                        menuAt = IntOffset((rowSize.width / 3).coerceAtLeast(16), (rowSize.height / 2).coerceAtLeast(16))
                        true
                    } else {
                        false
                    }
                },
        )
        GlassMenu(menuAt != null, menuAt ?: IntOffset.Zero, onDismiss = { menuAt = null }) {
            GlassMenuItem(str("play"), PhosphorIcons.Fill.Play) { menuAt = null; onClick() }
            GlassMenuItem(str("desktop_play_next"), PhosphorIcons.Regular.Queue) { state.player.playNext(s); menuAt = null }
            GlassMenuItem(str("add_to_queue"), PhosphorIcons.Regular.ListPlus) { state.player.addToQueue(s); menuAt = null }
            GlassMenuItem(str("add_to_playlist"), PhosphorIcons.Regular.Plus) { menuAt = null; pickingPlaylist = true }
            s.albumId?.let { id ->
                GlassMenuItem(str("go_to_album"), PhosphorIcons.Regular.Disc) {
                    menuAt = null
                    if (isLocal) state.open(Screen.LocalAlbum(id, s.album.orEmpty()))
                    else state.open(Screen.Album(id, s.album.orEmpty()))
                }
            }
            s.artistId?.let { id ->
                GlassMenuItem(str("go_to_artist"), PhosphorIcons.Regular.User) {
                    menuAt = null
                    if (isLocal) state.open(Screen.LocalArtist(id, s.artist.orEmpty()))
                    else state.open(Screen.Artist(id, s.artist.orEmpty()))
                }
            }
            if (!isLocal) {
                if (state.downloads.has(s.id)) {
                    GlassMenuItem(str("remove_download"), PhosphorIcons.Regular.XCircle) { menuAt = null; state.downloads.remove(s.id) }
                } else {
                    GlassMenuItem(str("download"), PhosphorIcons.Regular.DownloadSimple) { menuAt = null; state.download(listOf(s)) }
                }
            } else {
                val trackPath = s.path ?: state.local.findById(s.id)?.path
                if (!trackPath.isNullOrBlank()) {
                    GlassMenuItem(str("desktop_show_in_folder"), PhosphorIcons.Regular.FolderOpen) {
                        menuAt = null
                        DropTargetHandler.openInFolder(state, trackPath)
                    }
                }
            }
            GlassMenuItem(str("desktop_radio_from_song"), PhosphorIcons.Regular.Broadcast) { state.startRadio(s); menuAt = null }
            GlassMenuItem(str("inspect"), PhosphorIcons.Regular.Waveform) { menuAt = null; inspecting = true }
            if (!isLocal) {
                GlassMenuItem(str("copy_link"), PhosphorIcons.Regular.LinkSimple) {
                    menuAt = null
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(state.linkFor(s)), null)
                    state.say(str("desktop_link_copied"))
                }
            }
            playlistId?.let { pid ->
                GlassMenuItem(str("remove_from_playlist"), PhosphorIcons.Regular.MinusCircle) { menuAt = null; removeFromPlaylist(pid) }
            }
            if (!isLocal) {
                GlassMenuItem(str("delete_from_nas"), PhosphorIcons.Regular.Trash) { menuAt = null; confirmDelete = true }
            }
        }
        if (inspecting) InspectDialog(state, s) { inspecting = false }
        if (pickingPlaylist) {
            val targets by produceState<List<CatalogPlaylist>>(emptyList()) {
                value = if (isLocal) state.local.writablePlaylists() else state.subsonicCatalog.writablePlaylists()
            }
            AlertDialog(
                onDismissRequest = { pickingPlaylist = false },
                title = { Text(str("add_to_playlist")) },
                text = {
                    Column {
                        if (targets.isEmpty()) Text(str("desktop_no_playlists"), color = InkDim)
                        targets.forEach { pl ->
                            TextButton({
                                pickingPlaylist = false
                                scope.launch {
                                    runCatching {
                                        if (isLocal) state.local.addToPlaylist(pl.uri, s.id)
                                        else state.subsonicCatalog.addToPlaylist(pl.uri, "subsonic:track:${s.id}")
                                    }.onSuccess {
                                        state.generation++
                                        state.say(str("added_to", pl.name))
                                    }.onFailure { state.say("${it.message}") }
                                }
                            }) { Text(pl.name) }
                        }
                    }
                },
                confirmButton = { TextButton({ pickingPlaylist = false }) { Text(str("close")) } },
            )
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str("delete_from_nas_message", s.title)) },
            text = { Text(str("desktop_delete_nas_body")) },
            confirmButton = { TextButton({ confirmDelete = false; deleteFromNas() }) { Text(str("delete"), color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(str("cancel")) } },
        )
    }
}

@Composable
private fun InspectDialog(state: AppState, s: SubsonicChildSong, onDismiss: () -> Unit) {
    val result by produceState<Result<Inspection>?>(null, s.id) {
        value = runCatching {
            val src = if (s.id.startsWith(LocalCatalog.LOCAL_TRACK_PREFIX)) state.sourceFor(s)
            else state.downloads.fileFor(s.id)?.path ?: state.client.buildDownloadUrl(s.id)
            inspect(s, src)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val r = result
                when {
                    r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(str("desktop_reading_spectrum"))
                    }
                    r.isFailure -> Text("${str("inspect_failed")}: ${r.exceptionOrNull()?.message}")
                    else -> {
                        val i = r.getOrThrow()
                        Text(i.verdict, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        i.facts.forEach { (k, v) ->
                            Row {
                                Text(k, color = InkDim, modifier = Modifier.width(90.dp))
                                Text(v, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(str("close")) } },
    )
}

@Composable
private fun TrackRowBody(
    state: AppState,
    s: SubsonicChildSong,
    number: Int?,
    showArt: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val current by state.player.current.collectAsState()
    val isCurrent = current?.id == s.id
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Ink.copy(alpha = 0.07f) else Color.Transparent)
    val ink = Ink
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = if (showArt) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Artwork(state.cover(s.coverArt, 100), Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
        } else {
            Box(Modifier.width(32.dp)) {
                if (hovered) Icon(PhosphorIcons.Fill.Play, null, tint = ink, modifier = Modifier.size(16.dp))
                else Text(number?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) ink else InkDim)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
            if (showArt) Text(s.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showArt && !s.album.isNullOrBlank()) {
            Text(
                s.album.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.65f).padding(horizontal = 10.dp),
            )
        }
        state.downloads.progress[s.id]?.let {
            CircularProgressIndicator({ it }, Modifier.size(14.dp), color = Ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (state.downloads.has(s.id)) {
            Icon(PhosphorIcons.Fill.CheckCircle, str("downloaded"), tint = InkDim, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text("%d:%02d".format(s.duration / 60, s.duration % 60), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}
