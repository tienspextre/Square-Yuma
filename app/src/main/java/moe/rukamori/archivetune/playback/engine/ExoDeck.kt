package moe.rukamori.archivetune.playback.engine

import androidx.media3.exoplayer.ExoPlayer
import moe.rukamori.archivetune.audiodsp.AudioDeck
import moe.rukamori.archivetune.audiodsp.AutomixPlan
import moe.rukamori.archivetune.audiodsp.DjFilterAudioProcessor
import moe.rukamori.archivetune.audiodsp.FALL
import moe.rukamori.archivetune.audiodsp.RISE
import moe.rukamori.archivetune.audiodsp.TransitionTier
import moe.rukamori.archivetune.audiodsp.incomingBassGainDb
import moe.rukamori.archivetune.audiodsp.incomingStageGain
import moe.rukamori.archivetune.audiodsp.outgoingBassGainDb
import moe.rukamori.archivetune.audiodsp.outgoingLowPassHz
import moe.rukamori.archivetune.audiodsp.outgoingStageGain
import timber.log.Timber

class ExoDeck(
    override val player: ExoPlayer,
    override val djFilter: DjFilterAudioProcessor,
    var partner: ExoDeck? = null,
    var isIncoming: Boolean = false,
    var baseVolume: Float = 1f,
    var maxGainFactor: Float = 1f,
) : AudioDeck {
    private var lastLoggedQuarter = -1

    override fun applyAutomation(progress: Float, plan: AutomixPlan) {
        val clamped = progress.coerceIn(0f, 1f)
        val isAutomix = plan.tier != TransitionTier.PLAIN_CROSSFADE || plan.enableBassSwap

        if (isIncoming) {
            val gain = if (isAutomix) incomingStageGain(clamped) else RISE(clamped)
            player.volume = (baseVolume * gain).coerceIn(0f, maxGainFactor)
            if (plan.enableBassSwap) {
                djFilter.highPassHz = DjFilterAudioProcessor.BYPASS_HIGH_PASS_HZ
                djFilter.bassGainDb = incomingBassGainDb(clamped)
            } else {
                djFilter.clearAutomation()
            }
        } else {
            if (isAutomix) {
                val partnerGain = (partner?.baseVolume ?: 0f) * incomingStageGain(clamped)
                val sumCap = (1f - partnerGain).coerceIn(0f, 1f)
                player.volume = (baseVolume * outgoingStageGain(clamped))
                    .coerceIn(0f, maxGainFactor)
                    .coerceAtMost(sumCap)
            } else {
                player.volume = (baseVolume * FALL(clamped)).coerceIn(0f, maxGainFactor)
            }

            if (plan.enableBassSwap) {
                djFilter.lowPassCutoffHz = outgoingLowPassHz(clamped)
                djFilter.bassGainDb = outgoingBassGainDb(clamped)
            } else {
                djFilter.clearAutomation()
            }
        }

        val quarter = (clamped * 4f).toInt().coerceIn(0, 4)
        if (plan.enableBassSwap && quarter != lastLoggedQuarter) {
            lastLoggedQuarter = quarter
            Timber.tag("DjFilter").d(
                "automation incoming=%b p=%.2f lp=%.0f bass=%.1f",
                isIncoming,
                clamped,
                djFilter.lowPassCutoffHz,
                djFilter.bassGainDb,
            )
        }
    }

    override fun clearAutomation() {
        djFilter.clearAutomation()
        lastLoggedQuarter = -1
    }
}
