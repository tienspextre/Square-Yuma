#pragma once

#include "audio_stream.h"
#include "decoder.h"
#include "dsp_chain.h"
#include "ring_buffer.h"
#include "smtc.h"

#include "miniaudio.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace square {

struct EngineCallbacks {
    std::function<void(int deck)> onEnded;
    std::function<void(const std::string& reason)> onDeviceLost;
    std::function<void(const std::string& button)> onSmtcButton;
    std::function<void(int deck, const std::string& message)> onError;
};

struct SelfCheckReport {
    bool ok = false;
    std::string summary;
    double analyzedDurationSec = 0.0;
    double analyzedLufs = -70.0;
};

class NativeAudioEngine {
public:
    NativeAudioEngine();
    ~NativeAudioEngine();

    bool init(bool headless = false);
    void shutdown();

    bool deckLoad(int deck, const std::string& uri, const std::string& headersJson, int64_t startMs);
    void deckPlay(int deck);
    void deckPause(int deck);
    void deckSeek(int deck, int64_t ms);

    bool setOutput(const std::string& mode, const std::string& deviceId);
    std::string listDevicesJson();

    void setDspJson(const std::string& jsonStr);
    bool scheduleMixJson(const std::string& planJson);
    void setSleepTimer(int64_t ms, int64_t fadeMs, bool endOfTrack);

    int64_t positionMs(int deck) const;
    std::string statusJson() const;

    void smtcUpdate(const std::string& metadataJson, const std::vector<uint8_t>& artwork);
    void setCallbacks(EngineCallbacks cb);

    // Offline helpers (also exposed via JNI)
    std::string probeTrackJson(const std::string& uri, const std::string& headersJson);
    std::vector<float> decodeToFloat(const std::string& uri, const std::string& headersJson,
                                     int64_t fromMs, int64_t durationMs, int targetRate, int targetChannels);
    double measureTrackLufs(const std::string& uri, const std::string& headersJson);
    static double measureSamplesLufs(const float* interleaved, size_t frames, uint32_t sampleRate, uint16_t channels);

    // Deterministic offline/null-sink full-chain & Automix verification
    SelfCheckReport runSelfCheck();

private:
    static constexpr int kDeckCount = 2;
    static constexpr int kMaxBlockFrames = 4096;
    static constexpr double kRingSeconds = 12.0;

    enum class CmdType : uint8_t {
        DECK_PLAY,
        DECK_PAUSE,
        SET_DSP,
        SET_ACTIVE_DECK,
        SCHEDULE_MIX,
        CANCEL_MIX,
        SET_SLEEP_TIMER,
        CLEAR_SLEEP_TIMER,
    };

    struct Command {
        CmdType type = CmdType::DECK_PLAY;
        int deck = 0;
        int64_t arg0 = 0;
        int64_t arg1 = 0;
        bool flag = false;
        DspConfig dsp{};
    };

    struct DeckState {
        std::string uri;
        std::string headersJson;
        TrackFormatInfo format{};
        bool hasTrack = false;

        // Decode thread state
        std::thread decodeThread;
        std::mutex decodeMu;
        std::condition_variable decodeCv;
        std::atomic<bool> stopDecode{false};
        std::atomic<bool> eofDecoded{false};
        std::atomic<bool> errorFlag{false};
        std::string lastError;

        // PcmLoop on source frames (optional, configured by AutomixPlan)
        PcmLoop loop;
        std::mutex loopMu;

        // Lock-free SPSC rings: decode thread writes, audio callback reads
        SpscRing<float> floatRing;
        SpscRing<uint8_t> rawRing;

        // Audio-thread state
        std::atomic<bool> playing{false};
        std::atomic<int64_t> sourceFramePos{0};
        std::atomic<uint32_t> sourceSampleRate{44100};
        std::atomic<uint16_t> sourceChannels{2};
        std::atomic<bool> endedNotified{false};
        std::atomic<bool> hasRenderedAudio{false};

        BungeeStretcher bungee;
        DjFilter djFilter;
    };

    enum class EventType : uint8_t {
        DECK_ENDED,
        DEVICE_LOST,
        SMTC_BUTTON,
        DECK_ERROR,
    };

    struct EventMsg {
        EventType type = EventType::DECK_ENDED;
        int deck = 0;
        std::string text;
    };

    static void maDataCallback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount);
    static void maNotificationCallback(const ma_device_notification* pNotification);

    void renderAudio(void* output, uint32_t frameCount);
    void drainCommands();
    void decodeLoop(int deckIdx, int64_t startMs);
    bool openDeviceForCurrentState();
    void closeDevice();
    void reopenDeviceIfNeeded();
    void pushEvent(EventMsg msg);
    void eventLoop();

    bool initialized_ = false;
    bool headless_ = false;

    ma_context context_{};
    bool contextInit_ = false;
    ma_device device_{};
    bool deviceOpen_ = false;
    std::mutex deviceMu_;

    std::string outputMode_ = "shared";
    std::string selectedDeviceId_;
    std::string fallbackReason_;
    bool exclusiveActive_ = false;
    uint32_t deviceSampleRate_ = 48000;
    uint16_t deviceChannels_ = 2;
    ma_format deviceFormat_ = ma_format_f32;
    std::string deviceName_;

    std::array<DeckState, kDeckCount> decks_;
    std::atomic<int> activeDeck_{0};

    // Pending/active Automix plan (double-buffered for lock-free audio thread read)
    std::mutex planMu_;
    std::array<AutomixPlanData, 2> planSlots_{};
    std::atomic<int> stagedPlanSlot_{0};
    AutomixPlanData activePlan_{};
    std::atomic<bool> planArmed_{false};
    std::atomic<bool> mixActive_{false};
    int64_t mixElapsedFrames_ = 0;
    int64_t mixTotalFrames_ = 0;
    int mixOutDeck_ = 0;
    int mixInDeck_ = 1;
    std::atomic<float> mixProgressAtomic_{0.0f};

    // Lock-free command queue (control -> audio thread)
    SpscRing<Command> cmdRing_;

    // Lock-free RT event queue (audio thread -> event thread)
    struct RtEvent {
        EventType type = EventType::DECK_ENDED;
        int deck = 0;
    };
    SpscRing<RtEvent> rtEventRing_;

    // Audio-thread master DSP chain (preallocated, zero heap allocs in renderAudio)
    DspConfig rtDsp_{};
    ParametricEq eq_;
    StereoReverb reverb_;
    KaraokeExtractor karaoke_;
    LoudnessStage loudness_;
    SleepTimerFade sleepFade_;
    SafetyLimiter limiter_;
    std::vector<float> bufOut_;
    std::vector<float> bufIn_;
    std::vector<float> bufMix_;
    std::vector<uint8_t> bufRaw_;
    std::atomic<bool> sleepTimerTriggered_{false};
    std::atomic<uint64_t> underruns_{0};

    // Control-side copy of DSP config
    DspConfig controlDsp_{};
    std::mutex controlMu_;

    // Event dispatch thread (so JNI callbacks never run on audio thread)
    EngineCallbacks callbacks_;
    std::mutex cbMu_;
    std::mutex evMu_;
    std::condition_variable evCv_;
    std::vector<EventMsg> evQueue_;
    std::thread evThread_;
    std::atomic<bool> evStop_{false};

    std::unique_ptr<SmtcController> smtc_;
};

} // namespace square
