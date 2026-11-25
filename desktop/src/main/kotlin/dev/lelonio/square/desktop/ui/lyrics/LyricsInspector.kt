package dev.lelonio.square.desktop.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.Quotes
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Translate
import dev.lelonio.square.backend.subsonic.SubsonicChildSong
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.TranslationLanguages
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.ui.components.GlassMenu
import dev.lelonio.square.ui.components.GlassMenuItem
import dev.lelonio.square.ui.player.ClassicLyricsView
import dev.lelonio.square.ui.player.LyricsView
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

@Composable
fun LyricsInspectorContent(state: AppState, modifier: Modifier = Modifier) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    var lyricsAsk by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }

    val found by produceState<Pair<LyricSource, Lyrics>?>(null, song?.id, lyricsAsk) {
        value = null
        val s = song ?: return@produceState
        loading = true
        value = bestLyrics(state, s)
        loading = false
    }
    val lyrics by produceState<Lyrics?>(null, found, state.translateLyrics, state.translateTo) {
        val l = found?.second
        value = l
        if (l != null && state.translateLyrics) {
            value = translated(l, state.translateTo)
        }
    }

    Column(modifier.fillMaxSize()) {
        LyricsHeaderBar(state, song, found?.first) { lyricsAsk++ }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                song == null -> Text(str("desktop_not_playing"), color = InkDim, modifier = Modifier.align(Alignment.Center))
                loading && found == null -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), color = Ink)
                lyrics != null -> {
                    val raw = p.positionMs.collectAsState()
                    val position = remember(raw) { derivedStateOf { raw.value - state.lyricsOffsetMs } }
                    val seek = { ms: Long -> p.seek(ms + state.lyricsOffsetMs) }
                    val show = state.translateLyrics
                    if (state.classicLyrics) {
                        ClassicLyricsView(lyrics!!, position, Modifier.fillMaxSize(), showTranslation = show, onSeek = seek)
                    } else {
                        LyricsView(lyrics!!, position, playing, Modifier.fillMaxSize(), showTranslation = show, sharpWhileBrowsingMs = 2500, onSeek = seek)
                    }
                }
                else -> Text(str("no_lyrics"), color = InkDim, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun LyricsHeaderBar(
    state: AppState,
    song: SubsonicChildSong?,
    from: LyricSource?,
    onPicked: () -> Unit,
) {
    var langOpen by remember { mutableStateOf(false) }
    val targetLabel = remember(state.translateTo) {
        TranslationLanguages.firstOrNull { it.first == state.translateTo }?.second ?: state.translateTo
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) {
            LyricsSourceRow(state, song, from, onPicked)
        }
        if (state.translateLyrics) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ink.copy(alpha = 0.08f))
                        .clickable { langOpen = true }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(PhosphorIcons.Regular.Globe, null, tint = InkDim, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(targetLabel, style = MaterialTheme.typography.bodySmall, color = Ink)
                }
                GlassMenu(langOpen, IntOffset(0, 28), onDismiss = { langOpen = false }) {
                    TranslationLanguages.forEach { (code, label) ->
                        GlassMenuItem(
                            label + if (code == state.translateTo) "  ✓" else "",
                            PhosphorIcons.Regular.Globe,
                        ) {
                            state.chooseTranslateTarget(code)
                            langOpen = false
                        }
                    }
                }
            }
        }
        Glyph(
            PhosphorIcons.Regular.Translate,
            if (state.translateLyrics) "desktop_hide_translation" else "desktop_translate_lyrics",
            active = state.translateLyrics,
            size = 16.dp,
        ) { state.useTranslation(!state.translateLyrics) }
    }
}

@Composable
fun LyricsSourceRow(
    state: AppState,
    song: SubsonicChildSong?,
    from: LyricSource?,
    onPicked: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val s = song ?: return
    Box {
        Text(
            (if (from != null) str("lyrics_from", from.label) else str("desktop_try_another_source")) + "  ·  " + str("desktop_change"),
            color = InkDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        GlassMenu(open, IntOffset(0, 28), onDismiss = { open = false }) {
            GlassMenuItem(str("lyrics_preferred_auto"), PhosphorIcons.Regular.Sparkle) {
                state.setLyricsPick(s.id, null)
                open = false
                onPicked()
            }
            LyricSource.entries.forEach { src ->
                GlassMenuItem(src.label + if (src == from) "  ✓" else "", PhosphorIcons.Regular.Quotes) {
                    state.setLyricsPick(s.id, src)
                    open = false
                    onPicked()
                }
            }
        }
    }
}
