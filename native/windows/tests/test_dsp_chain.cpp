#include "dsp_chain.h"

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <vector>

namespace {
constexpr double kPi = 3.14159265358979323846;

std::vector<float> makeStereoSine(int frames, int rate, double hz, float amp = 0.5f, float rightScale = 1.0f) {
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float v = amp * static_cast<float>(std::sin(2.0 * kPi * hz * i / rate));
        out[static_cast<size_t>(i) * 2] = v;
        out[static_cast<size_t>(i) * 2 + 1] = v * rightScale;
    }
    return out;
}

double rmsAfter(const std::vector<float>& buf, int startFrame) {
    double sum = 0.0;
    const size_t start = static_cast<size_t>(startFrame) * 2;
    const size_t count = buf.size() - start;
    for (size_t i = start; i < buf.size(); ++i) {
        sum += static_cast<double>(buf[i]) * static_cast<double>(buf[i]);
    }
    return std::sqrt(sum / static_cast<double>(count));
}

bool bitExactEqual(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}
} // namespace

int main() {
    using namespace square;
    constexpr int kRate = 44100;
    constexpr int kCh = 2;

    // 1. Bit-exact bypass checks for every DSP stage
    const auto orig = makeStereoSine(8192, kRate, 1000.0, 0.4f);

    {
        SpscRing<float> ring(orig.size() * 2);
        ring.write(orig.data(), orig.size());
        BungeeStretcher b;
        b.prepare(kRate, kCh, 4096);
        std::vector<float> out(orig.size());
        bool ended = false;
        b.renderFromRing(ring, out.data(), 8192, 1.0f, 1.0f, ended);
        assert(!ended);
        assert(bitExactEqual(orig, out));

        // Active Bungee changes signal when speed != 1.0
        ring.clear();
        ring.write(orig.data(), orig.size());
        std::vector<float> stretched(4096 * kCh);
        b.renderFromRing(ring, stretched.data(), 4096, 1.2f, 1.0f, ended);
        bool diff = false;
        for (size_t i = 0; i < stretched.size(); ++i) {
            if (std::abs(stretched[i] - orig[i]) > 1e-4f) { diff = true; break; }
        }
        assert(diff);
    }

    {
        DjFilter dj;
        dj.prepare(kRate, kCh);
        auto buf = orig;
        DeckShape neutral{};
        dj.process(buf.data(), 8192, neutral);
        assert(bitExactEqual(orig, buf));

        // Active LP, HP, bass shelf, and MID_SIDE vocal duck
        auto bassTone = makeStereoSine(16384, kRate, 50.0, 0.5f);
        dj.reset();
        DeckShape bassCut{};
        bassCut.bassDb = -24.0;
        dj.process(bassTone.data(), 16384, bassCut);
        assert(rmsAfter(bassTone, 8192) < 0.08);

        auto midCentre = makeStereoSine(16384, kRate, 1000.0, 0.4f, 1.0f);
        auto midSide = makeStereoSine(16384, kRate, 1000.0, 0.4f, -1.0f);
        DeckShape duckShape{};
        duckShape.stems = {1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, VocalDuckMode::MID_SIDE, false, true};
        dj.reset();
        dj.process(midCentre.data(), 16384, duckShape);
        dj.reset();
        dj.process(midSide.data(), 16384, duckShape);
        assert(rmsAfter(midCentre, 8192) < 0.05);
        assert(rmsAfter(midSide, 8192) > 0.25);
    }

    {
        ParametricEq eq;
        eq.prepare(kRate, kCh);
        auto buf = orig;
        eq.process(buf.data(), 8192);
        assert(bitExactEqual(orig, buf));

        // EQ headroom no-clip: +12 dB boost on 1 kHz sine near full scale (0.95) must not exceed 1.0!
        auto hot = makeStereoSine(8192, kRate, 1000.0, 0.95f);
        std::array<float, 10> gains{};
        gains[5] = 12.0f; // 1 kHz +12 dB
        eq.setGains(gains, 0.0f);
        assert(!eq.isBypassed());
        eq.process(hot.data(), 8192);
        float maxAbs = 0.0f;
        for (float s : hot) maxAbs = std::max(maxAbs, std::abs(s));
        assert(maxAbs <= 1.001f);
        assert(!bitExactEqual(orig, hot));
    }

    {
        StereoReverb rev;
        rev.prepare(kRate, kCh);
        auto buf = orig;
        rev.process(buf.data(), 8192);
        assert(bitExactEqual(orig, buf));

        rev.setAmount(0.6f);
        assert(!rev.isBypassed());
        rev.process(buf.data(), 8192);
        assert(!bitExactEqual(orig, buf));
    }

    {
        KaraokeExtractor k;
        k.prepare(kRate, kCh);
        auto buf = orig;
        k.process(buf.data(), 8192);
        assert(bitExactEqual(orig, buf));

        auto centreVoice = makeStereoSine(16384, kRate, 1000.0, 0.5f, 1.0f);
        k.setAmount(1.0f);
        assert(!k.isBypassed());
        k.process(centreVoice.data(), 16384);
        assert(rmsAfter(centreVoice, 8192) < 0.05);
    }

    {
        LoudnessStage l;
        l.prepare(kRate, kCh);
        auto buf = orig;
        l.process(buf.data(), 8192);
        assert(bitExactEqual(orig, buf));

        l.setConfig(false, -6.0f);
        assert(!l.isBypassed());
        l.process(buf.data(), 8192);
        assert(!bitExactEqual(orig, buf));

        // Integrated ebur128 measurement matches expected range on a 1 s stereo 1 kHz sine
        auto tone1s = makeStereoSine(kRate, kRate, 1000.0, 0.5f);
        ebur128_state* est = ebur128_init(kCh, kRate, EBUR128_MODE_I | EBUR128_MODE_HISTOGRAM);
        assert(est != nullptr);
        ebur128_add_frames_float(est, tone1s.data(), kRate);
        double measured = -70.0;
        assert(ebur128_loudness_global(est, &measured) == EBUR128_SUCCESS);
        ebur128_destroy(&est);
        assert(std::isfinite(measured) && measured > -20.0 && measured < -3.0);
    }

    {
        SleepTimerFade sf;
        auto buf = orig;
        sf.process(buf.data(), 8192, kCh);
        assert(bitExactEqual(orig, buf));

        sf.configure(kRate, 100, 100, false);
        sf.process(buf.data(), 8192, kCh);
        assert(!bitExactEqual(orig, buf));
        assert(sf.isFinished());
    }

    {
        SafetyLimiter lim;
        lim.prepare(kRate);
        auto buf = orig; // peak 0.4 <= 0.98 threshold -> bit-exact bypass
        lim.process(buf.data(), 8192, kCh);
        assert(bitExactEqual(orig, buf));

        auto loud = makeStereoSine(4096, kRate, 1000.0, 2.0f);
        lim.process(loud.data(), 4096, kCh);
        float peak = 0.0f;
        for (size_t i = 512 * 2; i < loud.size(); ++i) {
            peak = std::max(peak, std::abs(loud[i]));
        }
        assert(peak <= 1.0f);
    }

    // 2. MixStyle gain curves sum sensibly
    for (MixStyle st : {MixStyle::BLEND, MixStyle::FILTER_SWEEP, MixStyle::BASS_HANDOFF, MixStyle::EQUAL_POWER}) {
        const auto sStart = mixmath::shapeForStyle(st, 0.0f, 0.5, 0.1, 0.0);
        const auto sMid = mixmath::shapeForStyle(st, 0.5f, 0.5, 0.1, 0.0);
        const auto sEnd = mixmath::shapeForStyle(st, 1.0f, 0.5, 0.1, 0.0);
        assert(sStart.outgoing.gain > 0.99f && sStart.incoming.gain < 0.01f);
        assert(sEnd.outgoing.gain < 0.01f && sEnd.incoming.gain > 0.99f);
        const float sumPowerMid = sMid.outgoing.gain * sMid.outgoing.gain + sMid.incoming.gain * sMid.incoming.gain;
        assert(sumPowerMid >= 0.4f && sumPowerMid <= 1.6f);
        if (st != MixStyle::EQUAL_POWER) {
            assert(sStart.outgoing.bassDb > -0.1 && sStart.incoming.bassDb <= -23.9);
            assert(sEnd.outgoing.bassDb <= -23.9 && sEnd.incoming.bassDb > -0.1);
        }
    }

    // 3. Tempo return to 1.0 over last 35% & TempoMap
    {
        AutomixPlanData plan{};
        plan.tier = TransitionTier::SMART_BEATMATCH;
        plan.incomingTempoRatio = 1.12f;
        assert(std::abs(mixmath::incomingTempoAt(plan, 0.0f, 0.0, 0.0) - 1.12f) < 1e-5f);
        assert(std::abs(mixmath::incomingTempoAt(plan, 0.65f, 0.0, 0.0) - 1.12f) < 1e-5f);
        const float mid = mixmath::incomingTempoAt(plan, 0.825f, 0.0, 0.0);
        assert(mid > 1.04f && mid < 1.08f);
        assert(std::abs(mixmath::incomingTempoAt(plan, 1.0f, 0.0, 0.0) - 1.0f) < 1e-5f);

        plan.tempoMap.points = {{0, 1.10f}, {5000, 1.05f}, {10000, 1.00f}};
        assert(std::abs(mixmath::incomingTempoAt(plan, 0.0f, 0.0, 0.0) - 1.10f) < 1e-5f);
        assert(std::abs(mixmath::incomingTempoAt(plan, 0.5f, 5000.0, 0.0) - 1.05f) < 1e-5f);
        assert(std::abs(mixmath::incomingTempoAt(plan, 1.0f, 10000.0, 0.0) - 1.00f) < 1e-5f);
    }

    // 4. PcmLoop
    {
        PcmLoop loop;
        assert(loop.init(2, 1000, 3000, kRate));
        assert(loop.canStartAt(0));
        std::vector<float> blk(4000 * 2, 0.25f);
        loop.pass(blk.data(), 4000, 0);
        assert(loop.isLooping());
        std::vector<float> rendered(512 * 2, 0.0f);
        loop.render(rendered.data(), 512);
        assert(std::abs(rendered[0] - 0.25f) < 1e-5f);
    }

    std::cout << "test_dsp_chain passed\n";
    return 0;
}
