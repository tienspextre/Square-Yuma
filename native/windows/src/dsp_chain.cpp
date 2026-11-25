#include "dsp_chain.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace square {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kButterworthQ = 0.70710678;
constexpr double kBassCrossoverHz = 200.0;
constexpr double kShelfSlope = 1.0;
constexpr double kStemLowHz = 250.0;
constexpr double kStemHighHz = 5000.0;
constexpr double kDuetLevelWidth = 0.28;
constexpr double kDuetPhaseWidth = 0.55;
constexpr float kDuetKeepBelowHz = 120.0f;
constexpr float kDuetKeepAboveHz = 12000.0f;
constexpr double kTargetLufs = -16.0;
constexpr float kMinNormGain = 0.25f; // -12 dB
constexpr float kMaxNormGain = 2.0f;  // +6 dB
} // namespace

// --- Exclusive Format Selection ---

ExclusiveFormatDecision selectExclusiveFormat(const ExclusiveFormatRequest& req) {
    ExclusiveFormatDecision d;
    d.targetSampleRate = req.sourceSampleRate > 0 ? req.sourceSampleRate : 44100;
    d.targetChannels = req.sourceChannels > 0 ? req.sourceChannels : 2;
    d.targetBitsPerSample = 32;
    d.targetIsFloat = true;

    if (!req.exclusiveRequested) {
        return d;
    }
    if (!req.dspAllOff) {
        d.fallbackReason = "Exclusive mode bypassed: DSP, EQ, or Automix is active";
        return d;
    }
    if (req.sourceSampleRate == 0 || req.sourceSampleRate > 384000) {
        d.fallbackReason = "Exclusive mode unsupported for sample rate > 384 kHz";
        return d;
    }
    if (req.sourceChannels == 0 || req.sourceChannels > 8) {
        d.fallbackReason = "Exclusive mode unsupported channel count";
        return d;
    }

    uint16_t wantedBits = req.sourceBitsPerSample;
    if (wantedBits <= 16) {
        wantedBits = 16;
    } else if (wantedBits <= 24) {
        wantedBits = 24;
    } else {
        wantedBits = 32;
    }

    if (!req.deviceCapabilities.empty()) {
        auto matches = [&](uint16_t bits, bool flt) {
            for (const auto& cap : req.deviceCapabilities) {
                if (cap.sampleRate == req.sourceSampleRate &&
                    cap.channels == req.sourceChannels &&
                    cap.bitsPerSample == bits &&
                    cap.isFloat == flt) {
                    return true;
                }
            }
            return false;
        };

        if (matches(wantedBits, req.sourceIsFloat)) {
            d.useExclusive = true;
            d.targetBitsPerSample = wantedBits;
            d.targetIsFloat = req.sourceIsFloat;
            return d;
        }
        // 24-bit audio inside a 32-bit PCM container is bit-perfect in WASAPI exclusive.
        if (wantedBits == 24 && matches(32, false)) {
            d.useExclusive = true;
            d.targetBitsPerSample = 32;
            d.targetIsFloat = false;
            return d;
        }
        if (req.sourceIsFloat && matches(32, false)) {
            d.useExclusive = true;
            d.targetBitsPerSample = 32;
            d.targetIsFloat = false;
            return d;
        }
        d.fallbackReason = "Device does not support native format in exclusive mode";
        return d;
    }

    d.useExclusive = true;
    d.targetBitsPerSample = wantedBits;
    d.targetIsFloat = req.sourceIsFloat && wantedBits == 32;
    return d;
}

// --- TempoMap ---

float TempoMap::rateAt(double elapsedMixMs, double sourceTimeMs) const {
    if (!points.empty()) {
        if (points.size() == 1) return points.front().ratio;
        if (elapsedMixMs <= static_cast<double>(points.front().timeMs)) {
            return points.front().ratio;
        }
        if (elapsedMixMs >= static_cast<double>(points.back().timeMs)) {
            return points.back().ratio;
        }
        for (size_t i = 0; i + 1 < points.size(); ++i) {
            const double t0 = static_cast<double>(points[i].timeMs);
            const double t1 = static_cast<double>(points[i + 1].timeMs);
            if (elapsedMixMs >= t0 && elapsedMixMs <= t1) {
                if (t1 <= t0) return points[i + 1].ratio;
                const double f = (elapsedMixMs - t0) / (t1 - t0);
                return static_cast<float>(points[i].ratio + (points[i + 1].ratio - points[i].ratio) * f);
            }
        }
        return points.back().ratio;
    }

    if (sourceMs.size() >= 2 && sourceMs.size() == targetMs.size()) {
        if (sourceTimeMs < sourceMs.front() || sourceTimeMs >= sourceMs.back()) {
            return 1.0f;
        }
        auto it = std::upper_bound(sourceMs.begin(), sourceMs.end(), sourceTimeMs);
        size_t i = static_cast<size_t>(std::max<ptrdiff_t>(0, (it - sourceMs.begin()) - 1));
        if (i >= sourceMs.size() - 1) i = sourceMs.size() - 2;
        const double dt = targetMs[i + 1] - targetMs[i];
        if (dt <= 0.0) return 1.0f;
        return static_cast<float>((sourceMs[i + 1] - sourceMs[i]) / dt);
    }

    return 1.0f;
}

// --- MixStyles & Crossfade Math ---

namespace mixmath {

namespace {
constexpr float kDiveStartP = 0.05f;
constexpr float kDiveSubmergedP = 0.35f;
constexpr float kDiveHoldUntilP = 0.45f;
constexpr double kFilterUnderwaterHz = 2200.0;
constexpr double kLegacyFilterFloorHz = 1000.0;
constexpr float kIncomingStartP = 0.30f;
constexpr float kBassSwapStart = 0.45f;
constexpr float kBassSwapEnd = 0.55f;
constexpr double kBassSwapDominance = 0.55;

constexpr double kFilterEntryHz = 7000.0;
constexpr double kFilterFloorHz = 300.0;
constexpr double kFilterSweepShape = 0.75;
constexpr double kFilterFallShape = 1.6;
constexpr double kFilterRiseShape = 0.8;
constexpr double kEntryHighPassHz = 1200.0;
constexpr double kEntryShape = 0.35;
constexpr double kVocalSafeEntryHpHz = 2400.0;
constexpr double kVocalSepFloorHz = 1600.0;
constexpr double kVocalSepHpHz = 700.0;
constexpr double kVocalSafeRiseShape = 1.65;
constexpr double kVocalSafeFallShape = 0.65;
constexpr double kSweepOpenBy = 0.6;
constexpr double kHandoffOpenBy = 0.38;
constexpr double kVocalOpenBy = 0.6;
constexpr double kHandoffFloorHz = 1500.0;

float smoothStep(float t) {
    const float c = std::clamp(t, 0.0f, 1.0f);
    return c * c * (3.0f - 2.0f * c);
}

double entryHighPass(float p, double amount, double topHz, double openBy) {
    const double remaining = std::clamp(1.0 - static_cast<double>(p) / openBy, 0.0, 1.0);
    return glide(OFF_HZ, topHz, amount * std::pow(remaining, kEntryShape));
}

float vocalSafe(float styled, float safe, double clash) {
    return styled + (safe - styled) * static_cast<float>(clash);
}

double outgoingLowPassHz(float progress) {
    const float p = std::clamp(progress, 0.0f, 1.0f);
    if (p <= kDiveStartP) return OPEN_HZ;
    if (p <= kDiveSubmergedP) {
        const float t = (p - kDiveStartP) / (kDiveSubmergedP - kDiveStartP);
        return OPEN_HZ + (kFilterUnderwaterHz - OPEN_HZ) * smoothStep(t);
    }
    if (p <= kDiveHoldUntilP) return kFilterUnderwaterHz;
    const float t = (p - kDiveHoldUntilP) / (1.0f - kDiveHoldUntilP);
    return kFilterUnderwaterHz + (kLegacyFilterFloorHz - kFilterUnderwaterHz) * smoothStep(t);
}

double bassSwapIncomingLinear(float progress) {
    const float phase = std::clamp(
        (std::clamp(progress, 0.0f, 1.0f) - kBassSwapStart) / (kBassSwapEnd - kBassSwapStart),
        0.0f, 1.0f);
    if (phase <= 0.5f) {
        return static_cast<double>(phase / 0.5f) * kBassSwapDominance;
    }
    return kBassSwapDominance + static_cast<double>(phase - 0.5f) / 0.5 * (1.0 - kBassSwapDominance);
}

double linearToBassDb(double linear) {
    static const double bassFloor = std::pow(10.0, CUT_DB / 20.0);
    return std::max(CUT_DB, 20.0 * std::log10(std::max(linear, bassFloor)));
}

float outgoingStageGain(float progress) {
    const float p = std::clamp(progress, 0.0f, 1.0f);
    if (p <= kDiveHoldUntilP) return 1.0f;
    return 1.0f - smoothStep((p - kDiveHoldUntilP) / (1.0f - kDiveHoldUntilP));
}

float incomingStageGain(float progress) {
    const float p = std::clamp(progress, 0.0f, 1.0f);
    if (p < kIncomingStartP) return 0.0f;
    return smoothStep((p - kIncomingStartP) / (1.0f - kIncomingStartP));
}
} // namespace

float rise(float p) {
    return std::sin(std::clamp(p, 0.0f, 1.0f) * static_cast<float>(kPi) / 2.0f);
}

float fall(float p) {
    return std::cos(std::clamp(p, 0.0f, 1.0f) * static_cast<float>(kPi) / 2.0f);
}

double glide(double from, double to, double amount) {
    return from * std::pow(to / from, std::clamp(amount, 0.0, 1.0));
}

MixShapes shapeForStyle(MixStyle style, float p, double swapAt, double swapWidth, double clash) {
    const float clamped = std::clamp(p, 0.0f, 1.0f);
    const double width = swapWidth > 1e-6 ? swapWidth : 0.10;
    const double handover = std::clamp((static_cast<double>(clamped) - (swapAt - width)) / width, 0.0, 1.0);
    const double inBass = CUT_DB * (1.0 - handover);
    const double outBass = CUT_DB * handover;

    MixShapes res{};
    switch (style) {
    case MixStyle::FILTER_SWEEP:
    case MixStyle::BASS_HANDOFF: {
        const bool handoff = (style == MixStyle::BASS_HANDOFF);
        const double floorHz = handoff ? kHandoffFloorHz : kFilterFloorHz;
        const double outLp = glide(kFilterEntryHz, floorHz, std::pow(static_cast<double>(clamped), kFilterSweepShape));
        const double inHp = entryHighPass(
            clamped,
            1.0,
            glide(kEntryHighPassHz, kVocalSafeEntryHpHz, clash),
            handoff ? kHandoffOpenBy : kSweepOpenBy);
        const float styledIn = rise(static_cast<float>(std::pow(clamped, kFilterRiseShape)));
        const float styledOut = fall(static_cast<float>(std::pow(clamped, kFilterFallShape)));
        const float safeOut = fall(static_cast<float>(std::pow(clamped, kVocalSafeFallShape)));
        const float safeIn = rise(static_cast<float>(std::pow(clamped, kVocalSafeRiseShape)));
        res.outgoing = DeckShape{vocalSafe(styledOut, safeOut, clash), outLp, OFF_HZ, outBass, {}};
        res.incoming = DeckShape{vocalSafe(styledIn, safeIn, clash), OPEN_HZ, inHp, inBass, {}};
        break;
    }
    case MixStyle::BLEND: {
        const double outLp = (clash > 0.0)
            ? glide(OPEN_HZ, glide(OPEN_HZ, kVocalSepFloorHz, clash), std::pow(static_cast<double>(clamped), kFilterSweepShape))
            : OPEN_HZ;
        const double inHp = (clash > 0.0)
            ? entryHighPass(clamped, clash, kVocalSepHpHz, kVocalOpenBy)
            : OFF_HZ;
        res.outgoing = DeckShape{fall(clamped), outLp, OFF_HZ, outBass, {}};
        res.incoming = DeckShape{rise(clamped), OPEN_HZ, inHp, inBass, {}};
        break;
    }
    case MixStyle::EQUAL_POWER: {
        const double floorHz = glide(OPEN_HZ, kVocalSepFloorHz, clash);
        const double outLp = (clash > 0.0)
            ? glide(OPEN_HZ, floorHz, std::pow(static_cast<double>(clamped), kFilterSweepShape))
            : OPEN_HZ;
        const double inHp = (clash > 0.0)
            ? entryHighPass(clamped, clash, kVocalSepHpHz, kVocalOpenBy)
            : OFF_HZ;
        res.outgoing = DeckShape{fall(clamped), outLp, OFF_HZ, 0.0, {}};
        res.incoming = DeckShape{rise(clamped), OPEN_HZ, inHp, 0.0, {}};
        break;
    }
    }
    return res;
}

MixShapes automationForPlan(const AutomixPlanData& plan, float progress) {
    const float p = std::clamp(progress, 0.0f, 1.0f);
    MixShapes shapes;
    if (plan.hasStyle) {
        shapes = shapeForStyle(plan.style, p, plan.swapAt, plan.swapWidth, plan.vocalClash);
        if (plan.style == MixStyle::BLEND && !plan.enableBassSwap) {
            shapes.outgoing.bassDb = 0.0;
            shapes.incoming.bassDb = 0.0;
        }
    } else {
        const bool isAutomix = (plan.tier != TransitionTier::PLAIN_CROSSFADE) || plan.enableBassSwap;
        if (isAutomix) {
            const float inGain = incomingStageGain(p);
            const float sumCap = std::clamp(1.0f - inGain, 0.0f, 1.0f);
            const float outGain = std::min(outgoingStageGain(p), sumCap);
            shapes.outgoing.gain = outGain;
            shapes.incoming.gain = inGain;
        } else {
            shapes.outgoing.gain = fall(p);
            shapes.incoming.gain = rise(p);
        }
        if (plan.enableBassSwap) {
            shapes.outgoing.lowPassHz = outgoingLowPassHz(p);
            shapes.outgoing.bassDb = linearToBassDb(1.0 - bassSwapIncomingLinear(p));
            shapes.incoming.bassDb = linearToBassDb(bassSwapIncomingLinear(p));
        }
    }

    // Apply seam-specified VocalDuckMode and outgoingEnergyFill if active.
    if (plan.vocalDuckMode != VocalDuckMode::NONE && plan.vocalClash > 0.01) {
        const float duckDepth = static_cast<float>(std::clamp(1.0 - plan.vocalClash * (1.0 - p), 0.35, 1.0));
        shapes.outgoing.stems.active = true;
        shapes.outgoing.stems.vocal = duckDepth;
        shapes.outgoing.stems.vocalDuckMode = plan.vocalDuckMode;
        shapes.outgoing.stems.outgoingEnergyFill = plan.outgoingEnergyFill;
    } else if (plan.outgoingEnergyFill) {
        shapes.outgoing.stems.active = true;
        shapes.outgoing.stems.outgoingEnergyFill = true;
        shapes.outgoing.stems.vocalDuckMode = plan.vocalDuckMode;
    }

    return shapes;
}

float incomingTempoAt(const AutomixPlanData& plan, float progress, double elapsedMixMs, double sourceMs) {
    if (!plan.tempoMap.empty()) {
        return plan.tempoMap.rateAt(elapsedMixMs, sourceMs);
    }
    if (plan.tier != TransitionTier::SMART_BEATMATCH || std::abs(plan.incomingTempoRatio - 1.0f) < 1e-5f) {
        return 1.0f;
    }
    const float p = std::clamp(progress, 0.0f, 1.0f);
    if (p < TEMPO_RETURN_FROM) {
        return plan.incomingTempoRatio;
    }
    const float f = std::clamp((p - TEMPO_RETURN_FROM) / (1.0f - TEMPO_RETURN_FROM), 0.0f, 1.0f);
    const float eased = f * f * (3.0f - 2.0f * f);
    return plan.incomingTempoRatio + (1.0f - plan.incomingTempoRatio) * eased;
}

} // namespace mixmath

// --- PcmLoop ---

bool PcmLoop::init(int channels, int64_t startFrame, int64_t endFrame, int sampleRate) {
    reset();
    if (channels < 1 || sampleRate <= 0) return false;
    const int fade = std::max(1, static_cast<int>(sampleRate * 0.01));
    const int64_t length = endFrame - startFrame;
    if (length <= fade * 2 || length > static_cast<int64_t>(sampleRate * 16.5) || startFrame < fade) {
        return false;
    }
    channels_ = channels;
    start_ = startFrame;
    end_ = endFrame;
    fade_ = fade;
    length_ = static_cast<int>(length);
    recorded_.assign(static_cast<size_t>(length_ + fade_) * channels_, 0.0f);
    valid_ = true;
    return true;
}

void PcmLoop::reset() {
    valid_ = false;
    looping_ = false;
    read_ = 0;
    recorded_.clear();
}

float PcmLoop::sampleAt(int q, int c) const {
    const float body = recorded_[static_cast<size_t>(fade_ + q) * channels_ + c];
    const int k = q - (length_ - fade_);
    if (k < 0) return body;
    const double w = (k + 0.5) / static_cast<double>(fade_);
    const float lead = recorded_[static_cast<size_t>(k) * channels_ + c];
    return static_cast<float>(body * (1.0 - w) + lead * w);
}

void PcmLoop::pass(float* interleaved, int frames, int64_t position) {
    if (!valid_) return;
    for (int i = 0; i < frames; ++i) {
        const int64_t p = position + i;
        if (p < start_ - fade_ || p >= end_) continue;
        const size_t r = static_cast<size_t>(p - (start_ - fade_));
        for (int c = 0; c < channels_; ++c) {
            recorded_[r * channels_ + c] = interleaved[static_cast<size_t>(i) * channels_ + c];
        }
        if (p >= start_) {
            const int q = static_cast<int>(p - start_);
            for (int c = 0; c < channels_; ++c) {
                interleaved[static_cast<size_t>(i) * channels_ + c] = sampleAt(q, c);
            }
        }
    }
    if (position + frames >= end_) {
        looping_ = true;
    }
}

void PcmLoop::render(float* into, int frames) {
    if (!valid_ || length_ <= 0) {
        std::fill_n(into, static_cast<size_t>(frames) * channels_, 0.0f);
        return;
    }
    for (int i = 0; i < frames; ++i) {
        for (int c = 0; c < channels_; ++c) {
            into[static_cast<size_t>(i) * channels_ + c] = sampleAt(read_, c);
        }
        read_ = (read_ + 1) % length_;
    }
}

// --- BungeeStretcher ---

void BungeeStretcher::prepare(int sampleRate, int channels, int maxOutputFrames) {
    if (sampleRate <= 0 || channels <= 0) return;
    if (sampleRate == sampleRate_ && channels == channels_ && maxOutputFrames <= maxOutFrames_ && stream_) {
        return;
    }
    sampleRate_ = sampleRate;
    channels_ = channels;
    maxOutFrames_ = std::max(256, maxOutputFrames);
    maxInFrames_ = maxOutFrames_ * 2 + 256;

    stretcher_ = std::make_unique<Bungee::Stretcher<Bungee::Basic>>(
        Bungee::SampleRates{sampleRate, sampleRate}, channels);
    stream_ = std::make_unique<Bungee::Stream<Bungee::Basic>>(
        *stretcher_, maxInFrames_, channels);

    scratchInterleaved_.assign(static_cast<size_t>(maxInFrames_) * channels_, 0.0f);
    inputPlanar_.assign(static_cast<size_t>(maxInFrames_) * channels_, 0.0f);
    outputPlanar_.assign(static_cast<size_t>(maxOutFrames_) * channels_, 0.0f);
    inputPtrs_.resize(channels_);
    outputPtrs_.resize(channels_);
    for (int c = 0; c < channels_; ++c) {
        inputPtrs_[c] = inputPlanar_.data() + static_cast<size_t>(c) * maxInFrames_;
        outputPtrs_[c] = outputPlanar_.data() + static_cast<size_t>(c) * maxOutFrames_;
    }
    fracInAccum_ = 0.0;
    stretched_ = false;
}

void BungeeStretcher::reset() {
    if (sampleRate_ > 0 && channels_ > 0) {
        stretcher_ = std::make_unique<Bungee::Stretcher<Bungee::Basic>>(
            Bungee::SampleRates{sampleRate_, sampleRate_}, channels_);
        stream_ = std::make_unique<Bungee::Stream<Bungee::Basic>>(
            *stretcher_, maxInFrames_, channels_);
    }
    fracInAccum_ = 0.0;
    stretched_ = false;
}

bool BungeeStretcher::isActive(float speed, float pitch) const {
    return std::abs(speed - 1.0f) >= 1e-4f || std::abs(pitch - 1.0f) >= 1e-4f;
}

double BungeeStretcher::latencyFrames() const {
    return stream_ ? stream_->latency() : 0.0;
}

size_t BungeeStretcher::renderFromRing(SpscRing<float>& ring, float* interleavedOut, int outFrames,
                                       float speed, float pitch, bool& reachedEnd) {
    reachedEnd = false;
    if (outFrames <= 0 || channels_ <= 0) return 0;

    if (!isActive(speed, pitch) && !stretched_) {
        const size_t wanted = static_cast<size_t>(outFrames) * channels_;
        const size_t gotSamples = ring.read(interleavedOut, wanted);
        if (gotSamples < wanted) {
            std::fill(interleavedOut + gotSamples, interleavedOut + wanted, 0.0f);
            reachedEnd = true;
        }
        return gotSamples / static_cast<size_t>(channels_);
    }

    if (!isActive(speed, pitch) && stretched_) {
        // Transition back to bit-exact bypass: consume one block directly.
        stretched_ = false;
        fracInAccum_ = 0.0;
        const size_t wanted = static_cast<size_t>(outFrames) * channels_;
        const size_t gotSamples = ring.read(interleavedOut, wanted);
        if (gotSamples < wanted) {
            std::fill(interleavedOut + gotSamples, interleavedOut + wanted, 0.0f);
            reachedEnd = true;
        }
        return gotSamples / static_cast<size_t>(channels_);
    }

    if (!stream_ || outFrames > maxOutFrames_) {
        const size_t wanted = static_cast<size_t>(outFrames) * channels_;
        const size_t gotSamples = ring.read(interleavedOut, wanted);
        if (gotSamples < wanted) {
            std::fill(interleavedOut + gotSamples, interleavedOut + wanted, 0.0f);
            reachedEnd = true;
        }
        return gotSamples / static_cast<size_t>(channels_);
    }

    stretched_ = true;
    const double clampedSpeed = std::clamp(static_cast<double>(speed), 0.25, 4.0);
    const double clampedPitch = std::clamp(static_cast<double>(pitch), 0.25, 4.0);
    const double exactIn = static_cast<double>(outFrames) * clampedSpeed + fracInAccum_;
    int inFrames = std::clamp(static_cast<int>(std::round(exactIn)), 1, maxInFrames_);
    fracInAccum_ = exactIn - static_cast<double>(inFrames);

    const size_t wantedSamples = static_cast<size_t>(inFrames) * channels_;
    const size_t gotSamples = ring.read(scratchInterleaved_.data(), wantedSamples);
    const int gotFrames = static_cast<int>(gotSamples / static_cast<size_t>(channels_));
    if (gotFrames < inFrames) {
        std::fill(scratchInterleaved_.data() + gotSamples, scratchInterleaved_.data() + wantedSamples, 0.0f);
        if (gotFrames == 0) {
            reachedEnd = true;
        }
    }

    for (int f = 0; f < inFrames; ++f) {
        for (int c = 0; c < channels_; ++c) {
            inputPlanar_[static_cast<size_t>(c) * maxInFrames_ + f] =
                scratchInterleaved_[static_cast<size_t>(f) * channels_ + c];
        }
    }

    const int produced = stream_->process(
        inputPtrs_.data(),
        outputPtrs_.data(),
        inFrames,
        static_cast<double>(outFrames),
        clampedPitch);

    const int copyFrames = std::min(produced, outFrames);
    for (int f = 0; f < copyFrames; ++f) {
        for (int c = 0; c < channels_; ++c) {
            interleavedOut[static_cast<size_t>(f) * channels_ + c] = outputPtrs_[c][f];
        }
    }
    if (copyFrames < outFrames) {
        std::fill(interleavedOut + static_cast<size_t>(copyFrames) * channels_,
                  interleavedOut + static_cast<size_t>(outFrames) * channels_,
                  0.0f);
    }

    return static_cast<size_t>(gotFrames);
}

// --- DjFilter ---

void DjFilter::prepare(double sampleRate, int channels) {
    sampleRate_ = sampleRate > 0.0 ? sampleRate : 44100.0;
    channels_ = std::clamp(channels, 1, kMaxChannels);
    computeLowPass(lowEdgeCoeff_, kStemLowHz, kButterworthQ);
    computeHighPass(highEdgeCoeff_, kStemHighHz, kButterworthQ);
    computeHighPass(lowEdgeHpCoeff_, kStemLowHz, kButterworthQ);
    computeLowPass(highEdgeLpCoeff_, kStemHighHz, kButterworthQ);
    reset();
}

void DjFilter::reset() {
    for (auto& b : lp_) b.reset();
    for (auto& b : hp_) b.reset();
    for (auto& b : shelf_) b.reset();
    for (auto& arr : band_) {
        for (auto& b : arr) b.reset();
    }
    cachedLpHz_ = -1.0;
    cachedHpHz_ = -1.0;
    cachedBassDb_ = 9999.0;
    lastStem_ = {1.0f, 1.0f, 1.0f, 1.0f};
}

void DjFilter::computeLowPass(Biquad& coeffTarget, double hz, double q) const {
    const double clampedHz = std::clamp(hz, 30.0, sampleRate_ * 0.45);
    const double w0 = 2.0 * kPi * clampedHz / sampleRate_;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    coeffTarget.b0 = ((1.0 - cosW0) / 2.0) / a0;
    coeffTarget.b1 = (1.0 - cosW0) / a0;
    coeffTarget.b2 = coeffTarget.b0;
    coeffTarget.a1 = (-2.0 * cosW0) / a0;
    coeffTarget.a2 = (1.0 - alpha) / a0;
}

void DjFilter::computeHighPass(Biquad& coeffTarget, double hz, double q) const {
    const double clampedHz = std::clamp(hz, 30.0, sampleRate_ * 0.45);
    const double w0 = 2.0 * kPi * clampedHz / sampleRate_;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    coeffTarget.b0 = ((1.0 + cosW0) / 2.0) / a0;
    coeffTarget.b1 = (-(1.0 + cosW0)) / a0;
    coeffTarget.b2 = coeffTarget.b0;
    coeffTarget.a1 = (-2.0 * cosW0) / a0;
    coeffTarget.a2 = (1.0 - alpha) / a0;
}

void DjFilter::computeLowShelf(Biquad& coeffTarget, double db) const {
    const double amplitude = std::clamp(std::pow(10.0, db / 20.0), 0.001, 4.0);
    const double shelfA = std::sqrt(amplitude);
    const double w0 = 2.0 * kPi * kBassCrossoverHz / sampleRate_;
    const double cosW0 = std::cos(w0);
    const double alpha = std::sin(w0) / 2.0 * std::sqrt((shelfA + 1.0 / shelfA) * (1.0 / kShelfSlope - 1.0) + 2.0);
    const double blend = 2.0 * std::sqrt(shelfA) * alpha;
    const double a0 = (shelfA + 1.0) + (shelfA - 1.0) * cosW0 + blend;
    coeffTarget.b0 = (shelfA * ((shelfA + 1.0) - (shelfA - 1.0) * cosW0 + blend)) / a0;
    coeffTarget.b1 = (2.0 * shelfA * ((shelfA - 1.0) - (shelfA + 1.0) * cosW0)) / a0;
    coeffTarget.b2 = (shelfA * ((shelfA + 1.0) - (shelfA - 1.0) * cosW0 - blend)) / a0;
    coeffTarget.a1 = (-2.0 * ((shelfA - 1.0) + (shelfA + 1.0) * cosW0)) / a0;
    coeffTarget.a2 = ((shelfA + 1.0) + (shelfA - 1.0) * cosW0 - blend) / a0;
}

void DjFilter::process(float* interleaved, int frames, const DeckShape& shape) {
    if (frames <= 0 || !interleaved) return;

    const double lpHz = (shape.stems.active && shape.stems.lowPassHz > 0.0f)
        ? static_cast<double>(shape.stems.lowPassHz)
        : shape.lowPassHz;
    const double hpHz = (shape.stems.active && shape.stems.highPassHz > 0.0f)
        ? static_cast<double>(shape.stems.highPassHz)
        : shape.highPassHz;

    const bool filtering = lpHz < 20000.0;
    const bool highPassing = hpHz > 30.0;
    const bool shelving = std::abs(shape.bassDb) >= 0.01;
    const bool applyGain = std::abs(static_cast<double>(shape.gain) - 1.0) >= 1e-4;

    const std::array<float, 4> targetStem = shape.stems.active
        ? std::array<float, 4>{shape.stems.low, shape.stems.mid, shape.stems.high, shape.stems.vocal}
        : std::array<float, 4>{1.0f, 1.0f, 1.0f, 1.0f};
    const std::array<float, 4> fromStem = lastStem_;
    const bool splitting = shape.stems.active ||
        std::any_of(fromStem.begin(), fromStem.end(), [](float v) { return std::abs(v - 1.0f) > 1e-4f; });

    if (!filtering && !highPassing && !shelving && !applyGain && !splitting) {
        return; // Bit-exact bypass
    }

    if (filtering && std::abs(lpHz - cachedLpHz_) >= 1.0) {
        cachedLpHz_ = lpHz;
        computeLowPass(lpCoeff_, lpHz, kButterworthQ);
        for (int c = 0; c < channels_; ++c) {
            lp_[c].b0 = lpCoeff_.b0; lp_[c].b1 = lpCoeff_.b1; lp_[c].b2 = lpCoeff_.b2;
            lp_[c].a1 = lpCoeff_.a1; lp_[c].a2 = lpCoeff_.a2;
        }
    }
    if (highPassing && std::abs(hpHz - cachedHpHz_) >= 1.0) {
        cachedHpHz_ = hpHz;
        computeHighPass(hpCoeff_, hpHz, kButterworthQ);
        for (int c = 0; c < channels_; ++c) {
            hp_[c].b0 = hpCoeff_.b0; hp_[c].b1 = hpCoeff_.b1; hp_[c].b2 = hpCoeff_.b2;
            hp_[c].a1 = hpCoeff_.a1; hp_[c].a2 = hpCoeff_.a2;
        }
    }
    if (shelving && !(std::abs(shape.bassDb - cachedBassDb_) < 0.05)) {
        cachedBassDb_ = shape.bassDb;
        computeLowShelf(shelfCoeff_, shape.bassDb);
        for (int c = 0; c < channels_; ++c) {
            shelf_[c].b0 = shelfCoeff_.b0; shelf_[c].b1 = shelfCoeff_.b1; shelf_[c].b2 = shelfCoeff_.b2;
            shelf_[c].a1 = shelfCoeff_.a1; shelf_[c].a2 = shelfCoeff_.a2;
        }
    }

    std::array<double, kMaxChannels> frameIn{};
    std::array<double, kMaxChannels> frameLow{};
    std::array<double, kMaxChannels> frameMid{};
    std::array<double, kMaxChannels> frameHigh{};

    if (splitting) {
        for (int c = 0; c < channels_; ++c) {
            auto copyCoeff = [](Biquad& dst, const Biquad& src) {
                dst.b0 = src.b0; dst.b1 = src.b1; dst.b2 = src.b2; dst.a1 = src.a1; dst.a2 = src.a2;
            };
            copyCoeff(band_[c][0], lowEdgeCoeff_);
            copyCoeff(band_[c][1], lowEdgeCoeff_);
            copyCoeff(band_[c][2], lowEdgeHpCoeff_);
            copyCoeff(band_[c][3], lowEdgeHpCoeff_);
            copyCoeff(band_[c][4], highEdgeCoeff_);
            copyCoeff(band_[c][5], highEdgeCoeff_);
            copyCoeff(band_[c][6], highEdgeLpCoeff_);
            copyCoeff(band_[c][7], highEdgeLpCoeff_);
        }
    }

    for (int f = 0; f < frames; ++f) {
        for (int c = 0; c < channels_; ++c) {
            frameIn[c] = static_cast<double>(interleaved[static_cast<size_t>(f) * channels_ + c]);
        }

        if (splitting) {
            const double p = static_cast<double>(f + 1) / static_cast<double>(frames);
            const double gLow = fromStem[0] + (targetStem[0] - fromStem[0]) * p;
            const double gMid = fromStem[1] + (targetStem[1] - fromStem[1]) * p;
            const double gHigh = fromStem[2] + (targetStem[2] - fromStem[2]) * p;
            const double gVocal = fromStem[3] + (targetStem[3] - fromStem[3]) * p;

            double centre = 0.0;
            for (int c = 0; c < channels_; ++c) {
                const double x = frameIn[c];
                const double low = band_[c][1].run(band_[c][0].run(x));
                const double rest = band_[c][3].run(band_[c][2].run(x));
                frameLow[c] = low;
                frameHigh[c] = band_[c][5].run(band_[c][4].run(rest));
                frameMid[c] = band_[c][7].run(band_[c][6].run(rest));
                centre += frameMid[c];
            }
            centre /= static_cast<double>(channels_);

            if (shape.stems.vocalDuckMode == VocalDuckMode::FULL_BAND) {
                for (int c = 0; c < channels_; ++c) {
                    const double duckedMid = gVocal * frameMid[c];
                    const double fill = shape.stems.outgoingEnergyFill ? (1.0 + (1.0 - gVocal) * 0.25) : 1.0;
                    frameIn[c] = gLow * fill * frameLow[c] + gMid * duckedMid + gHigh * fill * frameHigh[c];
                }
            } else {
                // MID_SIDE (also default when VocalDuckMode::NONE with StemMix active)
                const double duck = (1.0 - gVocal) * centre;
                const double fill = shape.stems.outgoingEnergyFill ? (1.0 + (1.0 - gVocal) * 0.25) : 1.0;
                for (int c = 0; c < channels_; ++c) {
                    const double midOut = frameMid[c] - duck;
                    frameIn[c] = gLow * fill * frameLow[c] + gMid * (midOut * fill) + gHigh * fill * frameHigh[c];
                }
            }
        }

        for (int c = 0; c < channels_; ++c) {
            double s = frameIn[c];
            if (filtering) s = lp_[c].run(s);
            if (highPassing) s = hp_[c].run(s);
            if (shelving) s = shelf_[c].run(s);
            if (applyGain) s *= static_cast<double>(shape.gain);
            interleaved[static_cast<size_t>(f) * channels_ + c] = static_cast<float>(s);
        }
    }

    lastStem_ = targetStem;
}

// --- ParametricEq ---

void ParametricEq::prepare(int sampleRate, int channels) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    channels_ = std::clamp(channels, 1, 8);
    reset();
    rebuild();
}

void ParametricEq::reset() {
    for (auto& b : bands_) {
        b.z.fill(0.0);
    }
}

float ParametricEq::computeHeadroomDb(const std::array<float, kNumBands>& gainsDb) {
    constexpr int kRefRate = 48000;
    constexpr double kQ = 1.41;

    struct Coeff { double b0, b1, b2, a1, a2; bool active; };
    std::array<Coeff, kNumBands> coeffs{};
    for (int i = 0; i < kNumBands; ++i) {
        const double g = static_cast<double>(gainsDb[i]);
        const double freq = static_cast<double>(kFrequencies[i]);
        if (std::abs(g) < 0.05 || freq >= kRefRate * 0.45) {
            coeffs[i].active = false;
            continue;
        }
        const double a = std::pow(10.0, g / 40.0);
        const double w0 = 2.0 * kPi * freq / kRefRate;
        const double alpha = std::sin(w0) / (2.0 * kQ);
        const double cosW0 = std::cos(w0);
        const double a0 = 1.0 + alpha / a;
        coeffs[i] = {
            (1.0 + alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha / a) / a0,
            true,
        };
    }

    double peak = 0.0;
    double f = 20.0;
    while (f <= 20000.0) {
        const double w = 2.0 * kPi * f / kRefRate;
        const double cosW = std::cos(w);
        const double cos2W = std::cos(2.0 * w);
        const double sinW = std::sin(w);
        const double sin2W = std::sin(2.0 * w);
        double total = 0.0;
        for (int i = 0; i < kNumBands; ++i) {
            if (!coeffs[i].active) continue;
            const auto& c = coeffs[i];
            const double numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W;
            const double numIm = -(c.b1 * sinW + c.b2 * sin2W);
            const double denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W;
            const double denIm = -(c.a1 * sinW + c.a2 * sin2W);
            const double mag = std::sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
            total += 20.0 * std::log10(mag);
        }
        peak = std::max(peak, total);
        f *= 1.05;
    }
    return static_cast<float>(peak);
}

void ParametricEq::setGains(const std::array<float, kNumBands>& gainsDb, float preampDb) {
    bool changed = (std::abs(preampDb - preampDb_) >= 1e-4f);
    for (int i = 0; i < kNumBands; ++i) {
        if (std::abs(gainsDb[i] - gainsDb_[i]) >= 1e-4f) {
            changed = true;
            break;
        }
    }
    if (!changed) return;

    gainsDb_ = gainsDb;
    preampDb_ = std::clamp(preampDb, -12.0f, 12.0f);
    rebuild();
}

void ParametricEq::rebuild() {
    constexpr double kQ = 1.41;
    bool allZero = std::abs(preampDb_) < 0.05f;
    for (int i = 0; i < kNumBands; ++i) {
        if (std::abs(gainsDb_[i]) >= 0.05f) {
            allZero = false;
            break;
        }
    }
    if (allZero) {
        bypassed_ = true;
        activeBandCount_ = 0;
        inputGain_ = 1.0;
        reset();
        return;
    }

    bypassed_ = false;
    const float headroom = computeHeadroomDb(gainsDb_);
    inputGain_ = std::pow(10.0, (static_cast<double>(preampDb_) - static_cast<double>(headroom)) / 20.0);

    int count = 0;
    for (int i = 0; i < kNumBands; ++i) {
        const double g = static_cast<double>(gainsDb_[i]);
        const double freq = static_cast<double>(kFrequencies[i]);
        if (std::abs(g) < 0.05 || freq >= sampleRate_ * 0.45) continue;
        const double a = std::pow(10.0, g / 40.0);
        const double w0 = 2.0 * kPi * freq / sampleRate_;
        const double alpha = std::sin(w0) / (2.0 * kQ);
        const double cosW0 = std::cos(w0);
        const double a0 = 1.0 + alpha / a;
        bands_[count].b0 = (1.0 + alpha * a) / a0;
        bands_[count].b1 = (-2.0 * cosW0) / a0;
        bands_[count].b2 = (1.0 - alpha * a) / a0;
        bands_[count].a1 = (-2.0 * cosW0) / a0;
        bands_[count].a2 = (1.0 - alpha / a) / a0;
        count++;
    }
    if (count != activeBandCount_) {
        reset();
    }
    activeBandCount_ = count;
}

void ParametricEq::process(float* interleaved, int frames) {
    if (bypassed_ || frames <= 0 || !interleaved) return;

    for (int f = 0; f < frames; ++f) {
        for (int c = 0; c < channels_; ++c) {
            double x = static_cast<double>(interleaved[static_cast<size_t>(f) * channels_ + c]) * inputGain_;
            for (int b = 0; b < activeBandCount_; ++b) {
                auto& band = bands_[b];
                const int s = c * 2;
                const double y = band.b0 * x + band.z[s];
                band.z[s] = band.b1 * x - band.a1 * y + band.z[s + 1];
                band.z[s + 1] = band.b2 * x - band.a2 * y;
                x = y;
            }
            interleaved[static_cast<size_t>(f) * channels_ + c] = static_cast<float>(x);
        }
    }
}

// --- StereoReverb ---

void StereoReverb::prepare(int sampleRate, int channels) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 44100;
    channels_ = std::clamp(channels, 1, 2);
    static constexpr std::array<int, 8> kCombTunings = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr std::array<int, 4> kAllpassTunings = {556, 441, 341, 225};
    static constexpr int kStereoSpread = 23;
    const double scale = static_cast<double>(sampleRate_) / 44100.0;

    for (int c = 0; c < 2; ++c) {
        const int spread = (c == 1) ? kStereoSpread : 0;
        for (size_t i = 0; i < 8; ++i) {
            combs_[c][i].resize(std::max<size_t>(16, static_cast<size_t>((kCombTunings[i] + spread) * scale)));
        }
        for (size_t i = 0; i < 4; ++i) {
            allpasses_[c][i].resize(std::max<size_t>(16, static_cast<size_t>((kAllpassTunings[i] + spread) * scale)));
        }
    }
    setAmount(amount_);
}

void StereoReverb::reset() {
    for (int c = 0; c < 2; ++c) {
        for (auto& comb : combs_[c]) comb.clear();
        for (auto& ap : allpasses_[c]) ap.clear();
    }
}

void StereoReverb::setAmount(float amount) {
    amount_ = std::clamp(amount, 0.0f, 1.0f);
    if (amount_ <= 0.001f) {
        wet_ = 0.0f;
        return;
    }
    // Matches ReverbTuning.kt sendLevel(amount) = level^2 * 0.85 + level * 0.15
    const float send = amount_ * amount_ * 0.85f + amount_ * 0.15f;
    wet_ = send * 0.35f;
    // Decay from 500 ms (room) to 4500 ms (large hall), with HF damping
    const float feedback = 0.72f + amount_ * 0.22f;
    const float damp = 0.35f;
    for (int c = 0; c < 2; ++c) {
        for (auto& comb : combs_[c]) {
            comb.feedback = feedback;
            comb.damp1 = damp;
            comb.damp2 = 1.0f - damp;
        }
    }
}

void StereoReverb::process(float* interleaved, int frames) {
    if (isBypassed() || frames <= 0 || !interleaved) return;

    if (channels_ == 1) {
        for (int f = 0; f < frames; ++f) {
            const float dry = interleaved[f];
            const float in = dry * 0.015f;
            float out = 0.0f;
            for (auto& comb : combs_[0]) out += comb.process(in);
            for (auto& ap : allpasses_[0]) out = ap.process(out);
            interleaved[f] = dry + out * wet_;
        }
        return;
    }

    for (int f = 0; f < frames; ++f) {
        const float dryL = interleaved[static_cast<size_t>(f) * 2];
        const float dryR = interleaved[static_cast<size_t>(f) * 2 + 1];
        const float in = (dryL + dryR) * 0.015f;

        float outL = 0.0f;
        float outR = 0.0f;
        for (int i = 0; i < 8; ++i) {
            outL += combs_[0][i].process(in);
            outR += combs_[1][i].process(in);
        }
        for (int i = 0; i < 4; ++i) {
            outL = allpasses_[0][i].process(outL);
            outR = allpasses_[1][i].process(outR);
        }

        interleaved[static_cast<size_t>(f) * 2] = dryL + outL * wet_;
        interleaved[static_cast<size_t>(f) * 2 + 1] = dryR + outR * wet_;
    }
}

// --- KaraokeExtractor (CentreExtractor.kt port) ---

KaraokeExtractor::KaraokeExtractor() {
    for (int i = 0; i < kWindow; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * kPi * i / kWindow));
        const double ang = -2.0 * kPi * i / kWindow;
        cosTable_[i] = static_cast<float>(std::cos(ang));
        sinTable_[i] = static_cast<float>(std::sin(ang));
    }
    input_.assign(kBufFrames * 2, 0.0f);
    overlap_.assign(kBufFrames * 2, 0.0f);
    done_.assign(kBufFrames * 2, 0.0f);
}

void KaraokeExtractor::prepare(int sampleRate, int channels) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 44100;
    channels_ = channels;
    reset();
}

void KaraokeExtractor::reset() {
    pending_ = 0;
    ready_ = 0;
    std::fill(input_.begin(), input_.end(), 0.0f);
    std::fill(overlap_.begin(), overlap_.end(), 0.0f);
    std::fill(done_.begin(), done_.end(), 0.0f);
}

void KaraokeExtractor::fft(float* re, float* im, bool inverse) const {
    constexpr int n = kWindow;
    int j = 0;
    for (int i = 1; i < n; ++i) {
        int bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const int half = len >> 1;
        const int step = n / len;
        for (int i = 0; i < n; i += len) {
            for (int k = 0; k < half; ++k) {
                const int tw = k * step;
                const float wr = cosTable_[tw];
                const float wi = inverse ? -sinTable_[tw] : sinTable_[tw];
                const float vr = re[i + k + half] * wr - im[i + k + half] * wi;
                const float vi = re[i + k + half] * wi + im[i + k + half] * wr;
                const float ur = re[i + k];
                const float ui = im[i + k];
                re[i + k] = ur + vr;
                im[i + k] = ui + vi;
                re[i + k + half] = ur - vr;
                im[i + k + half] = ui - vi;
            }
        }
    }
    if (inverse) {
        const float invN = 1.0f / static_cast<float>(n);
        for (int i = 0; i < n; ++i) {
            re[i] *= invN;
            im[i] *= invN;
        }
    }
}

void KaraokeExtractor::transformFrame(size_t atInterleaved) {
    for (int i = 0; i < kWindow; ++i) {
        const float w = window_[i];
        leftRe_[i] = input_[atInterleaved + static_cast<size_t>(i) * 2] * w;
        leftIm_[i] = 0.0f;
        rightRe_[i] = input_[atInterleaved + static_cast<size_t>(i) * 2 + 1] * w;
        rightIm_[i] = 0.0f;
    }
    fft(leftRe_.data(), leftIm_.data(), false);
    fft(rightRe_.data(), rightIm_.data(), false);

    const int lowBin = static_cast<int>(kDuetKeepBelowHz * kWindow / static_cast<float>(sampleRate_));
    const int highBin = static_cast<int>(kDuetKeepAboveHz * kWindow / static_cast<float>(sampleRate_));

    for (int bin = 0; bin < kBins; ++bin) {
        const float lr = leftRe_[bin];
        const float li = leftIm_[bin];
        const float rr = rightRe_[bin];
        const float ri = rightIm_[bin];

        float gain = 1.0f;
        if (bin >= lowBin && bin <= highBin) {
            const float leftSize = std::hypot(lr, li);
            const float rightSize = std::hypot(rr, ri);
            if (leftSize >= 1e-7f || rightSize >= 1e-7f) {
                const float level = std::abs(leftSize - rightSize) / (leftSize + rightSize);
                const float crossReal = lr * rr + li * ri;
                const float crossImag = li * rr - lr * ri;
                const float phase = std::abs(std::atan2(crossImag, crossReal));
                const double centred = std::exp(
                    -(level * level) / (kDuetLevelWidth * kDuetLevelWidth) -
                    (phase * phase) / (kDuetPhaseWidth * kDuetPhaseWidth));
                gain = 1.0f - amount_ * static_cast<float>(centred);
            }
        }

        leftRe_[bin] = lr * gain;
        leftIm_[bin] = li * gain;
        rightRe_[bin] = rr * gain;
        rightIm_[bin] = ri * gain;

        if (bin >= 1 && bin < kBins - 1) {
            const int mirror = kWindow - bin;
            leftRe_[mirror] = leftRe_[bin];
            leftIm_[mirror] = -leftIm_[bin];
            rightRe_[mirror] = rightRe_[bin];
            rightIm_[mirror] = -rightIm_[bin];
        }
    }

    fft(leftRe_.data(), leftIm_.data(), true);
    fft(rightRe_.data(), rightIm_.data(), true);

    constexpr float kNormalise = 2.0f / 3.0f;
    for (int i = 0; i < kWindow; ++i) {
        const float w = window_[i] * kNormalise;
        overlap_[atInterleaved + static_cast<size_t>(i) * 2] += leftRe_[i] * w;
        overlap_[atInterleaved + static_cast<size_t>(i) * 2 + 1] += rightRe_[i] * w;
    }
}

void KaraokeExtractor::process(float* interleaved, int frames) {
    if (isBypassed() || frames <= 0 || !interleaved) return;

    const size_t totalSamples = static_cast<size_t>(frames) * 2;
    size_t offset = 0;
    // Process in chunks fitting our preallocated buffer (zero heap allocs).
    while (offset < totalSamples) {
        const size_t maxPush = input_.size() - pending_;
        const size_t chunk = std::min(totalSamples - offset, maxPush);
        if (chunk == 0) break;

        std::memcpy(input_.data() + pending_, interleaved + offset, chunk * sizeof(float));
        pending_ += chunk;

        size_t consumed = 0;
        while (pending_ - consumed >= static_cast<size_t>(kWindow) * 2) {
            transformFrame(consumed);
            consumed += static_cast<size_t>(kHop) * 2;
        }

        if (consumed > 0) {
            const size_t space = done_.size() - ready_;
            const size_t pushDone = std::min(consumed, space);
            std::memcpy(done_.data() + ready_, overlap_.data(), pushDone * sizeof(float));
            ready_ += pushDone;

            std::memmove(overlap_.data(), overlap_.data() + consumed,
                         (overlap_.size() - consumed) * sizeof(float));
            std::fill(overlap_.data() + (overlap_.size() - consumed), overlap_.data() + overlap_.size(), 0.0f);
            std::memmove(input_.data(), input_.data() + consumed, (pending_ - consumed) * sizeof(float));
            pending_ -= consumed;
        }

        if (ready_ >= chunk) {
            std::memcpy(interleaved + offset, done_.data(), chunk * sizeof(float));
            std::memmove(done_.data(), done_.data() + chunk, (ready_ - chunk) * sizeof(float));
            ready_ -= chunk;
        } else {
            std::fill(interleaved + offset, interleaved + offset + chunk, 0.0f);
        }
        offset += chunk;
    }
}

// --- LoudnessStage (libebur128) ---

LoudnessStage::~LoudnessStage() {
    if (ebur_) {
        ebur128_destroy(&ebur_);
        ebur_ = nullptr;
    }
}

void LoudnessStage::prepare(int sampleRate, int channels) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 44100;
    channels_ = std::clamp(channels, 1, 8);
    if (ebur_) {
        ebur128_destroy(&ebur_);
        ebur_ = nullptr;
    }
    // HISTOGRAM mode preallocates all block bins at init time; zero malloc during add_frames.
    ebur_ = ebur128_init(static_cast<unsigned int>(channels_),
                         static_cast<unsigned long>(sampleRate_),
                         EBUR128_MODE_S | EBUR128_MODE_I | EBUR128_MODE_HISTOGRAM);
    reset();
}

void LoudnessStage::reset() {
    lastLufs_ = -70.0;
    currentGain_ = 1.0f;
    if (ebur_) {
        ebur128_destroy(&ebur_);
        ebur_ = ebur128_init(static_cast<unsigned int>(channels_),
                             static_cast<unsigned long>(sampleRate_),
                             EBUR128_MODE_S | EBUR128_MODE_I | EBUR128_MODE_HISTOGRAM);
    }
}

void LoudnessStage::setConfig(bool autoLoudness, float staticGainDb) {
    autoLoudness_ = autoLoudness;
    staticGainDb_ = std::clamp(staticGainDb, -24.0f, 12.0f);
}

bool LoudnessStage::isBypassed() const {
    return !autoLoudness_ && std::abs(staticGainDb_) < 0.01f;
}

void LoudnessStage::process(float* interleaved, int frames) {
    if (isBypassed() || frames <= 0 || !interleaved) return;

    float targetGain = 1.0f;
    if (std::abs(staticGainDb_) >= 0.01f) {
        targetGain = std::pow(10.0f, staticGainDb_ / 20.0f);
    }

    if (autoLoudness_ && ebur_) {
        ebur128_add_frames_float(ebur_, interleaved, static_cast<size_t>(frames));
        double lufs = -70.0;
        if (ebur128_loudness_global(ebur_, &lufs) == EBUR128_SUCCESS &&
            std::isfinite(lufs) && lufs > -60.0 && lufs < 0.0) {
            lastLufs_ = lufs;
            if (std::abs(staticGainDb_) < 0.01f) {
                const double gain = std::pow(10.0, (kTargetLufs - lufs) / 20.0);
                targetGain = std::clamp(static_cast<float>(gain), kMinNormGain, kMaxNormGain);
            }
        } else {
            double stLufs = -70.0;
            if (ebur128_loudness_shortterm(ebur_, &stLufs) == EBUR128_SUCCESS &&
                std::isfinite(stLufs) && stLufs > -60.0 && stLufs < 0.0) {
                lastLufs_ = stLufs;
                if (std::abs(staticGainDb_) < 0.01f) {
                    const double gain = std::pow(10.0, (kTargetLufs - stLufs) / 20.0);
                    targetGain = std::clamp(static_cast<float>(gain), kMinNormGain, kMaxNormGain);
                }
            }
        }
    }

    const size_t total = static_cast<size_t>(frames) * channels_;
    if (std::abs(targetGain - currentGain_) < 1e-5f) {
        currentGain_ = targetGain;
        if (std::abs(currentGain_ - 1.0f) >= 1e-5f) {
            for (size_t i = 0; i < total; ++i) {
                interleaved[i] *= currentGain_;
            }
        }
        return;
    }

    // Smooth gain ramp over the block (~10 ms) to avoid steps
    const float startGain = currentGain_;
    const float alpha = std::clamp(static_cast<float>(frames) / (static_cast<float>(sampleRate_) * 0.25f), 0.01f, 1.0f);
    const float endGain = startGain + (targetGain - startGain) * alpha;
    for (int f = 0; f < frames; ++f) {
        const float g = startGain + (endGain - startGain) * (static_cast<float>(f + 1) / static_cast<float>(frames));
        for (int c = 0; c < channels_; ++c) {
            interleaved[static_cast<size_t>(f) * channels_ + c] *= g;
        }
    }
    currentGain_ = endGain;
}

// --- SleepTimerFade ---

void SleepTimerFade::configure(int sampleRate, int64_t remainingMs, int64_t fadeMs, bool endOfTrack) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 44100;
    endOfTrack_ = endOfTrack;
    finished_ = false;
    elapsedSamples_ = 0;

    if (remainingMs < 0) {
        active_ = false;
        totalSamples_ = 0;
        fadeSamples_ = 0;
        return;
    }

    active_ = true;
    totalSamples_ = std::max<int64_t>(0, remainingMs * sampleRate_ / 1000);
    const int64_t effFadeMs = (fadeMs > 0) ? fadeMs : std::min<int64_t>(5000, remainingMs);
    fadeSamples_ = std::clamp<int64_t>(effFadeMs * sampleRate_ / 1000, 1, std::max<int64_t>(1, totalSamples_));
}

void SleepTimerFade::clear() {
    active_ = false;
    endOfTrack_ = false;
    finished_ = false;
    totalSamples_ = 0;
    fadeSamples_ = 0;
    elapsedSamples_ = 0;
}

void SleepTimerFade::process(float* interleaved, int frames, int channels) {
    if (!active_ || frames <= 0 || !interleaved) return;
    if (finished_) {
        std::fill_n(interleaved, static_cast<size_t>(frames) * channels, 0.0f);
        return;
    }

    const int64_t fadeStart = totalSamples_ - fadeSamples_;
    if (elapsedSamples_ + frames <= fadeStart) {
        elapsedSamples_ += frames;
        return; // True bypass before fade window
    }

    for (int f = 0; f < frames; ++f) {
        const int64_t pos = elapsedSamples_ + f;
        float gain = 1.0f;
        if (pos >= totalSamples_) {
            gain = 0.0f;
            finished_ = true;
        } else if (pos > fadeStart) {
            gain = 1.0f - static_cast<float>(pos - fadeStart) / static_cast<float>(fadeSamples_);
        }
        if (gain < 1.0f) {
            for (int c = 0; c < channels; ++c) {
                interleaved[static_cast<size_t>(f) * channels + c] *= gain;
            }
        }
    }
    elapsedSamples_ += frames;
    if (elapsedSamples_ >= totalSamples_) {
        finished_ = true;
    }
}

// --- SafetyLimiter ---

void SafetyLimiter::prepare(int sampleRate) {
    const double sr = sampleRate > 0 ? static_cast<double>(sampleRate) : 44100.0;
    attackCoeff_ = static_cast<float>(std::exp(-1.0 / (0.0005 * sr)));
    releaseCoeff_ = static_cast<float>(std::exp(-1.0 / (0.050 * sr)));
    reset();
}

void SafetyLimiter::reset() {
    env_ = 1.0f;
}

void SafetyLimiter::process(float* interleaved, int frames, int channels) {
    if (frames <= 0 || !interleaved) return;

    // Fast path: if envelope is unity and no sample exceeds threshold, bit-exact bypass.
    const size_t total = static_cast<size_t>(frames) * channels;
    if (env_ >= 0.999999f) {
        bool over = false;
        for (size_t i = 0; i < total; ++i) {
            if (std::abs(interleaved[i]) > kThreshold) {
                over = true;
                break;
            }
        }
        if (!over) return;
    }

    for (int f = 0; f < frames; ++f) {
        float peak = 0.0f;
        for (int c = 0; c < channels; ++c) {
            peak = std::max(peak, std::abs(interleaved[static_cast<size_t>(f) * channels + c]));
        }
        const float targetGain = (peak > kThreshold) ? (kThreshold / peak) : 1.0f;
        if (targetGain < env_) {
            env_ = attackCoeff_ * env_ + (1.0f - attackCoeff_) * targetGain;
        } else {
            env_ = releaseCoeff_ * env_ + (1.0f - releaseCoeff_) * targetGain;
        }
        if (env_ < 0.999999f) {
            for (int c = 0; c < channels; ++c) {
                float& s = interleaved[static_cast<size_t>(f) * channels + c];
                s = std::clamp(s * env_, -1.0f, 1.0f);
            }
        }
    }
}

// --- DspConfig ---

bool DspConfig::isDspAllOff() const {
    if (std::abs(speed - 1.0f) >= 1e-4f) return false;
    if (std::abs(pitch - 1.0f) >= 1e-4f) return false;
    if (std::abs(preampDb) >= 0.05f) return false;
    for (float g : eq) {
        if (std::abs(g) >= 0.05f) return false;
    }
    if (reverb > 0.001f) return false;
    if (karaoke > 0.001f) return false;
    if (filterLowPassHz < 20000.0) return false;
    if (filterHighPassHz > 30.0) return false;
    if (std::abs(filterBassDb) >= 0.01) return false;
    if (loudness) return false;
    if (std::abs(normalizeGainDb) >= 0.01f) return false;
    if (std::abs(volume - 1.0f) >= 1e-4f) return false;
    return true;
}

} // namespace square
