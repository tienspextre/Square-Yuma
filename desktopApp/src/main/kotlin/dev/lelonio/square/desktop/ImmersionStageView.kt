package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowsInSimple
import com.adamglin.phosphoricons.bold.Repeat
import com.adamglin.phosphoricons.bold.RepeatOnce
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.bold.Sliders
import com.adamglin.phosphoricons.bold.Waveform
import com.adamglin.phosphoricons.fill.MicrophoneStage
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.fill.SpeakerHigh
import com.adamglin.phosphoricons.fill.SpeakerSlash
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.playback.DesktopRepeatMode
import dev.lelonio.square.playback.AudioDspState
import dev.lelonio.square.playback.AutomixUiState
import dev.lelonio.square.playback.DesktopDspPreset
import dev.lelonio.square.ui.components.DesktopArtworkImage
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.LiquidToggle
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.glass.shapes.ContinuousRoundedRectangle
import dev.lelonio.square.ui.player.DesktopInspectView
import dev.lelonio.square.ui.player.LyricsView
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import moe.rukamori.archivetune.audiodsp.incomingStageGain
import moe.rukamori.archivetune.audiodsp.outgoingMidDuckGain
import moe.rukamori.archivetune.audiodsp.outgoingStageGain

/**
 * Apple Music on iPadOS / Cider 2 Full-Window Immersion Stage:
 * Left 44%: Large bass-reactive album artwork, quality badge, Automix pill, LiquidSlider scrubber & transport.
 * Right 56%: Full-height stage switched via a floating Liquid Glass segmented pill:
 *   1. Karaoke Lyrics (Word-by-word TTML / LRC synced)
 *   2. Automix Queue & Live Dual-Deck Crossfade Curve
 *   3. Audio DSP Studio (Speed, Pitch, Reverb, Bass Boost, Vocal Cancellation)
 *   4. Lossless Spectrogram & FLAC Header Inspector
 */
@Composable
fun ImmersionStageView(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onPaletteLoaded: (Color, Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrack by controller.player.currentTrack.collectAsState()
    val isPlaying by controller.player.isPlaying.collectAsState()
    val positionMs by controller.player.positionMs.collectAsState()
    val durationMs by controller.player.durationMs.collectAsState()
    val volume by controller.player.masterVolume.collectAsState()
    val automix by controller.player.automixState.collectAsState()
    val dspState by controller.player.dspState.collectAsState()
    val spectrum by controller.player.liveSpectrum.collectAsState()
    val bassPulse by controller.player.bassPulse.collectAsState()
    val activeRightTab by controller.immersionTab.collectAsState()
    val lyrics by controller.currentLyrics.collectAsState()
    val isLyricsLoading by controller.isLyricsLoading.collectAsState()
    val queue by controller.player.queue.collectAsState()
    val currentIndex by controller.player.currentIndex.collectAsState()

    val positionState = rememberUpdatedState(positionMs)

    val pulseReactive by controller.subsonicPrefs.pulseReactiveBackground.collectAsState()
    val shuffleEnabled by controller.player.shuffleEnabled.collectAsState()
    val repeatMode by controller.player.repeatMode.collectAsState()

    val artScale by animateFloatAsState(
        targetValue = if (isPlaying && pulseReactive) 1.0f + (bassPulse * 0.035f) else 1.0f,
        animationSpec = tween(180),
        label = "artScale",
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LEFT COLUMN: Apple Music iPadOS Artwork & Transport Column
        Column(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar: Return to 3-Pane Studio button + Pulse Glow Toggle + Automix live pill
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(
                    onClick = { controller.toggleImmersionMode() },
                    backdrop = backdrop,
                    surfaceColor = Color.White.copy(alpha = 0.08f),
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Bold.ArrowsInSimple,
                        contentDescription = "Back to Studio",
                        tint = Ink,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Studio View", style = MaterialTheme.typography.bodySmall, color = Ink)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Quick Optional Toggle: Nhấp nháy nền theo nhạc
                    val pulseShape = ContinuousCapsule()
                    Row(
                        modifier = Modifier
                            .clip(pulseShape)
                            .background(
                                if (pulseReactive) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                1.dp,
                                if (pulseReactive) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                else Color.White.copy(alpha = 0.12f),
                                pulseShape,
                            )
                            .clickable { controller.subsonicPrefs.setPulseReactiveBackground(!pulseReactive) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.Bold.Waveform,
                            contentDescription = null,
                            tint = if (pulseReactive) MaterialTheme.colorScheme.primary else InkDim,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = if (pulseReactive) "Pulse: BẬT" else "Pulse: TẮT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (pulseReactive) Ink else InkDim,
                        )
                    }

                    AutomixStatusPill(
                        automix = automix,
                        backdrop = backdrop,
                        glassConfig = glassConfig,
                        onClick = { controller.setImmersionTab(ImmersionRightTab.QUEUE) },
                    )
                }
            }

            // Large Floating Album Art with Optional Bass-Reactive Rim
            val coverShape = ContinuousRoundedRectangle(26.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.80f)
                    .aspectRatio(1f)
                    .scale(artScale)
                    .softShadow(coverShape, elevation = 28.dp, ambient = 0.22f, spot = 0.32f)
                    .clip(coverShape)
                    .border(
                        1.dp,
                        Color.White.copy(alpha = if (pulseReactive) 0.22f + bassPulse * 0.25f else 0.22f),
                        coverShape,
                    ),
            ) {
                DesktopArtworkImage(
                    artworkUrl = currentTrack?.artworkUrl,
                    titleFallback = currentTrack?.name ?: "Square-Yuma",
                    modifier = Modifier.fillMaxSize(),
                    onPaletteLoaded = onPaletteLoaded,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Track Metadata + Hi-Res Quality Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentTrack?.name ?: "Select a track to begin listening",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(currentTrack?.artist ?: "Square × Yuma Desktop")
                        val alb = currentTrack?.album.orEmpty()
                        if (alb.isNotBlank()) append(" — ").append(alb)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                // Clickable Lossless Quality Badge
                val badgeShape = ContinuousCapsule()
                Row(
                    modifier = Modifier
                        .liquidGlass(glassConfig, badgeShape, ownBackdrop = backdrop)
                        .clip(badgeShape)
                        .clickable { controller.setImmersionTab(ImmersionRightTab.INSPECT) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Bold.Waveform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = currentTrack?.qualityBadge ?: "FLAC 24-bit / 48 kHz",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                        color = Ink,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Scrubber LiquidSlider
            val safeDur = durationMs.coerceAtLeast(1L).toFloat()
            val curFrac = (positionMs.toFloat() / safeDur).coerceIn(0f, 1f)
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val displayFrac = scrubFraction ?: curFrac
            val displayMs = scrubFraction?.let { (it * safeDur).toLong() } ?: positionMs
            Column(modifier = Modifier.fillMaxWidth(0.92f)) {
                LiquidSlider(
                    value = { displayFrac },
                    onValueChange = { frac ->
                        scrubFraction = frac
                    },
                    onValueChangeFinished = {
                        val target = scrubFraction
                        scrubFraction = null
                        if (target != null) {
                            controller.player.seekTo((target * safeDur).toLong())
                        }
                    },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatClockMs(displayMs),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                        color = InkDim,
                    )
                    Text(
                        text = "-${formatClockMs((durationMs - displayMs).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                        color = InkDim,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Transport Buttons (Shuffle, SkipBack, Play/Pause, SkipForward, Repeat)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shuffle Button
                LiquidButton(
                    onClick = { controller.player.toggleShuffle() },
                    backdrop = backdrop,
                    surfaceColor = if (shuffleEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Bold.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else InkDim,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Previous Button
                LiquidButton(
                    onClick = { controller.player.skipPrevious() },
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(PhosphorIcons.Fill.SkipBack, contentDescription = "Previous", tint = Ink, modifier = Modifier.size(19.dp))
                }

                // Play / Pause Button (Hero glass circle)
                LiquidButton(
                    onClick = { controller.player.togglePlayPause() },
                    backdrop = backdrop,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                        contentDescription = "Play/Pause",
                        tint = Ink,
                        modifier = Modifier.size(26.dp),
                    )
                }

                // Next Button
                LiquidButton(
                    onClick = { controller.player.skipNext() },
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(PhosphorIcons.Fill.SkipForward, contentDescription = "Next", tint = Ink, modifier = Modifier.size(19.dp))
                }

                // Repeat Button
                LiquidButton(
                    onClick = { controller.player.toggleRepeat() },
                    backdrop = backdrop,
                    surfaceColor = if (repeatMode != DesktopRepeatMode.OFF) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (repeatMode == DesktopRepeatMode.ONE) PhosphorIcons.Bold.RepeatOnce else PhosphorIcons.Bold.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode != DesktopRepeatMode.OFF) MaterialTheme.colorScheme.primary else InkDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Volume LiquidSlider row with mute toggle
            var preMuteVol by remember { mutableStateOf(0.85f) }
            Row(
                modifier = Modifier.fillMaxWidth(0.78f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (volume <= 0.001f) PhosphorIcons.Fill.SpeakerSlash else PhosphorIcons.Fill.SpeakerHigh,
                    contentDescription = "Mute / Unmute",
                    tint = InkDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            if (volume > 0.001f) {
                                preMuteVol = volume
                                controller.player.setMasterVolume(0f)
                            } else {
                                controller.player.setMasterVolume(if (preMuteVol > 0.05f) preMuteVol else 0.85f)
                            }
                        },
                )
                LiquidSlider(
                    value = { volume },
                    onValueChange = { controller.player.setMasterVolume(it) },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.005f,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    color = InkDim,
                    modifier = Modifier.width(36.dp),
                )
            }
        }

        // RIGHT COLUMN: Full-Height Stage (Karaoke Lyrics / Automix Queue / DSP Studio / Spectrogram Inspector)
        val stageShape = ContinuousRoundedRectangle(28.dp)
        Column(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight()
                .liquidGlass(
                    config = glassConfig,
                    shape = stageShape,
                    ownBackdrop = backdrop,
                )
                .padding(20.dp),
        ) {
            // Top Floating Segmented Switcher Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImmersionRightTab.entries.forEach { tab ->
                    val selected = tab == activeRightTab
                    val pillShape = ContinuousCapsule()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(pillShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                else Color.White.copy(alpha = 0.12f),
                                shape = pillShape,
                            )
                            .clickable { controller.setImmersionTab(tab) }
                            .padding(vertical = 9.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val icon = when (tab) {
                                ImmersionRightTab.LYRICS -> PhosphorIcons.Fill.MicrophoneStage
                                ImmersionRightTab.QUEUE -> PhosphorIcons.Bold.Shuffle
                                ImmersionRightTab.DSP -> PhosphorIcons.Bold.Sliders
                                ImmersionRightTab.INSPECT -> PhosphorIcons.Bold.Waveform
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else InkDim,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                ),
                                color = if (selected) Ink else InkDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Stage Body
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(targetState = activeRightTab, label = "immersionRightStage") { tab ->
                    when (tab) {
                        ImmersionRightTab.LYRICS -> {
                            val currentLyrics = lyrics
                            when {
                                isLyricsLoading -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Searching word-timed TTML & LRCLIB lyrics…",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = InkDim,
                                        )
                                    }
                                }
                                currentLyrics != null && currentLyrics.lines.isNotEmpty() -> {
                                    Column(Modifier.fillMaxSize()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val hasWords = currentLyrics.lines.any { it.words.isNotEmpty() }
                                            Text(
                                                text = if (hasWords) "KARAOKE WORD-BY-WORD SYNC" else "TIME-SYNCED LYRICS",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = currentLyrics.source ?: "Synced",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = InkDim,
                                            )
                                        }
                                        LyricsView(
                                            lyrics = currentLyrics,
                                            positionMs = positionState,
                                            modifier = Modifier.fillMaxSize(),
                                            showTranslation = true,
                                            onSeek = { controller.player.seekTo(it) },
                                        )
                                    }
                                }
                                else -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Icon(
                                                imageVector = PhosphorIcons.Fill.MicrophoneStage,
                                                contentDescription = null,
                                                tint = InkDim,
                                                modifier = Modifier.size(42.dp),
                                            )
                                            Text(
                                                text = "No synced lyrics found for this track",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Ink,
                                            )
                                            Text(
                                                text = "Live 32-Band Audio Spectrum",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = InkDim,
                                            )
                                            LiveSpectrumBars(
                                                bands = spectrum,
                                                accent = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.fillMaxWidth(0.75f).height(110.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ImmersionRightTab.QUEUE -> {
                            AutomixQueueAndCurvePanel(
                                automix = automix,
                                queue = queue,
                                currentIndex = currentIndex,
                                backdrop = backdrop,
                                onToggleAutomix = { controller.player.setAutomixEnabled(it) },
                                onToggleBassSwap = { controller.player.setDjBassSwapEnabled(it) },
                                onCrossfadeChange = { controller.player.setCrossfadeDurationSec(it) },
                                onSelectQueueIndex = { idx -> controller.player.playTrackList(queue, idx) },
                            )
                        }

                        ImmersionRightTab.DSP -> {
                            AudioDspPanel(
                                dspState = dspState,
                                spectrum = spectrum,
                                backdrop = backdrop,
                                onSelectPreset = { controller.player.applyDspPreset(it) },
                                onUpdateDsp = { state, restart -> controller.player.updateDspState(state, restart) },
                            )
                        }

                        ImmersionRightTab.INSPECT -> {
                            val inspectSource = androidx.compose.runtime.remember(currentTrack?.uri) {
                                controller.resolveInspectionSource(currentTrack)
                            }
                            DesktopInspectView(
                                sourceUrlOrPath = inspectSource,
                                expectedDurationMs = currentTrack?.durationMs ?: 180_000L,
                                expectedSampleRate = currentTrack?.sampleRateHz ?: 44100,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutomixStatusPill(
    automix: AutomixUiState,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onClick: () -> Unit,
) {
    val pillShape = ContinuousCapsule()
    val borderCol = if (automix.isMixing) {
        MaterialTheme.colorScheme.primary
    } else if (automix.enabled) {
        Color.White.copy(alpha = 0.25f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }

    Row(
        modifier = Modifier
            .liquidGlass(glassConfig, pillShape, ownBackdrop = backdrop)
            .clip(pillShape)
            .border(1.dp, borderCol, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        automix.isMixing -> Color(0xFF22C55E)
                        automix.enabled -> MaterialTheme.colorScheme.primary
                        else -> InkDim
                    }
                ),
        )
        Text(
            text = when {
                automix.isMixing -> {
                    val pct = (automix.progress * 100).toInt()
                    val khz = String.format("%.1f", automix.filterCutoffHz / 1000f)
                    "MIXING $pct% • LP $khz kHz"
                }
                automix.enabled -> "AUTOMIX • ${automix.crossfadeDurationSec.toInt()}s DJ SWAP"
                else -> "AUTOMIX OFF"
            },
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
        )
    }
}

@Composable
fun AutomixQueueAndCurvePanel(
    automix: AutomixUiState,
    queue: List<CatalogTrack>,
    currentIndex: Int,
    backdrop: Backdrop,
    onToggleAutomix: (Boolean) -> Unit,
    onToggleBassSwap: (Boolean) -> Unit,
    onCrossfadeChange: (Float) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Dual-Deck Crossfade & Underwater DJ Filter Graph Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "YUMA DUAL-DECK AUTOMIX ENGINE",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                    Text(
                        text = if (automix.isMixing) {
                            "Deck A (${(automix.outgoingGain * 100).toInt()}%) → Deck B (${(automix.incomingGain * 100).toInt()}%) • Filter ${automix.filterCutoffHz.toInt()} Hz"
                        } else {
                            "Staged Underwater Low-Pass (20kHz → 2.2kHz) + Equal-Power Bass Swap"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                    )
                }
                LiquidToggle(
                    selected = { automix.enabled },
                    onSelect = onToggleAutomix,
                    backdrop = backdrop,
                )
            }

            // Interactive Curve Canvas showing Outgoing Deck A (Cyan), Incoming Deck B (Accent), and progress cursor
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(8.dp),
            ) {
                val w = size.width
                val h = size.height
                val outPath = Path()
                val inPath = Path()
                val steps = 80
                for (i in 0..steps) {
                    val p = i.toFloat() / steps
                    val x = p * w
                    val outG = (outgoingStageGain(p) * outgoingMidDuckGain(p).toFloat()).coerceIn(0f, 1f)
                    val inG = incomingStageGain(p).coerceIn(0f, 1f)
                    val yOut = h * (1f - outG * 0.88f)
                    val yIn = h * (1f - inG * 0.88f)
                    if (i == 0) {
                        outPath.moveTo(x, yOut)
                        inPath.moveTo(x, yIn)
                    } else {
                        outPath.lineTo(x, yOut)
                        inPath.lineTo(x, yIn)
                    }
                }
                drawPath(outPath, color = Color(0xFF38BDF8), style = Stroke(width = 2.5.dp.toPx()))
                drawPath(inPath, color = accent, style = Stroke(width = 2.5.dp.toPx()))

                if (automix.isMixing) {
                    val cx = automix.progress.coerceIn(0f, 1f) * w
                    drawLine(
                        color = Color(0xFF22C55E),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Crossfade: ${automix.crossfadeDurationSec.toInt()}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier.width(96.dp),
                )
                LiquidSlider(
                    value = { automix.crossfadeDurationSec },
                    onValueChange = onCrossfadeChange,
                    valueRange = 2f..15f,
                    visibilityThreshold = 0.1f,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "DJ Bass Swap",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
                LiquidToggle(
                    selected = { automix.djBassSwapEnabled },
                    onSelect = onToggleBassSwap,
                    backdrop = backdrop,
                )
            }
        }

        // Up Next Queue list
        Text(
            text = "PLAYING QUEUE (${queue.size} TRACKS)",
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(queue, key = { idx, t -> "${t.uri}_$idx" }) { idx, track ->
                val isCurrent = idx == currentIndex
                val isNext = idx == currentIndex + 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                isCurrent -> accent.copy(alpha = 0.22f)
                                isNext -> Color.White.copy(alpha = 0.07f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { onSelectQueueIndex(idx) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = when {
                            isCurrent -> "▶"
                            isNext -> "NEXT"
                            else -> "${idx + 1}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isCurrent || isNext) accent else InkDim,
                        modifier = Modifier.width(36.dp),
                    )
                    DesktopArtworkImage(
                        artworkUrl = track.artworkUrl,
                        titleFallback = track.name,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${track.artist} • ${track.qualityBadge}",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatClockMs(track.durationMs),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = InkDim,
                    )
                }
            }
        }
    }
}

@Composable
fun AudioDspPanel(
    dspState: AudioDspState,
    spectrum: FloatArray,
    backdrop: Backdrop,
    onSelectPreset: (DesktopDspPreset) -> Unit,
    onUpdateDsp: (AudioDspState, Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "REAL-TIME 32-BAND SPECTRUM & DSP STUDIO",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )

        LiveSpectrumBars(
            bands = spectrum,
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.26f))
                .padding(10.dp),
        )

        Text(
            text = "ONE-CLICK STUDIO PRESETS",
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
        )

        // 2x3 Grid of Presets
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopDspPreset.entries.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPresets.forEach { preset ->
                        val selected = dspState.preset == preset
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) accent.copy(alpha = 0.24f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) accent else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable { onSelectPreset(preset) }
                                .padding(12.dp),
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Ink,
                            )
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Manual DSP Sliders
        DspSliderRow(
            label = "Playback Speed",
            valueText = String.format("%.2fx", dspState.speed),
            value = dspState.speed,
            range = 0.5f..1.5f,
            backdrop = backdrop,
            onValueChange = { onUpdateDsp(dspState.copy(speed = it), true) },
        )
        DspSliderRow(
            label = "Pitch Shift",
            valueText = String.format("%.2fx", dspState.pitch),
            value = dspState.pitch,
            range = 0.5f..1.5f,
            backdrop = backdrop,
            onValueChange = { onUpdateDsp(dspState.copy(pitch = it), true) },
        )
        DspSliderRow(
            label = "Hall Reverb",
            valueText = "${(dspState.reverb * 100).toInt()}%",
            value = dspState.reverb,
            range = 0f..1f,
            backdrop = backdrop,
            onValueChange = { onUpdateDsp(dspState.copy(reverb = it), false) },
        )
        DspSliderRow(
            label = "Sub-Bass Shelf",
            valueText = String.format("%+.1f dB", dspState.bassBoostDb),
            value = dspState.bassBoostDb,
            range = -6f..12f,
            backdrop = backdrop,
            onValueChange = { onUpdateDsp(dspState.copy(bassBoostDb = it), false) },
        )
        DspSliderRow(
            label = "Karaoke Vocal Cancellation",
            valueText = "${(dspState.vocalRemoval * 100).toInt()}%",
            value = dspState.vocalRemoval,
            range = 0f..1f,
            backdrop = backdrop,
            onValueChange = { onUpdateDsp(dspState.copy(vocalRemoval = it), false) },
        )
    }
}

@Composable
private fun DspSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            valueRange = range,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun LiveSpectrumBars(
    bands: FloatArray,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val count = bands.size.coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2f)
        for (i in 0 until count) {
            val mag = bands[i].coerceIn(0.05f, 1f)
            val barH = size.height * mag
            val x = i * (barWidth + gap)
            val y = size.height - barH
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.35f)),
                    startY = y,
                    endY = size.height,
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

fun formatClockMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
