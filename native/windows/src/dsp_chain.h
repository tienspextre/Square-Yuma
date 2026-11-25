#pragma once

#include "ring_buffer.h"

#include <cmath>
#include <limits>
#include <bungee/Bungee.h>
#include <bungee/Stream.h>
#include <ebur128.h>

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace square {

// --- Exclusive-mode format selection (pure logic, testable without hardware) ---

struct ExclusiveFormatOption {
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    uint16_t channels = 0;
    bool isFloat = false;
};

struct ExclusiveFormatRequest {
    uint32_t sourceSampleRate = 44100;
    uint16_t sourceBitsPerSample = 16;
    uint16_t sourceChannels = 2;
    bool sourceIsFloat = false;
    bool exclusiveRequested = false;
    bool dspAllOff = true;
    std::vector<ExclusiveFormatOption> deviceCapabilities; // empty = assume standard <= 384 kHz supported
};

struct ExclusiveFormatDecision {
    bool useExclusive = false;
    uint32_t targetSampleRate = 44100;
    uint16_t targetBitsPerSample = 32;
    uint16_t targetChannels = 2;
    bool targetIsFloat = true;
    std::string fallbackReason;
};

ExclusiveFormatDecision selectExclusiveFormat(const ExclusiveFormatRequest& req);

// --- Automix enums & plans (mirrors shared Kotlin planners + Phase 1 seams) ---

enum class MixStyle {
    BLEND,
    FILTER_SWEEP,
    BASS_HANDOFF,
    EQUAL_POWER,
};

enum class TransitionTier {
    SMART_BEATMATCH,
    PLAIN_CROSSFADE,
    GAPLESS,
    HARD_CUT,
};

enum class VocalDuckMode {
    NONE,
    FULL_BAND,
    MID_SIDE,
};

struct TempoPoint {
    int64_t timeMs = 0;
    float ratio = 1.0f;
};

struct TempoMap {
    std::vector<TempoPoint> points;
    // Optional Pulse-style source/target ms curves
    std::vector<double> sourceMs;
    std::vector<double> targetMs;

    bool empty() const {
        return points.empty() && sourceMs.size() < 2;
    }

    float rateAt(double elapsedMixMs, double sourceTimeMs) const;
};

struct StemMix {
    float low = 1.0f;
    float mid = 1.0f;
    float high = 1.0f;
    float vocal = 1.0f;
    float highPassHz = 0.0f;
    float lowPassHz = 0.0f;
    VocalDuckMode vocalDuckMode = VocalDuckMode::NONE;
    bool outgoingEnergyFill = false;
    bool active = false;
};

struct DeckShape {
    float gain = 1.0f;
    double lowPassHz = 20000.0;
    double highPassHz = 20.0;
    double bassDb = 0.0;
    StemMix stems{};
};

struct AutomixPlanData {
    int64_t triggerOffsetMs = 0;
    int64_t durationMs = 0;
    int64_t incomingStartMs = 0;
    bool enableBassSwap = true;
    int64_t triggerAtMs = -1;
    TransitionTier tier = TransitionTier::PLAIN_CROSSFADE;
    float incomingTempoRatio = 1.0f;
    bool hasStyle = false;
    MixStyle style = MixStyle::BLEND;
    double swapAt = 0.5;
    double swapWidth = 0.1;
    double vocalClash = 0.0;
    int64_t loopStartMs = -1;
    int64_t loopEndMs = -1;
    TempoMap tempoMap;
    VocalDuckMode vocalDuckMode = VocalDuckMode::NONE;
    bool outgoingEnergyFill = false;

    // Optional incoming target for native scheduler
    int incomingDeck = -1;
    std::string incomingUri;
    std::string incomingHeadersJson;
};

struct MixShapes {
    DeckShape outgoing;
    DeckShape incoming;
};

namespace mixmath {
constexpr double OPEN_HZ = 20000.0;
constexpr double OFF_HZ = 20.0;
constexpr double CUT_DB = -24.0;
constexpr float TEMPO_RETURN_FROM = 0.65f;

float rise(float p);
float fall(float p);
double glide(double from, double to, double amount);
MixShapes shapeForStyle(MixStyle style, float p, double swapAt, double swapWidth, double clash);
MixShapes automationForPlan(const AutomixPlanData& plan, float progress);
float incomingTempoAt(const AutomixPlanData& plan, float progress, double elapsedMixMs, double sourceMs);
} // namespace mixmath

// --- PcmLoop (float32 port of PcmLoop.kt) ---

class PcmLoop {
public:
    bool init(int channels, int64_t startFrame, int64_t endFrame, int sampleRate);
    void reset();
    bool isValid() const { return valid_; }
    bool isLooping() const { return looping_; }
    bool canStartAt(int64_t position) const { return valid_ && position <= start_ - fade_; }
    int64_t framesUntilLoop(int64_t position) const {
        return (end_ > position) ? (end_ - position) : 0;
    }

    void pass(float* interleaved, int frames, int64_t position);
    void render(float* into, int frames);

private:
    float sampleAt(int q, int c) const;

    bool valid_ = false;
    int channels_ = 2;
    int64_t start_ = 0;
    int64_t end_ = 0;
    int fade_ = 1;
    int length_ = 0;
    std::vector<float> recorded_;
    bool looping_ = false;
    int read_ = 0;
};

// --- Bungee time stretcher + pitch shifter ---

class BungeeStretcher {
public:
    void prepare(int sampleRate, int channels, int maxOutputFrames = 4096);
    void reset();

    bool isActive(float speed, float pitch) const;

    // Pulls source frames from ring, stretches to `outFrames` at `speed`/`pitch`.
    // Returns number of source frames consumed from `ring`.
    size_t renderFromRing(SpscRing<float>& ring, float* interleavedOut, int outFrames,
                          float speed, float pitch, bool& reachedEnd);

    double latencyFrames() const;

private:
    int sampleRate_ = 0;
    int channels_ = 0;
    int maxOutFrames_ = 4096;
    int maxInFrames_ = 8192;
    std::unique_ptr<Bungee::Stretcher<Bungee::Basic>> stretcher_;
    std::unique_ptr<Bungee::Stream<Bungee::Basic>> stream_;
    std::vector<float> scratchInterleaved_;
    std::vector<float> inputPlanar_;
    std::vector<float> outputPlanar_;
    std::vector<const float*> inputPtrs_;
    std::vector<float*> outputPtrs_;
    double fracInAccum_ = 0.0;
    bool stretched_ = false;
};

// --- DjFilter (port of DjFilterAudioProcessor.kt) ---

class DjFilter {
public:
    void prepare(double sampleRate, int channels);
    void reset();
    void process(float* interleaved, int frames, const DeckShape& shape);

private:
    struct Biquad {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        std::array<double, 4> st{}; // x1, x2, y1, y2

        void reset() { st.fill(0.0); }
        double run(double x) {
            const double y = b0 * x + b1 * st[0] + b2 * st[1] - a1 * st[2] - a2 * st[3];
            st[1] = st[0];
            st[0] = x;
            st[3] = st[2];
            st[2] = y;
            return y;
        }
    };

    void computeLowPass(Biquad& coeffTarget, double hz, double q) const;
    void computeHighPass(Biquad& coeffTarget, double hz, double q) const;
    void computeLowShelf(Biquad& coeffTarget, double db) const;

    double sampleRate_ = 44100.0;
    int channels_ = 2;
    static constexpr int kMaxChannels = 8;

    std::array<Biquad, kMaxChannels> lp_;
    std::array<Biquad, kMaxChannels> hp_;
    std::array<Biquad, kMaxChannels> shelf_;
    // LR4 3-band stem splitter: 8 biquads per channel (2 LP250, 2 HP250, 2 HP5000, 2 LP5000)
    std::array<std::array<Biquad, 8>, kMaxChannels> band_;

    Biquad lpCoeff_{};
    Biquad hpCoeff_{};
    Biquad shelfCoeff_{};
    Biquad lowEdgeCoeff_{};
    Biquad lowEdgeHpCoeff_{};
    Biquad highEdgeCoeff_{};
    Biquad highEdgeLpCoeff_{};

    double cachedLpHz_ = -1.0;
    double cachedHpHz_ = -1.0;
    double cachedBassDb_ = 9999.0;
    std::array<float, 4> lastStem_{1.0f, 1.0f, 1.0f, 1.0f};
};

// --- 10-band Parametric EQ with auto headroom (port of Equalizer.kt) ---

class ParametricEq {
public:
    static constexpr int kNumBands = 10;
    static constexpr std::array<int, kNumBands> kFrequencies = {
        31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };

    void prepare(int sampleRate, int channels);
    void reset();
    void setGains(const std::array<float, kNumBands>& gainsDb, float preampDb = 0.0f);
    bool isBypassed() const { return bypassed_; }
    double inputGain() const { return inputGain_; }
    static float computeHeadroomDb(const std::array<float, kNumBands>& gainsDb);

    void process(float* interleaved, int frames);

private:
    struct Band {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        std::array<double, 16> z{}; // 2 states * up to 8 channels (TDF-II)
    };

    void rebuild();

    int sampleRate_ = 48000;
    int channels_ = 2;
    std::array<float, kNumBands> gainsDb_{};
    float preampDb_ = 0.0f;
    double inputGain_ = 1.0;
    bool bypassed_ = true;
    std::array<Band, kNumBands> bands_{};
    int activeBandCount_ = 0;
};

// --- Stereo Schroeder/Freeverb Reverb (tuned to ReverbTuning.kt) ---

class StereoReverb {
public:
    void prepare(int sampleRate, int channels);
    void reset();
    void setAmount(float amount);
    bool isBypassed() const { return amount_ <= 0.001f; }
    void process(float* interleaved, int frames);

private:
    struct Comb {
        std::vector<float> buf;
        size_t idx = 0;
        float filterStore = 0.0f;
        float feedback = 0.8f;
        float damp1 = 0.2f;
        float damp2 = 0.8f;

        void resize(size_t n) { buf.assign(n, 0.0f); idx = 0; filterStore = 0.0f; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; filterStore = 0.0f; }
        float process(float input) {
            const float out = buf[idx];
            filterStore = out * damp2 + filterStore * damp1;
            buf[idx] = input + filterStore * feedback;
            if (++idx >= buf.size()) idx = 0;
            return out;
        }
    };

    struct Allpass {
        std::vector<float> buf;
        size_t idx = 0;

        void resize(size_t n) { buf.assign(n, 0.0f); idx = 0; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; }
        float process(float input) {
            const float bufOut = buf[idx];
            const float out = -input + bufOut;
            buf[idx] = input + bufOut * 0.5f;
            if (++idx >= buf.size()) idx = 0;
            return out;
        }
    };

    int sampleRate_ = 44100;
    int channels_ = 2;
    float amount_ = 0.0f;
    float wet_ = 0.0f;
    std::array<std::array<Comb, 8>, 2> combs_;
    std::array<std::array<Allpass, 4>, 2> allpasses_;
};

// --- Karaoke DUET Centre Extractor (port of CentreExtractor.kt) ---

class KaraokeExtractor {
public:
    static constexpr int kWindow = 4096;
    static constexpr int kHop = kWindow / 4;
    static constexpr int kBins = kWindow / 2 + 1;

    KaraokeExtractor();
    void prepare(int sampleRate, int channels);
    void reset();
    void setAmount(float amount) { amount_ = (amount < 0.0f) ? 0.0f : (amount > 1.0f ? 1.0f : amount); }
    bool isBypassed() const { return amount_ <= 0.001f || channels_ != 2; }
    void process(float* interleaved, int frames);

private:
    void transformFrame(size_t atInterleaved);
    void fft(float* re, float* im, bool inverse) const;

    int sampleRate_ = 44100;
    int channels_ = 2;
    float amount_ = 0.0f;

    std::array<float, kWindow> window_{};
    std::array<float, kWindow> cosTable_{};
    std::array<float, kWindow> sinTable_{};
    std::array<float, kWindow> leftRe_{};
    std::array<float, kWindow> leftIm_{};
    std::array<float, kWindow> rightRe_{};
    std::array<float, kWindow> rightIm_{};

    // Fixed preallocated circular/shift buffers (no heap alloc in process)
    static constexpr size_t kBufFrames = kWindow * 8;
    std::vector<float> input_;
    std::vector<float> overlap_;
    std::vector<float> done_;
    size_t pending_ = 0;
    size_t ready_ = 0;
};

// --- EBU R128 Loudness Stage (libebur128, real-time safe via HISTOGRAM mode) ---

class LoudnessStage {
public:
    ~LoudnessStage();
    void prepare(int sampleRate, int channels);
    void reset();
    void setConfig(bool autoLoudness, float staticGainDb);
    bool isBypassed() const;
    double lastMeasuredLufs() const { return lastLufs_; }
    float currentGainLinear() const { return currentGain_; }

    void process(float* interleaved, int frames);

private:
    int sampleRate_ = 44100;
    int channels_ = 2;
    bool autoLoudness_ = false;
    float staticGainDb_ = 0.0f;
    float currentGain_ = 1.0f;
    double lastLufs_ = -70.0;
    ebur128_state* ebur_ = nullptr;
};

// --- Sleep Timer Fade ---

class SleepTimerFade {
public:
    void configure(int sampleRate, int64_t remainingMs, int64_t fadeMs, bool endOfTrack);
    void clear();
    bool isEndOfTrack() const { return endOfTrack_; }
    bool isActive() const { return active_; }
    bool isFinished() const { return finished_; }
    void process(float* interleaved, int frames, int channels);

private:
    int sampleRate_ = 44100;
    bool active_ = false;
    bool endOfTrack_ = false;
    bool finished_ = false;
    int64_t totalSamples_ = 0;
    int64_t fadeSamples_ = 0;
    int64_t elapsedSamples_ = 0;
};

// --- Safety Limiter (true bit-exact bypass below threshold) ---

class SafetyLimiter {
public:
    void prepare(int sampleRate);
    void reset();
    void process(float* interleaved, int frames, int channels);

private:
    static constexpr float kThreshold = 0.98f;
    float env_ = 1.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
};

// --- Master DSP Config ---

struct DspConfig {
    float speed = 1.0f;
    float pitch = 1.0f;
    std::array<float, ParametricEq::kNumBands> eq{};
    float preampDb = 0.0f;
    float reverb = 0.0f;
    float karaoke = 0.0f;
    double filterLowPassHz = 20000.0;
    double filterHighPassHz = 20.0;
    double filterBassDb = 0.0;
    bool loudness = false;
    float normalizeGainDb = 0.0f;
    float volume = 0.8f;
    bool bitPerfectRequested = false;

    bool isDspAllOff() const;
};

} // namespace square
