package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics

/** Lyric visual settings the player can override. */
@Immutable
data class LyricVisualSettings(
    /** Blur on the lines not being sung; 0 turns it off. */
    val inactiveBlur: Float = 6.0f,
)

val LocalLyricVisualSettings = compositionLocalOf { LyricVisualSettings() }

/**
 * The direction the song is written in, whatever the app is read in.
 *
 * The page used to take the app's: with the phone in Arabic, an English song
 * was laid out right to left, its sung line's words set down in reverse by
 * the row that places them and every question mark at the wrong end (issue
 * #92). The words decide instead, by counting the letters that have a
 * direction of their own; [fallback] for a sheet that has none, such as one
 * of only numbers.
 */
fun lyricsDirection(lines: List<LyricLine>, fallback: LayoutDirection): LayoutDirection {
    var ltr = 0
    var rtl = 0
    lines.forEach { line ->
        line.text.forEach { c ->
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
            }
        }
    }
    return when {
        ltr > rtl -> LayoutDirection.Ltr
        rtl > ltr -> LayoutDirection.Rtl
        else -> fallback
    }
}

/**
 * Synced lyrics, drawn the way YumaPlayer draws them: by Accompanist's
 * KaraokeLyricsView.
 *
 * What the hand-written view before it got wrong, and this does not:
 * - only one line could be lit, so a line still being sung went dark the
 *   moment the next began, and backing vocals were interleaved into the lead's
 *   words; here every line whose time covers the position is lit, and backing
 *   vocals are a line of their own under the lead's;
 * - the position was read in composition, so every visible line recomposed on
 *   every frame; here it is a lambda, read only where it is drawn.
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: State<Long>,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier,
    /** Whether to read each line in the listener's language underneath it. */
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    if (!lyrics.synced) {
        PlainLyrics(lyrics, modifier)
        return
    }
    val synced = remember(lyrics) { lyrics.toSyncedLyrics() }
    val wordTimed = remember(lyrics) { lyrics.lines.any { it.words.isNotEmpty() } }
    val position = rememberSmoothLyricsPosition(positionMs, isPlaying, isTtml = wordTimed)
    val currentPosition = remember(position) { { position.value.toInt() } }
    val blur = LocalLyricVisualSettings.current.inactiveBlur > 0f

    val base = MaterialTheme.typography.headlineMedium
    val normal = remember(base) {
        base.copy(
            fontSize = LYRICS_SP.sp,
            lineHeight = (LYRICS_SP * LINE_SPACING).sp,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        )
    }
    val accompaniment = remember(normal) {
        normal.copy(fontSize = (LYRICS_SP * 0.82f).sp, lineHeight = (LYRICS_SP * 0.82f * LINE_SPACING).sp)
    }

    key(lyrics) {
        val listState = rememberLazyListState()
        // Where the song was when the listener's finger took the list, until
        // the list (fling included) stops: a line turning over mid-drag made
        // the view scroll to it, which switched its springs back on under the
        // finger and sent every line drifting. YumaPlayer freezes it the same way.
        val held = remember { mutableIntStateOf(-1) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (!it) held.intValue = -1 }
        }
        val grab = remember(listState, currentPosition) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && held.intValue < 0) held.intValue = currentPosition()
                    return Offset.Zero
                }
            }
        }
        val shown = remember(listState, currentPosition) {
            { held.intValue.takeIf { it >= 0 && listState.isScrollInProgress } ?: currentPosition() }
        }
        BoxWithConstraints(modifier.fillMaxSize().nestedScroll(grab)) {
            KaraokeLyricsView(
                listState = listState,
                lyrics = synced,
                currentPosition = shown,
                onLineClicked = { line: ISyncedLine -> onSeek(line.start.toLong()) },
                onLinePressed = {},
                textColor = Color.White,
                normalLineTextStyle = normal,
                accompanimentLineTextStyle = accompaniment,
                blendMode = BlendMode.SrcOver,
                useBlurEffect = blur,
                showTranslation = showTranslation,
                showPhonetic = false,
                offset = maxHeight * 0.32f,
                keepAliveZone = 72.dp,
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            )
        }
    }
}

/** Words with no times: just the text, to scroll through. */
@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(lyrics.lines) { line ->
            Text(
                text = line.text,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * Square's lyrics as Accompanist's model, built the way YumaPlayer builds it.
 *
 * Word-timed lines become karaoke lines that run from their first word to
 * their last, with backing vocals split off into an accompaniment line;
 * line-timed ones last until the next line starts, at most four seconds when
 * a long gap follows. Blank lines (the gaps between verses) are dropped: the
 * view draws its breathing dots wherever nothing is being sung.
 */
internal fun Lyrics.toSyncedLyrics(): SyncedLyrics {
    val timed = lines.filter { it.startTimeMs != null }.sortedBy { it.startTimeMs }
    val out = mutableListOf<ISyncedLine>()
    timed.forEachIndexed { index, line ->
        if (line.text.isBlank() || line.text.trim() == "♪") return@forEachIndexed
        val start = line.startTimeMs!!.toInt()
        val alignment = if (line.secondVoice) KaraokeAlignment.End else KaraokeAlignment.Start
        val syllables = syllablesOf(line)
        val lead = syllables.filterNot { it.second }.map { it.first }
            .ifEmpty { syllables.map { it.first } }
        if (lead.isNotEmpty()) {
            val backing = syllables.filter { it.second }.map { it.first }
                .takeIf { it.isNotEmpty() && it.size < syllables.size }
            out += KaraokeLine.MainKaraokeLine(
                syllables = lead,
                translation = line.translation,
                alignment = alignment,
                start = lead.first().start,
                end = lead.maxOf { it.end },
                accompanimentLines = backing?.let {
                    listOf(
                        KaraokeLine.AccompanimentKaraokeLine(
                            syllables = it,
                            translation = null,
                            alignment = alignment,
                            start = it.first().start,
                            end = it.maxOf { s -> s.end },
                        ),
                    )
                },
            )
        } else {
            val next = timed.getOrNull(index + 1)?.startTimeMs?.toInt()
            val end = when {
                next == null || next <= start -> start + LONE_LINE_MS
                next - start > LONG_GAP_MS -> minOf(next - 1, start + LONE_LINE_MS)
                else -> next - 1
            }.coerceAtLeast(start + 1)
            out += SyncedLine(content = line.text, translation = line.translation, start = start, end = end)
        }
    }
    return SyncedLyrics(lines = out.sortedBy { it.start })
}

/**
 * Each timed word as a syllable carrying the whitespace that follows it in the
 * line's text (the view joins syllables as they are, so a word that ends a word
 * needs its space), paired with whether it is a backing vocal.
 */
private fun syllablesOf(line: LyricLine): List<Pair<KaraokeSyllable, Boolean>> {
    var at = 0
    return line.words.map { word ->
        val found = line.text.indexOf(word.text, at)
        val piece = if (found < 0) {
            "${word.text} "
        } else {
            var stop = found + word.text.length
            while (stop < line.text.length && line.text[stop].isWhitespace()) stop++
            at = stop
            line.text.substring(found, stop)
        }
        val start = word.startMs.toInt()
        KaraokeSyllable(piece, start, maxOf(start, word.endMs.toInt())) to word.background
    }
}

private const val LYRICS_SP = 26f
private const val LINE_SPACING = 1.3f
private const val LONE_LINE_MS = 4_000
private const val LONG_GAP_MS = 3_000
