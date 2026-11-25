#include "smtc.h"

#ifdef _WIN32

#include <nlohmann/json.hpp>

#include <windows.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace square {

namespace {
std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    return w;
}
} // namespace

class WinRtSmtcController final : public SmtcController {
public:
    ~WinRtSmtcController() override { stop(); }

    bool start(SmtcButtonCallback onButton) override {
        stop();
        onButton_ = std::move(onButton);
        running_.store(true);
        readyPromise_ = false;
        thread_ = std::thread([this]() { runMessageLoop(); });
        std::unique_lock<std::mutex> lock(initMu_);
        initCv_.wait(lock, [this]() { return readyPromise_; });
        return startedOk_;
    }

    void update(const std::string& metadataJson, const std::vector<uint8_t>& artworkBytes) override {
        if (!running_.load()) return;
        {
            std::lock_guard<std::mutex> lock(pendingMu_);
            pendingJson_ = metadataJson;
            pendingArt_ = artworkBytes;
            hasPending_ = true;
        }
        HWND hwnd = hwnd_.load();
        if (hwnd) PostMessageW(hwnd, WM_APP + 1, 0, 0);
    }

    void stop() override {
        if (!running_.exchange(false)) return;
        HWND hwnd = hwnd_.load();
        if (hwnd) PostMessageW(hwnd, WM_QUIT, 0, 0);
        if (thread_.joinable()) thread_.join();
        hwnd_.store(nullptr);
    }

private:
    static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
        if (msg == WM_NCCREATE) {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
            return TRUE;
        }
        auto* self = reinterpret_cast<WinRtSmtcController*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (self && msg == WM_APP + 1) {
            self->applyPendingUpdate();
            return 0;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    }

    void runMessageLoop() {
        winrt::init_apartment(winrt::apartment_type::single_threaded);

        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = &WinRtSmtcController::wndProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.lpszClassName = L"SquareYumaSmtcWindow";
        RegisterClassExW(&wc);

        // Hidden message-only HWND independent of Compose window (survives tray mode).
        HWND hwnd = CreateWindowExW(
            0, wc.lpszClassName, L"SquareYumaSMTC", 0,
            0, 0, 0, 0, HWND_MESSAGE, nullptr, wc.hInstance, this);
        hwnd_.store(hwnd);

        winrt::Windows::Media::SystemMediaTransportControls smtc{nullptr};
        bool ok = false;
        if (hwnd) {
            try {
                auto interop = winrt::get_activation_factory<
                    winrt::Windows::Media::SystemMediaTransportControls,
                    ISystemMediaTransportControlsInterop>();
                winrt::com_ptr<IInspectable> inspectable;
                if (SUCCEEDED(interop->GetForWindow(
                        hwnd,
                        winrt::guid_of<winrt::Windows::Media::SystemMediaTransportControls>(),
                        winrt::put_abi(inspectable)))) {
                    smtc = inspectable.as<winrt::Windows::Media::SystemMediaTransportControls>();
                    smtc.IsEnabled(true);
                    smtc.IsPlayEnabled(true);
                    smtc.IsPauseEnabled(true);
                    smtc.IsNextEnabled(true);
                    smtc.IsPreviousEnabled(true);
                    smtc.IsStopEnabled(true);

                    smtc.ButtonPressed([this](
                        winrt::Windows::Media::SystemMediaTransportControls const&,
                        winrt::Windows::Media::SystemMediaTransportControlsButtonPressedEventArgs const& args) {
                        using Btn = winrt::Windows::Media::SystemMediaTransportControlsButton;
                        std::string name;
                        switch (args.Button()) {
                        case Btn::Play: name = "play"; break;
                        case Btn::Pause: name = "pause"; break;
                        case Btn::Next: name = "next"; break;
                        case Btn::Previous: name = "previous"; break;
                        case Btn::Stop: name = "stop"; break;
                        default: break;
                        }
                        if (!name.empty() && onButton_) onButton_(name);
                    });
                    smtc_ = smtc;
                    ok = true;
                }
            } catch (...) {
                ok = false;
            }
        }

        {
            std::lock_guard<std::mutex> lock(initMu_);
            startedOk_ = ok;
            readyPromise_ = true;
        }
        initCv_.notify_all();

        MSG msg;
        while (running_.load() && GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        if (smtc_) {
            try { smtc_.IsEnabled(false); } catch (...) {}
            smtc_ = nullptr;
        }
        if (hwnd) DestroyWindow(hwnd);
        winrt::uninit_apartment();
    }

    void applyPendingUpdate() {
        if (!smtc_) return;
        std::string jsonStr;
        std::vector<uint8_t> art;
        {
            std::lock_guard<std::mutex> lock(pendingMu_);
            if (!hasPending_) return;
            jsonStr = std::move(pendingJson_);
            art = std::move(pendingArt_);
            hasPending_ = false;
        }

        auto j = nlohmann::json::parse(jsonStr, nullptr, false);
        if (!j.is_object()) return;

        try {
            const std::string status = j.value("status", "stopped");
            using Status = winrt::Windows::Media::MediaPlaybackStatus;
            if (status == "playing") smtc_.PlaybackStatus(Status::Playing);
            else if (status == "paused") smtc_.PlaybackStatus(Status::Paused);
            else smtc_.PlaybackStatus(Status::Stopped);

            auto updater = smtc_.DisplayUpdater();
            updater.Type(winrt::Windows::Media::MediaPlaybackType::Music);
            auto music = updater.MusicProperties();
            music.Title(toWide(j.value("title", "")));
            music.Artist(toWide(j.value("artist", "")));
            music.AlbumTitle(toWide(j.value("album", "")));

            if (!art.empty()) {
                winrt::Windows::Storage::Streams::InMemoryRandomAccessStream ras;
                winrt::Windows::Storage::Streams::DataWriter writer(ras);
                writer.WriteBytes(art);
                writer.StoreAsync().get();
                writer.DetachStream();
                ras.Seek(0);
                updater.Thumbnail(
                    winrt::Windows::Storage::Streams::RandomAccessStreamReference::CreateFromStream(ras));
            } else {
                updater.Thumbnail(nullptr);
            }
            updater.Update();

            if (j.contains("durationMs") && j.contains("positionMs")) {
                winrt::Windows::Media::SystemMediaTransportControlsTimelineProperties timeline;
                const int64_t durMs = j.value("durationMs", int64_t{0});
                const int64_t posMs = j.value("positionMs", int64_t{0});
                timeline.StartTime(std::chrono::milliseconds(0));
                timeline.MinSeekTime(std::chrono::milliseconds(0));
                timeline.EndTime(std::chrono::milliseconds(std::max<int64_t>(0, durMs)));
                timeline.MaxSeekTime(std::chrono::milliseconds(std::max<int64_t>(0, durMs)));
                timeline.Position(std::chrono::milliseconds(std::clamp<int64_t>(posMs, 0, std::max<int64_t>(0, durMs))));
                smtc_.UpdateTimelineProperties(timeline);
            }
        } catch (...) {
        }
    }

    SmtcButtonCallback onButton_;
    std::atomic<bool> running_{false};
    std::atomic<HWND> hwnd_{nullptr};
    std::thread thread_;
    std::mutex initMu_;
    std::condition_variable initCv_;
    bool readyPromise_ = false;
    bool startedOk_ = false;

    std::mutex pendingMu_;
    std::string pendingJson_;
    std::vector<uint8_t> pendingArt_;
    bool hasPending_ = false;

    winrt::Windows::Media::SystemMediaTransportControls smtc_{nullptr};
};

std::unique_ptr<SmtcController> createSmtcController() {
    return std::make_unique<WinRtSmtcController>();
}

} // namespace square

#endif // _WIN32
