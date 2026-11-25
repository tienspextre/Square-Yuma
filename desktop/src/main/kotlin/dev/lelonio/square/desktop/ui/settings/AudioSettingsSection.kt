package dev.lelonio.square.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Eq
import dev.lelonio.square.desktop.Fx
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.FxSlider
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.components.Toggle
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.roundToInt

internal fun LazyListScope.audioSettingsItems(state: AppState) {
    item { SectionLabel("crossfade", Modifier.padding(top = 14.dp)) }
    item {
        var crossfade by remember(state.player.crossfadeSeconds) { mutableStateOf(state.player.crossfadeSeconds.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                crossfade,
                { crossfade = it; state.setCrossfade(it.roundToInt()) },
                Modifier.weight(1f),
                valueRange = 0f..12f,
                steps = 11,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
            )
            Text(
                if (crossfade < 0.5f) str("crossfade_off") else "${crossfade.roundToInt()} s",
                color = Ink,
                modifier = Modifier.width(64.dp).padding(start = 12.dp),
            )
        }
    }
    item { Toggle("smart_automix", state.smartAutomix) { state.useAutomix(smart = it) } }
    if (state.smartAutomix) {
        item { Toggle("automix_bass_swap", state.bassSwap) { state.useAutomix(bass = it) } }
        item { Toggle("desktop_loop_mix", state.loopMix) { state.useAutomix(loop = it) } }
        item {
            Text(
                str("desktop_automix_note"),
                color = InkDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    item { Toggle("trim_silence", state.trimSilence) { state.useTrimSilence(it) } }
    item { Toggle("skip_fade", state.skipFade) { state.useSkipFade(it) } }
    item { Toggle("desktop_autoplay_end", state.autoplayAtEnd) { state.useAutoplay(it) } }

    item { SectionLabel("effects", Modifier.padding(top = 14.dp)) }
    item {
        val i = Fx.PRESETS.indexOfFirst { it.second.copy(loudness = state.fx.loudness, karaoke = state.fx.karaoke) == state.fx }
        Segmented(Fx.PRESETS.map { it.first }, i) { k ->
            state.setFx(Fx.PRESETS[k].second.copy(loudness = state.fx.loudness, karaoke = state.fx.karaoke))
        }
    }
    item { FxSlider("speed", state.fx.speed, 0.5f..2f, { "%.2f×".format(it) }) { v, done -> state.setFx(state.fx.copy(speed = v), done) } }
    item { FxSlider("pitch", state.fx.pitch, 0.5f..2f, { "%.2f×".format(it) }) { v, done -> state.setFx(state.fx.copy(pitch = v), done) } }
    item { FxSlider("reverb", state.fx.reverb, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v, done -> state.setFx(state.fx.copy(reverb = v), done) } }
    item { FxSlider("desktop_karaoke_full", state.fx.karaoke, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v, done -> state.setFx(state.fx.copy(karaoke = v), done) } }
    item { Toggle("desktop_match_loudness", state.fx.loudness) { state.setFx(state.fx.copy(loudness = it)) } }

    item { SectionLabel("equalizer", Modifier.padding(top = 14.dp)) }
    item {
        val gains = state.eqGains
        val preset = Eq.PRESETS.indexOfFirst { it.second.contentEquals(gains) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eq.PRESETS.chunked(4).forEach { row ->
                Segmented(row.map { it.first }, row.indexOfFirst { Eq.PRESETS.indexOf(it) == preset }) { i ->
                    state.setEq(row[i].second.copyOf())
                }
            }
            Eq.BANDS.forEachIndexed { b, hz ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Eq.label(hz), color = InkDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
                    Slider(
                        gains[b],
                        { v -> state.setEq(gains.copyOf().also { it[b] = (v * 2).roundToInt() / 2f }, restart = false) },
                        Modifier.weight(1f),
                        valueRange = -12f..12f,
                        onValueChangeFinished = { state.player.reapply() },
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
                    )
                    Text("%+.1f dB".format(gains[b]), color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp).padding(start = 10.dp))
                }
            }
            TextButton({ state.setEq(FloatArray(Eq.BANDS.size)) }) { Text(str("eq_reset")) }
        }
    }

    item { SectionLabel("lyrics_style_title", Modifier.padding(top = 14.dp)) }
    item {
        Segmented(listOf("lyrics_style_animated", "lyrics_style_classic"), if (state.classicLyrics) 1 else 0) {
            state.useClassicLyrics(it == 1)
        }
    }
    item { SectionLabel("lyrics_sync_offset", Modifier.padding(top = 14.dp)) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                state.lyricsOffsetMs / 1000f,
                { state.setLyricsOffset(((it * 4).roundToInt() * 250).toLong()) },
                Modifier.weight(1f),
                valueRange = -3f..3f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
            )
            Text("%+.2f s".format(state.lyricsOffsetMs / 1000f), color = Ink, modifier = Modifier.width(80.dp).padding(start = 12.dp))
        }
    }
}
