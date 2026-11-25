package dev.lelonio.square.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowDown
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Waveform
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.player.AutomixBadge
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import org.json.JSONArray
import kotlin.math.roundToInt

private data class OutputDeviceInfo(val id: String, val name: String, val isDefault: Boolean, val maxChannels: Int)

@Composable
fun AudioInspectorContent(state: AppState, modifier: Modifier = Modifier) {
    val chain by state.player.signalChain.collectAsState()
    val song by state.player.current.collectAsState()
    val devices by produceState(emptyList<OutputDeviceInfo>(), state.generation) {
        value = runCatching {
            val arr = JSONArray(NativeEngine.listDevices())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OutputDeviceInfo(
                    id = o.optString("id"),
                    name = o.optString("name", "Default"),
                    isDefault = o.optBoolean("isDefault", false),
                    maxChannels = o.optInt("maxChannels", 2),
                )
            }
        }.getOrDefault(emptyList())
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("desktop_signal_chain", Modifier.weight(1f))
            AutomixBadge(chain, state.smartAutomix)
        }

        // 1. Source File
        val srcSummary = if (chain.sourceSampleRate > 0) {
            val bits = if (chain.sourceBitsPerSample > 0) "${chain.sourceBitsPerSample}-bit · " else ""
            "${chain.sourceCodec.uppercase()} · $bits${chain.sourceSampleRate} Hz · ${chain.sourceChannels}ch"
        } else {
            song?.suffix?.uppercase() ?: str("desktop_not_playing")
        }
        ChainStageCard(
            title = str("desktop_chain_source"),
            primary = srcSummary,
            secondary = song?.title,
        )

        ChainArrow()

        // 2. DSP & Automix
        val activeStages = buildList {
            if (state.smartAutomix) {
                add(if (chain.mixing) str("desktop_automix_mixing") else str("desktop_automix_ready"))
            }
            if (state.fx.speed != 1f) add("${str("speed")} ${"%.2f×".format(state.fx.speed)}")
            if (state.fx.pitch != 1f) add("${str("pitch")} ${"%.2f×".format(state.fx.pitch)}")
            if (state.eqGains.any { it != 0f }) add(str("equalizer"))
            if (state.fx.reverb > 0f) add("${str("reverb")} ${(state.fx.reverb * 100).roundToInt()}%")
            if (state.fx.karaoke > 0f) add(str("karaoke"))
            if (state.fx.loudness && chain.lufs.isFinite()) add("EBU R128 ${"%.1f".format(chain.lufs)} LUFS")
        }
        val dspPrimary = if (chain.exclusiveActive) {
            "Bit-Perfect Bypass"
        } else if (activeStages.isEmpty()) {
            "Pure Pass-Through (float32)"
        } else {
            activeStages.joinToString(" → ")
        }
        val mixSub = if (chain.mixing) {
            "${str("desktop_automix_label")}: ${(chain.mixProgress * 100).roundToInt()}%"
        } else if (state.smartAutomix) {
            val swap = if (state.bassSwap) " · ${str("automix_bass_swap")}" else ""
            val loop = if (state.loopMix) " · ${str("desktop_loop_mix")}" else ""
            "${str("smart_automix")}$swap$loop"
        } else {
            "${str("crossfade")}: ${state.player.crossfadeSeconds}s"
        }
        ChainStageCard(
            title = str("desktop_chain_dsp"),
            primary = dspPrimary,
            secondary = mixSub,
        )

        ChainArrow()

        // 3. Output Device & WASAPI Mode
        val outModeLabel = if (chain.exclusiveActive) str("desktop_output_exclusive") else str("desktop_output_shared")
        val devRate = if (chain.deviceSampleRate > 0) "${chain.deviceSampleRate} Hz · ${chain.deviceFormat} · ${chain.deviceChannels}ch" else "—"
        val devSub = buildString {
            append(chain.deviceName.ifBlank { "System Default" })
            append(" · ")
            append(devRate)
            if (chain.bufferedMs > 0) append(" · ${chain.bufferedMs} ms")
            if (chain.fallbackReason.isNotBlank()) {
                append("\n")
                append(str("desktop_output_fallback", chain.fallbackReason))
            }
        }
        ChainStageCard(
            title = str("desktop_chain_output"),
            primary = outModeLabel,
            secondary = devSub,
        )

        Spacer(Modifier.height(4.dp))
        SectionLabel("desktop_output_mode")
        Segmented(
            listOf("desktop_output_shared", "desktop_output_exclusive"),
            if (state.player.outputMode == "exclusive") 1 else 0,
        ) { idx ->
            val mode = if (idx == 1) "exclusive" else "shared"
            state.setAudioOutput(mode, state.player.outputDeviceId)
        }

        if (devices.isNotEmpty()) {
            SectionLabel("desktop_output_device", Modifier.padding(top = 6.dp))
            devices.forEach { dev ->
                val selected = (state.player.outputDeviceId == null && dev.isDefault) || state.player.outputDeviceId == dev.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Ink.copy(alpha = 0.12f) else Ink.copy(alpha = 0.05f))
                        .clickable { state.setAudioOutput(state.player.outputMode, dev.id) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Regular.SpeakerHigh, null, tint = if (selected) Ink else InkDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dev.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) Ink else InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainStageCard(title: String, primary: String, secondary: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PhosphorIcons.Regular.Waveform, null, tint = InkDim, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = InkDim)
        }
        Text(primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Ink)
        if (!secondary.isNullOrBlank()) {
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChainArrow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Icon(PhosphorIcons.Regular.ArrowDown, null, tint = InkDim.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
    }
}
