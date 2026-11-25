#include "audio_stream.h"

#include <curl/curl.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace square {

namespace {
struct CurlGlobalInit {
    CurlGlobalInit() { curl_global_init(CURL_GLOBAL_DEFAULT); }
};
} // namespace

class CurlRangeStream final : public AudioInputStream {
public:
    ~CurlRangeStream() override { close(); }

    bool open(const std::string& url, const std::map<std::string, std::string>& headers, std::string& err) {
        static CurlGlobalInit sInit;
        url_ = url;
        headers_ = headers;
        return fetchRange(0, err);
    }

    size_t read(void* dst, size_t bytes) override {
        if (!dst || bytes == 0) return 0;
        if (pos_ >= static_cast<int64_t>(data_.size())) return 0;
        const size_t avail = data_.size() - static_cast<size_t>(pos_);
        const size_t take = std::min(avail, bytes);
        std::memcpy(dst, data_.data() + pos_, take);
        pos_ += static_cast<int64_t>(take);
        return take;
    }

    bool seek(int64_t offset, int whence) override {
        int64_t target = pos_;
        if (whence == SEEK_SET) target = offset;
        else if (whence == SEEK_CUR) target = pos_ + offset;
        else if (whence == SEEK_END) target = static_cast<int64_t>(data_.size()) + offset;
        else return false;
        if (target < 0) target = 0;
        if (target > static_cast<int64_t>(data_.size())) target = static_cast<int64_t>(data_.size());
        pos_ = target;
        return true;
    }

    int64_t tell() const override { return pos_; }
    int64_t size() const override { return static_cast<int64_t>(data_.size()); }
    bool isSeekable() const override { return true; }
    void close() override { data_.clear(); pos_ = 0; }

private:
    static size_t writeCb(char* ptr, size_t size, size_t nmemb, void* userdata) {
        auto* vec = static_cast<std::vector<uint8_t>*>(userdata);
        const size_t n = size * nmemb;
        vec->insert(vec->end(), reinterpret_cast<uint8_t*>(ptr), reinterpret_cast<uint8_t*>(ptr) + n);
        return n;
    }

    bool fetchRange(int64_t start, std::string& err) {
        CURL* curl = curl_easy_init();
        if (!curl) {
            err = "curl_easy_init failed";
            return false;
        }
        struct curl_slist* list = nullptr;
        for (const auto& [k, v] : headers_) {
            list = curl_slist_append(list, (k + ": " + v).c_str());
        }
        data_.clear();
        curl_easy_setopt(curl, CURLOPT_URL, url_.c_str());
        if (list) curl_easy_setopt(curl, CURLOPT_HTTPHEADER, list);
        if (start > 0) curl_easy_setopt(curl, CURLOPT_RESUME_FROM_LARGE, static_cast<curl_off_t>(start));
        curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, &CurlRangeStream::writeCb);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &data_);
        const CURLcode rc = curl_easy_perform(curl);
        long status = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
        if (list) curl_slist_free_all(list);
        curl_easy_cleanup(curl);
        if (rc != CURLE_OK || status < 200 || status >= 300) {
            err = "libcurl HTTP error";
            return false;
        }
        pos_ = 0;
        return true;
    }

    std::string url_;
    std::map<std::string, std::string> headers_;
    std::vector<uint8_t> data_;
    int64_t pos_ = 0;
};

std::unique_ptr<AudioInputStream> createHttpInputStream(
    const std::string& url,
    const std::map<std::string, std::string>& headers,
    std::string& err) {
    auto s = std::make_unique<CurlRangeStream>();
    if (!s->open(url, headers, err)) return nullptr;
    return s;
}

} // namespace square
