#include "engine.h"

#include "audio_analysis.h"
#include "resampler.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#ifdef _WIN32
#include <windows.h>
#include <avrt.h>
#endif

namespace square {

namespace {

std::string deviceIdToHex(const ma_device_id& id) {
    // Stable hex encoding of ma_device_id bytes so device IDs survive JSON round-trips.
    const auto* bytes = reinterpret_cast<const uint8_t*>(&id);
    static constexpr char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(sizeof(ma_device_id) * 2);
    for (size_t i = 0; i < sizeof(ma_device_id); ++i) {
        out.push_back(kHex[(bytes[i] >> 4) & 0xF]);
        out.push_back(kHex[bytes[i] & 0xF]);
    }
    return out;
}

bool hexToDeviceId(const std::string& hex, ma_device_id& outId) {
    if (hex.size() != sizeof(ma_device_id) * 2) return false;
    auto fromHex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    auto* bytes = reinterpret_cast<uint8_t*>(&outId);
    for (size_t i = 0; i < sizeof(ma_device_id); ++i) {
        const int hi = fromHex(hex[i * 2]);
        const int lo = fromHex(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        bytes[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

ma_format maFormatForDecision(const ExclusiveFormatDecision& d) {
    if (d.targetIsFloat) return ma_format_f32;
    if (d.targetBitsPerSample <= 16) return ma_format_s16;
    if (d.targetBitsPerSample <= 24) return ma_format_s24;
    return ma_format_s32;
}

MixStyle parseMixStyle(const std::string& s) {
    if (s == "FILTER_SWEEP") return MixStyle::FILTER_SWEEP;
    if (s == "BASS_HANDOFF") return MixStyle::BASS_HANDOFF;
    if (s == "EQUAL_POWER") return MixStyle::EQUAL_POWER;
    return MixStyle::BLEND;
}

TransitionTier parseTier(const std::string& s) {
    if (s == "SMART_BEATMATCH") return TransitionTier::SMART_BEATMATCH;
    if (s == "GAPLESS") return TransitionTier::GAPLESS;
    if (s == "HARD_CUT") return TransitionTier::HARD_CUT;
    return TransitionTier::PLAIN_CROSSFADE;
}

VocalDuckMode parseVocalDuckMode(const std::string& s) {
    if (s == "FULL_BAND") return VocalDuckMode::FULL_BAND;
    if (s == "MID_SIDE") return VocalDuckMode::MID_SIDE;
    return VocalDuckMode::NONE;
}

const char* maFormatName(ma_format f) {
    switch (f) {
    case ma_format_u8: return "u8";
    case ma_format_s16: return "s16";
    case ma_format_s24: return "s24";
    case ma_format_s32: return "s32";
    case ma_format_f32: return "f32";
    default: return "unknown";
    }
}

} // namespace

NativeAudioEngine::NativeAudioEngine()
    : cmdRing_(256), rtEventRing_(128) {}

NativeAudioEngine::~NativeAudioEngine() {
    shutdown();
}

bool NativeAudioEngine::init(bool headless) {
    if (initialized_) return true;
    headless_ = headless;

    if (!headless_) {
        ma_context_config ctxCfg = ma_context_config_init();
        if (ma_context_init(nullptr, 0, &ctxCfg, &context_) == MA_SUCCESS) {
            contextInit_ = true;
        } else {
            // Fall back to null backend so engine still initializes on headless machines.
            ma_backend nullBackend[] = {ma_backend_null};
            if (ma_context_init(nullBackend, 1, &ctxCfg, &context_) == MA_SUCCESS) {
                contextInit_ = true;
            }
        }
    }

    deviceSampleRate_ = 48000;
    deviceChannels_ = 2;
    deviceFormat_ = ma_format_f32;
    openDeviceForCurrentState();

    // Preallocate audio-thread buffers (no allocations ever inside renderAudio).
    bufOut_.assign(kMaxBlockFrames * 8, 0.0f);
    bufIn_.assign(kMaxBlockFrames * 8, 0.0f);
    bufMix_.assign(kMaxBlockFrames * 8, 0.0f);
    bufRaw_.assign(kMaxBlockFrames * 8 * 4, 0);

    for (auto& d : decks_) {
        d.floatRing.reset(static_cast<size_t>(kRingSeconds * deviceSampleRate_ * deviceChannels_));
        d.rawRing.reset(static_cast<size_t>(4.0 * 192000 * 2 * 4));
        d.bungee.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_, kMaxBlockFrames);
        d.djFilter.prepare(static_cast<double>(deviceSampleRate_), deviceChannels_);
    }

    eq_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    reverb_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    karaoke_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    loudness_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    limiter_.prepare(static_cast<int>(deviceSampleRate_));

    evStop_.store(false);
    evThread_ = std::thread([this]() { eventLoop(); });

    smtc_ = createSmtcController();
    smtc_->start([this](const std::string& btn) {
        pushEvent({EventType::SMTC_BUTTON, 0, btn});
    });

    initialized_ = true;
    return true;
}

void NativeAudioEngine::shutdown() {
    if (!initialized_) return;
    initialized_ = false;

    if (smtc_) {
        smtc_->stop();
        smtc_.reset();
    }

    for (int i = 0; i < kDeckCount; ++i) {
        decks_[i].playing.store(false);
        decks_[i].stopDecode.store(true);
        decks_[i].decodeCv.notify_all();
        if (decks_[i].decodeThread.joinable()) {
            decks_[i].decodeThread.join();
        }
    }

    closeDevice();
    if (contextInit_) {
        ma_context_uninit(&context_);
        contextInit_ = false;
    }

    evStop_.store(true);
    evCv_.notify_all();
    if (evThread_.joinable()) {
        evThread_.join();
    }
}

bool NativeAudioEngine::openDeviceForCurrentState() {
    std::lock_guard<std::mutex> lock(deviceMu_);
    closeDevice();

    if (headless_ || !contextInit_) {
        exclusiveActive_ = false;
        deviceSampleRate_ = 48000;
        deviceChannels_ = 2;
        deviceFormat_ = ma_format_f32;
        deviceName_ = "Null Sink";
        return true;
    }

    const int active = activeDeck_.load();
    const bool excReq = (outputMode_ == "exclusive");
    bool dspAllOff = false;
    {
        std::lock_guard<std::mutex> cl(controlMu_);
        dspAllOff = controlDsp_.isDspAllOff() && !mixActive_.load() && !planArmed_.load();
    }

    ExclusiveFormatRequest req;
    req.exclusiveRequested = excReq;
    req.dspAllOff = dspAllOff;
    if (decks_[active].hasTrack) {
        req.sourceSampleRate = decks_[active].format.sampleRate;
        req.sourceBitsPerSample = decks_[active].format.bitsPerSample;
        req.sourceChannels = decks_[active].format.channels;
        req.sourceIsFloat = (decks_[active].format.nativeFormat == SampleFormat::F32);
    }

    ExclusiveFormatDecision dec = selectExclusiveFormat(req);
    fallbackReason_ = dec.fallbackReason;

    ma_device_id devId{};
    bool hasDevId = false;
    if (!selectedDeviceId_.empty()) {
        hasDevId = hexToDeviceId(selectedDeviceId_, devId);
    }

    auto tryInit = [&](ma_share_mode shareMode, ma_format fmt, ma_uint32 ch, ma_uint32 sr) -> ma_result {
        ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
        cfg.playback.pDeviceID = hasDevId ? &devId : nullptr;
        cfg.playback.shareMode = shareMode;
        cfg.playback.format = fmt;
        cfg.playback.channels = ch;
        cfg.sampleRate = sr;
        cfg.dataCallback = &NativeAudioEngine::maDataCallback;
        cfg.notificationCallback = &NativeAudioEngine::maNotificationCallback;
        cfg.pUserData = this;
        return ma_device_init(&context_, &cfg, &device_);
    };

    ma_result rc = MA_ERROR;
    if (dec.useExclusive) {
        const ma_format excFmt = maFormatForDecision(dec);
        rc = tryInit(ma_share_mode_exclusive, excFmt, dec.targetChannels, dec.targetSampleRate);
        if (rc == MA_SUCCESS) {
            exclusiveActive_ = true;
            deviceSampleRate_ = device_.sampleRate;
            deviceChannels_ = static_cast<uint16_t>(device_.playback.channels);
            deviceFormat_ = device_.playback.format;
            deviceName_ = device_.playback.name;
            deviceOpen_ = true;
            return ma_device_start(&device_) == MA_SUCCESS;
        }
        fallbackReason_ = "Exclusive mode refused by device (ma_result=" + std::to_string(static_cast<int>(rc)) + "); fell back to Shared float32";
    }

    exclusiveActive_ = false;
    rc = tryInit(ma_share_mode_shared, ma_format_f32, 2, 0);
    if (rc != MA_SUCCESS) {
        // Fallback to null backend if system audio device unavailable
        deviceSampleRate_ = 48000;
        deviceChannels_ = 2;
        deviceFormat_ = ma_format_f32;
        deviceName_ = "Null Sink";
        if (fallbackReason_.empty()) {
            fallbackReason_ = "No physical audio output available; using null sink";
        }
        return false;
    }

    deviceOpen_ = true;
    deviceSampleRate_ = device_.sampleRate > 0 ? device_.sampleRate : 48000;
    deviceChannels_ = device_.playback.channels > 0 ? static_cast<uint16_t>(device_.playback.channels) : 2;
    deviceFormat_ = device_.playback.format;
    deviceName_ = device_.playback.name;

    // Ensure DSP processors match shared device format
    for (auto& d : decks_) {
        d.bungee.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_, kMaxBlockFrames);
        d.djFilter.prepare(static_cast<double>(deviceSampleRate_), deviceChannels_);
    }
    eq_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    reverb_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    karaoke_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    loudness_.prepare(static_cast<int>(deviceSampleRate_), deviceChannels_);
    limiter_.prepare(static_cast<int>(deviceSampleRate_));

    ma_device_start(&device_);
    return true;
}

void NativeAudioEngine::closeDevice() {
    if (deviceOpen_) {
        deviceOpen_ = false; // Set first so notificationCallback knows close is intentional
        ma_device_uninit(&device_);
    }
}

void NativeAudioEngine::reopenDeviceIfNeeded() {
    openDeviceForCurrentState();
}

void NativeAudioEngine::maDataCallback(ma_device* pDevice, void* pOutput, const void* /*pInput*/, ma_uint32 frameCount) {
#ifdef _WIN32
    thread_local bool mmcssDone = false;
    if (!mmcssDone) {
        mmcssDone = true;
        DWORD taskIndex = 0;
        AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
    }
#endif
    auto* self = static_cast<NativeAudioEngine*>(pDevice->pUserData);
    if (!self || !pOutput) return;
    self->renderAudio(pOutput, frameCount);
}

void NativeAudioEngine::maNotificationCallback(const ma_device_notification* pNotification) {
    if (!pNotification || !pNotification->pDevice) return;
    auto* self = static_cast<NativeAudioEngine*>(pNotification->pDevice->pUserData);
    if (!self || !self->deviceOpen_) return;

    if (pNotification->type == ma_device_notification_type_stopped ||
        pNotification->type == ma_device_notification_type_rerouted) {
        for (int i = 0; i < kDeckCount; ++i) {
            self->decks_[i].playing.store(false);
        }
        self->pushEvent({EventType::DEVICE_LOST, 0,
                         pNotification->type == ma_device_notification_type_rerouted ? "rerouted" : "stopped"});
    }
}

bool NativeAudioEngine::deckLoad(int deck, const std::string& uri, const std::string& headersJson, int64_t startMs) {
    if (deck < 0 || deck >= kDeckCount) return false;
    auto& d = decks_[deck];

    d.playing.store(false);
    d.stopDecode.store(true);
    d.decodeCv.notify_all();
    if (d.decodeThread.joinable()) {
        d.decodeThread.join();
    }

    d.uri = uri;
    d.headersJson = headersJson;
    d.hasTrack = false;
    d.eofDecoded.store(false);
    d.errorFlag.store(false);
    d.endedNotified.store(false);
    d.lastError.clear();
    {
        std::lock_guard<std::mutex> lk(d.loopMu);
        d.loop.reset();
    }
    d.floatRing.reset(static_cast<size_t>(kRingSeconds * deviceSampleRate_ * deviceChannels_));
    d.rawRing.clear();
    d.bungee.reset();
    d.djFilter.reset();

    const int64_t clampedStart = std::max<int64_t>(0, startMs);
    d.sourceFramePos.store(clampedStart * static_cast<int64_t>(d.sourceSampleRate.load()) / 1000LL);
    d.stopDecode.store(false);

    // Probe header synchronously so format info and initial readiness are immediate.
    TrackFormatInfo info{};
    std::string probeErr;
    if (!probeAudioUri(uri, headersJson, info, probeErr)) {
        d.errorFlag.store(true);
        d.lastError = probeErr;
        pushEvent({EventType::DECK_ERROR, deck, probeErr});
        return false;
    }

    d.format = info;
    d.hasTrack = true;
    d.sourceSampleRate.store(info.sampleRate);
    d.sourceChannels.store(info.channels);
    d.sourceFramePos.store(clampedStart * static_cast<int64_t>(info.sampleRate) / 1000LL);

    if (deck == activeDeck_.load() && (outputMode_ == "exclusive") != exclusiveActive_) {
        reopenDeviceIfNeeded();
    }

    d.decodeThread = std::thread([this, deck, clampedStart]() { decodeLoop(deck, clampedStart); });
    return true;
}

void NativeAudioEngine::deckPlay(int deck) {
    if (deck < 0 || deck >= kDeckCount) return;
    if (outputMode_ == "exclusive" && !deviceOpen_) {
        openDeviceForCurrentState();
    }
    Command cmd{};
    cmd.type = CmdType::DECK_PLAY;
    cmd.deck = deck;
    cmdRing_.pushOne(cmd);
    if (headless_) {
        decks_[deck].playing.store(true);
    }
}

void NativeAudioEngine::deckPause(int deck) {
    if (deck < 0 || deck >= kDeckCount) return;
    Command cmd{};
    cmd.type = CmdType::DECK_PAUSE;
    cmd.deck = deck;
    cmdRing_.pushOne(cmd);
    if (headless_) {
        decks_[deck].playing.store(false);
    }
    if (exclusiveActive_ && !decks_[0].playing.load() && !decks_[1].playing.load()) {
        // Spec §3.3: device closed on pause/stop in Exclusive mode.
        std::lock_guard<std::mutex> lock(deviceMu_);
        closeDevice();
    }
}

void NativeAudioEngine::deckSeek(int deck, int64_t ms) {
    if (deck < 0 || deck >= kDeckCount) return;
    auto& d = decks_[deck];
    if (!d.hasTrack) return;
    const bool wasPlaying = d.playing.load();
    deckLoad(deck, d.uri, d.headersJson, ms);
    if (wasPlaying) {
        deckPlay(deck);
    }
}

bool NativeAudioEngine::setOutput(const std::string& mode, const std::string& deviceId) {
    outputMode_ = (mode == "exclusive") ? "exclusive" : "shared";
    selectedDeviceId_ = deviceId;
    return openDeviceForCurrentState();
}

std::string NativeAudioEngine::listDevicesJson() {
    nlohmann::json arr = nlohmann::json::array();
    if (!contextInit_) {
        arr.push_back({
            {"id", "null"},
            {"name", "Null Sink"},
            {"isDefault", true},
            {"maxChannels", 2},
        });
        return arr.dump();
    }

    ma_device_info* playbackInfos = nullptr;
    ma_uint32 playbackCount = 0;
    if (ma_context_get_devices(&context_, &playbackInfos, &playbackCount, nullptr, nullptr) == MA_SUCCESS) {
        for (ma_uint32 i = 0; i < playbackCount; ++i) {
            ma_device_info detailed{};
            ma_uint32 maxCh = 2;
            if (ma_context_get_device_info(&context_, ma_device_type_playback, &playbackInfos[i].id, &detailed) == MA_SUCCESS) {
                for (ma_uint32 f = 0; f < detailed.nativeDataFormatCount; ++f) {
                    maxCh = std::max(maxCh, detailed.nativeDataFormats[f].channels);
                }
            }
            arr.push_back({
                {"id", deviceIdToHex(playbackInfos[i].id)},
                {"name", std::string(playbackInfos[i].name)},
                {"isDefault", playbackInfos[i].isDefault != 0},
                {"maxChannels", maxCh},
            });
        }
    }
    return arr.dump();
}

void NativeAudioEngine::setDspJson(const std::string& jsonStr) {
    auto j = nlohmann::json::parse(jsonStr, nullptr, false);
    if (!j.is_object()) return;

    DspConfig cfg{};
    cfg.speed = std::clamp(j.value("speed", 1.0f), 0.25f, 4.0f);
    cfg.pitch = std::clamp(j.value("pitch", 1.0f), 0.25f, 4.0f);
    cfg.preampDb = std::clamp(j.value("preampDb", 0.0f), -12.0f, 12.0f);
    if (j.contains("eq") && j["eq"].is_array()) {
        const auto& eqArr = j["eq"];
        for (size_t i = 0; i < ParametricEq::kNumBands && i < eqArr.size(); ++i) {
            cfg.eq[i] = std::clamp(eqArr[i].get<float>(), -12.0f, 12.0f);
        }
    }
    cfg.reverb = std::clamp(j.value("reverb", 0.0f), 0.0f, 1.0f);
    cfg.karaoke = std::clamp(j.value("karaoke", 0.0f), 0.0f, 1.0f);
    if (j.contains("filter") && j["filter"].is_object()) {
        const auto& flt = j["filter"];
        cfg.filterLowPassHz = flt.value("lowPassHz", 20000.0);
        cfg.filterHighPassHz = flt.value("highPassHz", 20.0);
        cfg.filterBassDb = flt.value("bassDb", 0.0);
    }
    cfg.loudness = j.value("loudness", false);
    cfg.normalizeGainDb = std::clamp(j.value("normalizeGainDb", 0.0f), -24.0f, 12.0f);
    cfg.volume = std::clamp(j.value("volume", 0.8f), 0.0f, 1.0f);
    cfg.bitPerfectRequested = j.value("bitPerfect", false);

    {
        std::lock_guard<std::mutex> lock(controlMu_);
        controlDsp_ = cfg;
    }

    Command cmd{};
    cmd.type = CmdType::SET_DSP;
    cmd.dsp = cfg;
    cmdRing_.pushOne(cmd);

    // Re-evaluate Exclusive <-> Shared if bit-perfect state changed
    if (outputMode_ == "exclusive") {
        const bool shouldExclusive = cfg.isDspAllOff() && !mixActive_.load();
        if (shouldExclusive != exclusiveActive_) {
            reopenDeviceIfNeeded();
        }
    }
}

bool NativeAudioEngine::scheduleMixJson(const std::string& planJson) {
    auto j = nlohmann::json::parse(planJson, nullptr, false);
    if (!j.is_object()) return false;

    AutomixPlanData plan{};
    plan.triggerOffsetMs = j.value("triggerOffsetMs", int64_t{0});
    plan.durationMs = std::max<int64_t>(0, j.value("durationMs", int64_t{0}));
    plan.incomingStartMs = std::max<int64_t>(0, j.value("incomingStartMs", int64_t{0}));
    plan.enableBassSwap = j.value("enableBassSwap", true);
    plan.triggerAtMs = j.contains("triggerAtMs") && !j["triggerAtMs"].is_null()
        ? j["triggerAtMs"].get<int64_t>()
        : -1;
    plan.tier = parseTier(j.value("tier", "PLAIN_CROSSFADE"));
    plan.incomingTempoRatio = j.value("incomingTempoRatio", 1.0f);
    if (j.contains("style") && j["style"].is_string()) {
        plan.hasStyle = true;
        plan.style = parseMixStyle(j["style"].get<std::string>());
    }
    plan.swapAt = j.value("swapAt", 0.5);
    plan.swapWidth = j.value("swapWidth", 0.1);
    plan.vocalClash = j.value("vocalClash", 0.0);
    plan.loopStartMs = j.contains("loopStartMs") && !j["loopStartMs"].is_null()
        ? j["loopStartMs"].get<int64_t>()
        : -1;
    plan.loopEndMs = j.contains("loopEndMs") && !j["loopEndMs"].is_null()
        ? j["loopEndMs"].get<int64_t>()
        : -1;
    plan.vocalDuckMode = parseVocalDuckMode(j.value("vocalDuckMode", "NONE"));
    plan.outgoingEnergyFill = j.value("outgoingEnergyFill", false);

    if (j.contains("tempoMap") && j["tempoMap"].is_object()) {
        const auto& tm = j["tempoMap"];
        if (tm.contains("points") && tm["points"].is_array()) {
            for (const auto& pt : tm["points"]) {
                plan.tempoMap.points.push_back({
                    pt.value("timeMs", int64_t{0}),
                    pt.value("ratio", 1.0f),
                });
            }
        }
        if (tm.contains("sourceMs") && tm.contains("targetMs")) {
            plan.tempoMap.sourceMs = tm["sourceMs"].get<std::vector<double>>();
            plan.tempoMap.targetMs = tm["targetMs"].get<std::vector<double>>();
        }
    }

    plan.incomingDeck = j.value("incomingDeck", -1);
    plan.incomingUri = j.value("incomingUri", "");
    plan.incomingHeadersJson = j.value("incomingHeadersJson", "");

    const int outIdx = activeDeck_.load();
    const int inIdx = (plan.incomingDeck == 0 || plan.incomingDeck == 1) ? plan.incomingDeck : (1 - outIdx);

    if (!plan.incomingUri.empty()) {
        if (!deckLoad(inIdx, plan.incomingUri, plan.incomingHeadersJson, plan.incomingStartMs)) {
            return false;
        }
    }

    // Arm PcmLoop on outgoing deck if requested
    if (plan.loopStartMs >= 0 && plan.loopEndMs > plan.loopStartMs) {
        auto& outDeck = decks_[outIdx];
        const int sr = static_cast<int>(outDeck.sourceSampleRate.load());
        const int ch = static_cast<int>(outDeck.sourceChannels.load());
        std::lock_guard<std::mutex> lk(outDeck.loopMu);
        outDeck.loop.init(ch, plan.loopStartMs * sr / 1000LL, plan.loopEndMs * sr / 1000LL, sr);
    }

    {
        std::lock_guard<std::mutex> lock(planMu_);
        const int nextSlot = 1 - stagedPlanSlot_.load();
        planSlots_[nextSlot] = plan;
        stagedPlanSlot_.store(nextSlot);
    }

    if (exclusiveActive_) {
        reopenDeviceIfNeeded();
    }

    Command cmd{};
    cmd.type = CmdType::SCHEDULE_MIX;
    cmd.deck = outIdx;
    cmd.arg0 = inIdx;
    cmdRing_.pushOne(cmd);
    return true;
}

void NativeAudioEngine::setSleepTimer(int64_t ms, int64_t fadeMs, bool endOfTrack) {
    Command cmd{};
    if (ms < 0 && !endOfTrack) {
        cmd.type = CmdType::CLEAR_SLEEP_TIMER;
    } else {
        cmd.type = CmdType::SET_SLEEP_TIMER;
        cmd.arg0 = ms;
        cmd.arg1 = fadeMs;
        cmd.flag = endOfTrack;
    }
    cmdRing_.pushOne(cmd);
}

int64_t NativeAudioEngine::positionMs(int deck) const {
    if (deck < 0 || deck >= kDeckCount) return 0;
    const uint32_t sr = decks_[deck].sourceSampleRate.load();
    if (sr == 0) return 0;
    return decks_[deck].sourceFramePos.load() * 1000LL / static_cast<int64_t>(sr);
}

std::string NativeAudioEngine::statusJson() const {
    const int active = activeDeck_.load();
    const auto& d = decks_[active];
    nlohmann::json j = {
        {"initialized", initialized_},
        {"activeDeck", active},
        {"playing", d.playing.load()},
        {"positionMs", positionMs(active)},
        {"outputMode", outputMode_},
        {"exclusiveActive", exclusiveActive_},
        {"fallbackReason", fallbackReason_},
        {"deviceName", deviceName_},
        {"deviceSampleRate", deviceSampleRate_},
        {"deviceChannels", deviceChannels_},
        {"deviceFormat", maFormatName(deviceFormat_)},
        {"sourceCodec", d.hasTrack ? d.format.codec : ""},
        {"sourceSampleRate", d.hasTrack ? d.format.sampleRate : 0},
        {"sourceBitsPerSample", d.hasTrack ? d.format.bitsPerSample : 0},
        {"sourceChannels", d.hasTrack ? d.format.channels : 0},
        {"mixing", mixActive_.load()},
        {"mixProgress", mixProgressAtomic_.load()},
        {"lufs", loudness_.lastMeasuredLufs()},
        {"normalizeGain", loudness_.currentGainLinear()},
        {"bufferedMs", static_cast<int64_t>(d.floatRing.availableRead() * 1000ULL /
                                            std::max<size_t>(1, deviceSampleRate_ * deviceChannels_))},
    };
    return j.dump();
}

void NativeAudioEngine::smtcUpdate(const std::string& metadataJson, const std::vector<uint8_t>& artwork) {
    if (smtc_) {
        smtc_->update(metadataJson, artwork);
    }
}

void NativeAudioEngine::setCallbacks(EngineCallbacks cb) {
    std::lock_guard<std::mutex> lock(cbMu_);
    callbacks_ = std::move(cb);
}

std::string NativeAudioEngine::probeTrackJson(const std::string& uri, const std::string& headersJson) {
    TrackFormatInfo info{};
    std::string err;
    if (!probeAudioUri(uri, headersJson, info, err)) {
        return nlohmann::json{{"error", err}}.dump();
    }
    return nlohmann::json{
        {"codec", info.codec},
        {"sampleRate", info.sampleRate},
        {"channels", info.channels},
        {"bitsPerSample", info.bitsPerSample},
        {"totalFrames", info.totalFrames},
        {"durationMs", info.durationMs},
        {"lossless", info.lossless},
    }.dump();
}

std::vector<float> NativeAudioEngine::decodeToFloat(
    const std::string& uri, const std::string& headersJson,
    int64_t fromMs, int64_t durationMs, int targetRate, int targetChannels) {
    std::vector<float> out;
    std::string err;
    decodeAudioToFloat(uri, headersJson, fromMs, durationMs,
                       static_cast<uint32_t>(std::max(0, targetRate)),
                       static_cast<uint16_t>(std::max(0, targetChannels)),
                       out, err);
    return out;
}

void NativeAudioEngine::decodeLoop(int deckIdx, int64_t startMs) {
    auto& d = decks_[deckIdx];
    std::string err;
    auto stream = createInputStream(d.uri, d.headersJson, err);
    if (!stream) {
        d.errorFlag.store(true);
        d.lastError = err;
        pushEvent({EventType::DECK_ERROR, deckIdx, err});
        return;
    }
    auto dec = createDecoder(std::move(stream), d.uri, err);
    if (!dec) {
        d.errorFlag.store(true);
        d.lastError = err;
        pushEvent({EventType::DECK_ERROR, deckIdx, err});
        return;
    }

    const TrackFormatInfo fmt = dec->format();
    int64_t sourceFrame = 0;
    if (startMs > 0) {
        sourceFrame = startMs * static_cast<int64_t>(fmt.sampleRate) / 1000LL;
        dec->seekFrame(sourceFrame);
    }

    const uint32_t dstRate = deviceSampleRate_ > 0 ? deviceSampleRate_ : fmt.sampleRate;
    const uint16_t dstCh = deviceChannels_ > 0 ? deviceChannels_ : 2;
    const bool needResample = (dstRate != fmt.sampleRate);

    ma_resampler resampler{};
    bool resamplerInit = false;
    if (needResample) {
        ma_resampler_config rcfg = ma_resampler_config_init(
            ma_format_f32, dstCh, fmt.sampleRate, dstRate, ma_resample_algorithm_linear);
        if (ma_resampler_init(&rcfg, nullptr, &resampler) == MA_SUCCESS) {
            resamplerInit = true;
        }
    }

    constexpr size_t kChunkFrames = 2048;
    std::vector<float> srcBuf(kChunkFrames * fmt.channels);
    std::vector<float> chBuf(kChunkFrames * dstCh);
    std::vector<float> rsBuf(kChunkFrames * dstCh * 4);
    std::vector<uint8_t> rawBuf(kChunkFrames * fmt.channels * 4);

    const size_t rawFrameBytes = (fmt.bitsPerSample / 8) * fmt.channels;

    while (!d.stopDecode.load()) {
        // Pre-buffer up to ring capacity (~12 s float / ~4 s raw)
        const bool needRaw = (outputMode_ == "exclusive");
        const size_t floatAvailWrite = d.floatRing.availableWrite() / dstCh;
        if (floatAvailWrite < kChunkFrames * 4) {
            std::unique_lock<std::mutex> lk(d.decodeMu);
            d.decodeCv.wait_for(lk, std::chrono::milliseconds(10), [&]() {
                return d.stopDecode.load() || (d.floatRing.availableWrite() / dstCh >= kChunkFrames * 4);
            });
            continue;
        }

        if (needRaw && d.rawRing.availableWrite() >= kChunkFrames * rawFrameBytes) {
            const size_t gotRaw = dec->readRawFrames(rawBuf.data(), kChunkFrames);
            if (gotRaw == 0) {
                d.eofDecoded.store(true);
                break;
            }
            d.rawRing.write(rawBuf.data(), gotRaw * rawFrameBytes);
            ma_pcm_convert(srcBuf.data(), ma_format_f32, rawBuf.data(),
                           static_cast<ma_format>(
                               fmt.nativeFormat == SampleFormat::S16 ? ma_format_s16 :
                               fmt.nativeFormat == SampleFormat::S24 ? ma_format_s24 :
                               fmt.nativeFormat == SampleFormat::S32 ? ma_format_s32 :
                               fmt.nativeFormat == SampleFormat::U8 ? ma_format_u8 : ma_format_f32),
                           gotRaw * fmt.channels, ma_dither_mode_none);
            sourceFrame += static_cast<int64_t>(gotRaw);
            // Also keep floatRing populated in case user toggles DSP/Shared mid-track
            if (fmt.channels == dstCh && !needResample) {
                d.floatRing.write(srcBuf.data(), gotRaw * dstCh);
            }
            continue;
        }

        size_t got = 0;
        bool loopingNow = false;
        {
            std::lock_guard<std::mutex> lk(d.loopMu);
            loopingNow = d.loop.isValid() && d.loop.isLooping();
            if (loopingNow) {
                d.loop.render(srcBuf.data(), static_cast<int>(kChunkFrames));
                got = kChunkFrames;
            }
        }
        if (!loopingNow) {
            got = dec->readFloatFrames(srcBuf.data(), kChunkFrames);
            if (got == 0) {
                d.eofDecoded.store(true);
                break;
            }
            {
                std::lock_guard<std::mutex> lk(d.loopMu);
                if (d.loop.isValid()) {
                    d.loop.pass(srcBuf.data(), static_cast<int>(got), sourceFrame);
                }
            }
            sourceFrame += static_cast<int64_t>(got);
        }

        const float* chPtr = srcBuf.data();
        if (fmt.channels != dstCh) {
            for (size_t i = 0; i < got; ++i) {
                if (dstCh == 2 && fmt.channels == 1) {
                    chBuf[i * 2] = srcBuf[i];
                    chBuf[i * 2 + 1] = srcBuf[i];
                } else {
                    for (uint16_t c = 0; c < dstCh; ++c) {
                        chBuf[i * dstCh + c] = (c < fmt.channels) ? srcBuf[i * fmt.channels + c] : 0.0f;
                    }
                }
            }
            chPtr = chBuf.data();
        }

        if (resamplerInit) {
            ma_uint64 inCount = got;
            ma_uint64 outCount = rsBuf.size() / dstCh;
            ma_resampler_process_pcm_frames(&resampler, chPtr, &inCount, rsBuf.data(), &outCount);
            if (outCount > 0) {
                d.floatRing.write(rsBuf.data(), static_cast<size_t>(outCount) * dstCh);
            }
        } else {
            d.floatRing.write(chPtr, got * dstCh);
        }
    }

    if (resamplerInit) {
        ma_resampler_uninit(&resampler, nullptr);
    }
    dec->close();
}

void NativeAudioEngine::drainCommands() {
    Command cmd{};
    while (cmdRing_.popOne(cmd)) {
        switch (cmd.type) {
        case CmdType::DECK_PLAY:
            if (cmd.deck >= 0 && cmd.deck < kDeckCount) {
                decks_[cmd.deck].playing.store(true);
                activeDeck_.store(cmd.deck);
            }
            break;
        case CmdType::DECK_PAUSE:
            if (cmd.deck >= 0 && cmd.deck < kDeckCount) {
                decks_[cmd.deck].playing.store(false);
            }
            break;
        case CmdType::SET_DSP:
            rtDsp_ = cmd.dsp;
            eq_.setGains(rtDsp_.eq, rtDsp_.preampDb);
            reverb_.setAmount(rtDsp_.reverb);
            karaoke_.setAmount(rtDsp_.karaoke);
            loudness_.setConfig(rtDsp_.loudness, rtDsp_.normalizeGainDb);
            break;
        case CmdType::SET_ACTIVE_DECK:
            if (cmd.deck >= 0 && cmd.deck < kDeckCount) {
                activeDeck_.store(cmd.deck);
            }
            break;
        case CmdType::SCHEDULE_MIX:
            activePlan_ = planSlots_[stagedPlanSlot_.load(std::memory_order_acquire)];
            mixOutDeck_ = cmd.deck;
            mixInDeck_ = static_cast<int>(cmd.arg0);
            mixElapsedFrames_ = 0;
            mixTotalFrames_ = std::max<int64_t>(0, activePlan_.durationMs * static_cast<int64_t>(deviceSampleRate_) / 1000LL);
            planArmed_.store(true);
            mixActive_.store(false);
            mixProgressAtomic_.store(0.0f);
            break;
        case CmdType::CANCEL_MIX:
            planArmed_.store(false);
            mixActive_.store(false);
            mixProgressAtomic_.store(0.0f);
            break;
        case CmdType::SET_SLEEP_TIMER:
            sleepTimerTriggered_.store(false);
            sleepFade_.configure(static_cast<int>(deviceSampleRate_), cmd.arg0, cmd.arg1, cmd.flag);
            break;
        case CmdType::CLEAR_SLEEP_TIMER:
            sleepTimerTriggered_.store(false);
            sleepFade_.clear();
            break;
        }
    }
}

void NativeAudioEngine::renderAudio(void* output, uint32_t frameCount) {
    // REAL-TIME AUDIO CALLBACK: zero allocations, zero mutex locks!
    drainCommands();

    const uint32_t frames = std::min<uint32_t>(frameCount, kMaxBlockFrames);
    const int ch = std::max<int>(1, deviceChannels_);

    if (exclusiveActive_) {
        const int active = activeDeck_.load();
        auto& d = decks_[active];
        const size_t bytesPerFrame = ma_get_bytes_per_frame(deviceFormat_, static_cast<ma_uint32>(ch));
        const size_t wantBytes = static_cast<size_t>(frames) * bytesPerFrame;
        if (!d.playing.load()) {
            std::memset(output, 0, wantBytes);
            return;
        }
        const size_t gotBytes = d.rawRing.read(static_cast<uint8_t*>(output), wantBytes);
        if (gotBytes < wantBytes) {
            std::memset(static_cast<uint8_t*>(output) + gotBytes, 0, wantBytes - gotBytes);
            if (d.eofDecoded.load() && d.rawRing.availableRead() == 0 && !d.endedNotified.exchange(true)) {
                d.playing.store(false);
                rtEventRing_.pushOne({EventType::DECK_ENDED, active});
            }
        }
        const int64_t gotFrames = static_cast<int64_t>(gotBytes / std::max<size_t>(1, bytesPerFrame));
        const int64_t srcFrames = gotFrames * static_cast<int64_t>(d.sourceSampleRate.load()) /
            std::max<int64_t>(1, deviceSampleRate_);
        d.sourceFramePos.fetch_add(srcFrames);
        return;
    }

    float* outF = static_cast<float*>(output);
    const size_t totalSamples = static_cast<size_t>(frames) * ch;
    std::fill_n(outF, totalSamples, 0.0f);

    const int active = activeDeck_.load();
    auto& activeD = decks_[active];

    // Check armed Automix trigger
    if (planArmed_.load() && !mixActive_.load() && activeD.playing.load()) {
        const int64_t posMs = activeD.sourceFramePos.load() * 1000LL /
            std::max<int64_t>(1, activeD.sourceSampleRate.load());
        if (activePlan_.triggerAtMs < 0 || posMs >= activePlan_.triggerAtMs) {
            planArmed_.store(false);
            if (activePlan_.tier == TransitionTier::HARD_CUT || mixTotalFrames_ <= 0) {
                // Immediate cut / gapless switch
                activeD.playing.store(false);
                decks_[mixInDeck_].playing.store(true);
                activeDeck_.store(mixInDeck_);
                rtEventRing_.pushOne({EventType::DECK_ENDED, mixOutDeck_});
            } else {
                mixActive_.store(true);
                mixElapsedFrames_ = 0;
                decks_[mixInDeck_].playing.store(true);
            }
        }
    }

    const int curActive = activeDeck_.load();
    auto& dOut = decks_[curActive];
    if (!dOut.playing.load()) {
        return;
    }

    if (mixActive_.load()) {
        auto& outDeck = decks_[mixOutDeck_];
        auto& inDeck = decks_[mixInDeck_];

        const float progress = (mixTotalFrames_ > 0)
            ? std::clamp(static_cast<float>(mixElapsedFrames_) / static_cast<float>(mixTotalFrames_), 0.0f, 1.0f)
            : 1.0f;
        mixProgressAtomic_.store(progress);

        const MixShapes shapes = mixmath::automationForPlan(activePlan_, progress);
        const double elapsedMixMs = static_cast<double>(mixElapsedFrames_) * 1000.0 /
            std::max<double>(1.0, static_cast<double>(deviceSampleRate_));
        const double inSrcMs = static_cast<double>(inDeck.sourceFramePos.load()) * 1000.0 /
            std::max<double>(1.0, static_cast<double>(inDeck.sourceSampleRate.load()));
        const float incTempo = mixmath::incomingTempoAt(activePlan_, progress, elapsedMixMs, inSrcMs);

        bool outEnd = false;
        bool inEnd = false;
        const size_t outConsumed = outDeck.bungee.renderFromRing(
            outDeck.floatRing, bufOut_.data(), static_cast<int>(frames), rtDsp_.speed, rtDsp_.pitch, outEnd);
        const size_t inConsumed = inDeck.bungee.renderFromRing(
            inDeck.floatRing, bufIn_.data(), static_cast<int>(frames), rtDsp_.speed * incTempo, rtDsp_.pitch, inEnd);

        outDeck.sourceFramePos.fetch_add(
            static_cast<int64_t>(outConsumed) * outDeck.sourceSampleRate.load() / std::max<int64_t>(1, deviceSampleRate_));
        inDeck.sourceFramePos.fetch_add(
            static_cast<int64_t>(inConsumed) * inDeck.sourceSampleRate.load() / std::max<int64_t>(1, deviceSampleRate_));

        outDeck.djFilter.process(bufOut_.data(), static_cast<int>(frames), shapes.outgoing);
        inDeck.djFilter.process(bufIn_.data(), static_cast<int>(frames), shapes.incoming);

        for (size_t i = 0; i < totalSamples; ++i) {
            bufMix_[i] = bufOut_[i] + bufIn_[i];
        }

        mixElapsedFrames_ += frames;
        if (mixElapsedFrames_ >= mixTotalFrames_ || (outEnd && outDeck.eofDecoded.load())) {
            mixActive_.store(false);
            mixProgressAtomic_.store(0.0f);
            outDeck.playing.store(false);
            activeDeck_.store(mixInDeck_);
            outDeck.djFilter.reset();
            inDeck.djFilter.reset();
            rtEventRing_.pushOne({EventType::DECK_ENDED, mixOutDeck_});
        }
    } else {
        bool reachedEnd = false;
        const size_t consumed = dOut.bungee.renderFromRing(
            dOut.floatRing, bufMix_.data(), static_cast<int>(frames), rtDsp_.speed, rtDsp_.pitch, reachedEnd);
        dOut.sourceFramePos.fetch_add(
            static_cast<int64_t>(consumed) * dOut.sourceSampleRate.load() / std::max<int64_t>(1, deviceSampleRate_));

        DeckShape manualShape{};
        manualShape.gain = 1.0f;
        manualShape.lowPassHz = rtDsp_.filterLowPassHz;
        manualShape.highPassHz = rtDsp_.filterHighPassHz;
        manualShape.bassDb = rtDsp_.filterBassDb;
        dOut.djFilter.process(bufMix_.data(), static_cast<int>(frames), manualShape);

        if (reachedEnd && dOut.eofDecoded.load() && dOut.floatRing.availableRead() == 0) {
            if (!dOut.endedNotified.exchange(true)) {
                dOut.playing.store(false);
                if (sleepFade_.isEndOfTrack()) {
                    sleepFade_.clear();
                }
                rtEventRing_.pushOne({EventType::DECK_ENDED, curActive});
            }
        }
    }

    // Master DSP chain: volume → EQ → reverb → karaoke → ebur128 gain → sleep fade → limiter → out
    if (std::abs(rtDsp_.volume - 1.0f) >= 1e-4f) {
        const float vol = rtDsp_.volume;
        for (size_t i = 0; i < totalSamples; ++i) {
            bufMix_[i] *= vol;
        }
    }

    eq_.process(bufMix_.data(), static_cast<int>(frames));
    reverb_.process(bufMix_.data(), static_cast<int>(frames));
    karaoke_.process(bufMix_.data(), static_cast<int>(frames));
    loudness_.process(bufMix_.data(), static_cast<int>(frames));
    sleepFade_.process(bufMix_.data(), static_cast<int>(frames), ch);
    limiter_.process(bufMix_.data(), static_cast<int>(frames), ch);

    if (sleepFade_.isFinished() && !sleepTimerTriggered_.exchange(true)) {
        decks_[activeDeck_.load()].playing.store(false);
        sleepFade_.clear();
    }

    std::memcpy(outF, bufMix_.data(), totalSamples * sizeof(float));
}

void NativeAudioEngine::pushEvent(EventMsg msg) {
    {
        std::lock_guard<std::mutex> lock(evMu_);
        evQueue_.push_back(std::move(msg));
    }
    evCv_.notify_one();
}

void NativeAudioEngine::eventLoop() {
    while (!evStop_.load()) {
        RtEvent rtEv{};
        while (rtEventRing_.popOne(rtEv)) {
            std::lock_guard<std::mutex> lk(evMu_);
            evQueue_.push_back({rtEv.type, rtEv.deck, ""});
        }

        std::vector<EventMsg> batch;
        {
            std::unique_lock<std::mutex> lock(evMu_);
            if (evQueue_.empty()) {
                evCv_.wait_for(lock, std::chrono::milliseconds(15));
            }
            if (!evQueue_.empty()) {
                batch.swap(evQueue_);
            }
        }

        while (rtEventRing_.popOne(rtEv)) {
            batch.push_back({rtEv.type, rtEv.deck, ""});
        }

        if (batch.empty()) continue;

        EngineCallbacks cbCopy;
        {
            std::lock_guard<std::mutex> lock(cbMu_);
            cbCopy = callbacks_;
        }
        for (const auto& ev : batch) {
            switch (ev.type) {
            case EventType::DECK_ENDED:
                if (cbCopy.onEnded) cbCopy.onEnded(ev.deck);
                break;
            case EventType::DEVICE_LOST:
                if (cbCopy.onDeviceLost) cbCopy.onDeviceLost(ev.text);
                break;
            case EventType::SMTC_BUTTON:
                if (cbCopy.onSmtcButton) cbCopy.onSmtcButton(ev.text);
                break;
            case EventType::DECK_ERROR:
                if (cbCopy.onError) cbCopy.onError(ev.deck, ev.text);
                break;
            }
        }
    }
}

SelfCheckReport NativeAudioEngine::runSelfCheck() {
    SelfCheckReport report{};
    constexpr int kRate = 44100;
    constexpr int kCh = 2;
    constexpr int kFrames = kRate; // 1 s
    constexpr int kBlock = 1024;

    // 1. Render 1 s of generated 440 Hz + 880 Hz stereo sine through the full DSP chain (all stages on)
    SpscRing<float> ringA(kFrames * kCh * 2);
    SpscRing<float> ringB(kFrames * kCh * 2);
    std::vector<float> sineA(kFrames * kCh);
    std::vector<float> sineB(kFrames * kCh);
    for (int i = 0; i < kFrames; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kRate);
        const float sA = 0.45f * std::sin(2.0f * 3.14159265f * 440.0f * t) +
                         0.15f * std::sin(2.0f * 3.14159265f * 880.0f * t);
        const float sB = 0.40f * std::sin(2.0f * 3.14159265f * 330.0f * t);
        sineA[i * 2] = sA;
        sineA[i * 2 + 1] = sA * 0.85f;
        sineB[i * 2] = sB;
        sineB[i * 2 + 1] = sB * 0.90f;
    }

    BungeeStretcher bungee;
    bungee.prepare(kRate, kCh, kBlock);
    DjFilter dj;
    dj.prepare(kRate, kCh);
    ParametricEq eq;
    eq.prepare(kRate, kCh);
    eq.setGains({3.0f, 2.0f, 0.0f, -1.0f, 1.0f, 2.0f, 1.5f, 0.0f, 2.0f, 3.0f}, 0.0f);
    StereoReverb reverb;
    reverb.prepare(kRate, kCh);
    reverb.setAmount(0.4f);
    KaraokeExtractor karaoke;
    karaoke.prepare(kRate, kCh);
    karaoke.setAmount(0.5f);
    LoudnessStage loudness;
    loudness.prepare(kRate, kCh);
    loudness.setConfig(true, 0.0f);
    SleepTimerFade sleepFade;
    sleepFade.configure(kRate, 1000, 250, false);
    SafetyLimiter limiter;
    limiter.prepare(kRate);

    // Feed extra frames so stretcher never runs dry at 1.05x speed
    ringA.write(sineA.data(), sineA.size());
    ringA.write(sineA.data(), 4096 * kCh);

    std::vector<float> rendered(kFrames * kCh, 0.0f);
    std::vector<float> blockBuf(kBlock * kCh);
    DeckShape testShape{};
    testShape.gain = 0.95f;
    testShape.lowPassHz = 12000.0;
    testShape.highPassHz = 40.0;
    testShape.bassDb = -3.0;
    testShape.stems = {1.0f, 1.0f, 1.0f, 0.7f, 0.0f, 0.0f, VocalDuckMode::MID_SIDE, true, true};

    int done = 0;
    while (done < kFrames) {
        const int n = std::min(kBlock, kFrames - done);
        bool ended = false;
        bungee.renderFromRing(ringA, blockBuf.data(), n, 1.05f, 0.98f, ended);
        dj.process(blockBuf.data(), n, testShape);
        eq.process(blockBuf.data(), n);
        reverb.process(blockBuf.data(), n);
        karaoke.process(blockBuf.data(), n);
        loudness.process(blockBuf.data(), n);
        sleepFade.process(blockBuf.data(), n, kCh);
        limiter.process(blockBuf.data(), n, kCh);
        std::memcpy(rendered.data() + done * kCh, blockBuf.data(), static_cast<size_t>(n) * kCh * sizeof(float));
        done += n;
    }

    double sumSq = 0.0;
    for (float s : rendered) {
        if (!std::isfinite(s) || std::abs(s) > 1.0001f) {
            report.ok = false;
            report.summary = "full DSP render produced non-finite or clipped sample";
            return report;
        }
        sumSq += static_cast<double>(s) * static_cast<double>(s);
    }
    if (sumSq < 1e-3) {
        report.ok = false;
        report.summary = "full DSP render produced silence";
        return report;
    }

    // 2. Automix A->B with every MixStyle
    const std::array<MixStyle, 4> styles = {
        MixStyle::BLEND, MixStyle::FILTER_SWEEP, MixStyle::BASS_HANDOFF, MixStyle::EQUAL_POWER
    };
    for (MixStyle st : styles) {
        AutomixPlanData plan{};
        plan.hasStyle = true;
        plan.style = st;
        plan.durationMs = 500;
        plan.swapAt = 0.5;
        plan.swapWidth = 0.1;
        plan.vocalClash = 0.4;
        plan.tier = (st == MixStyle::BLEND) ? TransitionTier::SMART_BEATMATCH : TransitionTier::PLAIN_CROSSFADE;
        plan.incomingTempoRatio = (st == MixStyle::BLEND) ? 1.04f : 1.0f;

        ringA.clear();
        ringB.clear();
        ringA.write(sineA.data(), sineA.size());
        ringB.write(sineB.data(), sineB.size());

        DjFilter djOut, djIn;
        djOut.prepare(kRate, kCh);
        djIn.prepare(kRate, kCh);
        BungeeStretcher strIn;
        strIn.prepare(kRate, kCh, kBlock);

        const int mixFrames = kRate / 2;
        std::vector<float> bufA(kBlock * kCh), bufB(kBlock * kCh);
        int mDone = 0;
        while (mDone < mixFrames) {
            const int n = std::min(kBlock, mixFrames - mDone);
            const float p = static_cast<float>(mDone) / static_cast<float>(mixFrames);
            const MixShapes shapes = mixmath::automationForPlan(plan, p);
            const float incTempo = mixmath::incomingTempoAt(plan, p, p * 500.0, p * 500.0);

            bool eA = false, eB = false;
            ringA.read(bufA.data(), static_cast<size_t>(n) * kCh);
            strIn.renderFromRing(ringB, bufB.data(), n, incTempo, 1.0f, eB);
            (void)eA; (void)eB;

            djOut.process(bufA.data(), n, shapes.outgoing);
            djIn.process(bufB.data(), n, shapes.incoming);

            for (int i = 0; i < n * kCh; ++i) {
                const float mixS = bufA[i] + bufB[i];
                if (!std::isfinite(mixS) || std::abs(mixS) > 4.0f) {
                    report.ok = false;
                    report.summary = "Automix MixStyle render unstable";
                    return report;
                }
            }
            mDone += n;
        }
    }

    // 3. Run yuma::smart::AnalyzeAudio on 11025 Hz mono downmix of the rendered 1 s signal
    std::vector<float> mono(kFrames);
    for (int i = 0; i < kFrames; ++i) {
        mono[i] = 0.5f * (rendered[i * 2] + rendered[i * 2 + 1]);
    }
    const std::vector<float> down = yuma::smart::Resample(mono, kRate, 11025.0);
    const yuma::smart::AnalysisResult analysis = yuma::smart::AnalyzeAudio(down, 11025.0, 1.0);
    if (!std::isfinite(analysis.loudness_lufs) || !std::isfinite(analysis.duration)) {
        report.ok = false;
        report.summary = "AnalyzeAudio returned non-finite fields";
        return report;
    }

    report.ok = true;
    report.analyzedDurationSec = analysis.duration;
    report.analyzedLufs = analysis.loudness_lufs;
    report.summary = "native self-check ok (4 MixStyles + full DSP + AnalyzeAudio)";
    return report;
}

} // namespace square
