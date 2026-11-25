#include "audio_stream.h"

#ifdef _WIN32

#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <cstring>
#include <vector>

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

class WinHttpRangeStream final : public AudioInputStream {
public:
    ~WinHttpRangeStream() override { close(); }

    bool open(const std::string& url, const std::map<std::string, std::string>& headers, std::string& err) {
        url_ = url;
        headers_ = headers;

        std::wstring wurl = toWide(url);
        URL_COMPONENTS comps{};
        comps.dwStructSize = sizeof(comps);
        comps.dwSchemeLength = static_cast<DWORD>(-1);
        comps.dwHostNameLength = static_cast<DWORD>(-1);
        comps.dwUrlPathLength = static_cast<DWORD>(-1);
        comps.dwExtraInfoLength = static_cast<DWORD>(-1);
        if (!WinHttpCrackUrl(wurl.c_str(), 0, 0, &comps)) {
            err = "WinHttpCrackUrl failed";
            return false;
        }

        isHttps_ = (comps.nScheme == INTERNET_SCHEME_HTTPS);
        port_ = comps.nPort;
        host_.assign(comps.lpszHostName, comps.dwHostNameLength);
        path_.assign(comps.lpszUrlPath, comps.dwUrlPathLength);
        if (comps.dwExtraInfoLength > 0) {
            path_.append(comps.lpszExtraInfo, comps.dwExtraInfoLength);
        }

        session_ = WinHttpOpen(L"Square-Yuma/1.4", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                               WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session_) {
            err = "WinHttpOpen failed";
            return false;
        }
        WinHttpSetTimeouts(session_, 10000, 10000, 15000, 15000);

        connect_ = WinHttpConnect(session_, host_.c_str(), port_, 0);
        if (!connect_) {
            err = "WinHttpConnect failed";
            close();
            return false;
        }

        if (!openRequestAt(0, err)) {
            close();
            return false;
        }
        return true;
    }

    size_t read(void* dst, size_t bytes) override {
        if (!request_ || !dst || bytes == 0) return 0;
        uint8_t* out = static_cast<uint8_t*>(dst);
        size_t total = 0;

        while (total < bytes) {
            if (bufPos_ < bufLen_) {
                const size_t avail = bufLen_ - bufPos_;
                const size_t take = std::min(avail, bytes - total);
                std::memcpy(out + total, buf_.data() + bufPos_, take);
                bufPos_ += take;
                pos_ += static_cast<int64_t>(take);
                total += take;
                continue;
            }
            if (!refillBuffer()) break;
        }
        return total;
    }

    bool seek(int64_t offset, int whence) override {
        if (!seekable_) return false;
        int64_t target = pos_;
        if (whence == SEEK_SET) target = offset;
        else if (whence == SEEK_CUR) target = pos_ + offset;
        else if (whence == SEEK_END) {
            if (totalSize_ < 0) return false;
            target = totalSize_ + offset;
        } else {
            return false;
        }
        if (target < 0) target = 0;
        if (totalSize_ >= 0 && target > totalSize_) target = totalSize_;
        if (target == pos_) return true;

        // If target lies inside the already-buffered window, just move bufPos_.
        const int64_t bufStart = pos_ - static_cast<int64_t>(bufPos_);
        const int64_t bufEnd = bufStart + static_cast<int64_t>(bufLen_);
        if (target >= bufStart && target < bufEnd) {
            bufPos_ = static_cast<size_t>(target - bufStart);
            pos_ = target;
            return true;
        }

        std::string err;
        return openRequestAt(target, err);
    }

    int64_t tell() const override { return pos_; }
    int64_t size() const override { return totalSize_; }
    bool isSeekable() const override { return seekable_; }

    void close() override {
        closeRequest();
        if (connect_) { WinHttpCloseHandle(connect_); connect_ = nullptr; }
        if (session_) { WinHttpCloseHandle(session_); session_ = nullptr; }
    }

private:
    void closeRequest() {
        if (request_) { WinHttpCloseHandle(request_); request_ = nullptr; }
        bufPos_ = 0;
        bufLen_ = 0;
    }

    bool openRequestAt(int64_t offset, std::string& err) {
        closeRequest();
        DWORD flags = isHttps_ ? WINHTTP_FLAG_SECURE : 0;
        request_ = WinHttpOpenRequest(connect_, L"GET", path_.c_str(), nullptr,
                                      WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
        if (!request_) {
            err = "WinHttpOpenRequest failed";
            return false;
        }

        if (offset > 0) {
            std::wstring rangeHdr = L"Range: bytes=" + std::to_wstring(offset) + L"-\r\n";
            WinHttpAddRequestHeaders(request_, rangeHdr.c_str(), static_cast<DWORD>(-1),
                                     WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
        }
        for (const auto& [k, v] : headers_) {
            std::wstring hdr = toWide(k + ": " + v + "\r\n");
            WinHttpAddRequestHeaders(request_, hdr.c_str(), static_cast<DWORD>(-1), WINHTTP_ADDREQ_FLAG_ADD);
        }

        if (!WinHttpSendRequest(request_, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
            !WinHttpReceiveResponse(request_, nullptr)) {
            err = "WinHTTP request failed";
            closeRequest();
            return false;
        }

        DWORD status = 0;
        DWORD statusLen = sizeof(status);
        WinHttpQueryHeaders(request_, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusLen, WINHTTP_NO_HEADER_INDEX);
        if (status < 200 || status >= 300) {
            err = "HTTP status " + std::to_string(status);
            closeRequest();
            return false;
        }

        if (offset == 0) {
            wchar_t clBuf[64]{};
            DWORD clLen = sizeof(clBuf);
            if (WinHttpQueryHeaders(request_, WINHTTP_QUERY_CONTENT_LENGTH,
                                    WINHTTP_HEADER_NAME_BY_INDEX, clBuf, &clLen, WINHTTP_NO_HEADER_INDEX)) {
                totalSize_ = _wtoi64(clBuf);
            }
            wchar_t arBuf[64]{};
            DWORD arLen = sizeof(arBuf);
            if (WinHttpQueryHeaders(request_, WINHTTP_QUERY_ACCEPT_RANGES,
                                    WINHTTP_HEADER_NAME_BY_INDEX, arBuf, &arLen, WINHTTP_NO_HEADER_INDEX)) {
                seekable_ = (_wcsicmp(arBuf, L"none") != 0);
            } else {
                seekable_ = (totalSize_ > 0);
            }
        } else if (status == 206) {
            seekable_ = true;
        }

        pos_ = offset;
        buf_.resize(65536);
        bufPos_ = 0;
        bufLen_ = 0;
        return true;
    }

    bool refillBuffer() {
        if (!request_) return false;
        DWORD bytesRead = 0;
        if (!WinHttpReadData(request_, buf_.data(), static_cast<DWORD>(buf_.size()), &bytesRead)) {
            return false;
        }
        bufPos_ = 0;
        bufLen_ = static_cast<size_t>(bytesRead);
        return bytesRead > 0;
    }

    std::string url_;
    std::map<std::string, std::string> headers_;
    std::wstring host_;
    std::wstring path_;
    INTERNET_PORT port_ = 80;
    bool isHttps_ = false;
    HINTERNET session_ = nullptr;
    HINTERNET connect_ = nullptr;
    HINTERNET request_ = nullptr;
    std::vector<uint8_t> buf_;
    size_t bufPos_ = 0;
    size_t bufLen_ = 0;
    int64_t pos_ = 0;
    int64_t totalSize_ = -1;
    bool seekable_ = true;
};

std::unique_ptr<AudioInputStream> createHttpInputStream(
    const std::string& url,
    const std::map<std::string, std::string>& headers,
    std::string& err) {
    auto s = std::make_unique<WinHttpRangeStream>();
    if (!s->open(url, headers, err)) return nullptr;
    return s;
}

} // namespace square

#endif // _WIN32
