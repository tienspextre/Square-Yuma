#include "audio_stream.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <cstring>

#ifdef _WIN32
#include <windows.h>
#endif

namespace square {

namespace {
bool startsWithInsensitive(const std::string& s, const char* prefix) {
    const size_t n = std::strlen(prefix);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; ++i) {
        if (std::tolower(static_cast<unsigned char>(s[i])) != std::tolower(static_cast<unsigned char>(prefix[i]))) {
            return false;
        }
    }
    return true;
}

int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string urlDecode(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        if (in[i] == '%' && i + 2 < in.size()) {
            const int hi = hexVal(in[i + 1]);
            const int lo = hexVal(in[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 2;
                continue;
            }
        }
        out.push_back(in[i]);
    }
    return out;
}
} // namespace

bool isHttpUrl(const std::string& uri) {
    return startsWithInsensitive(uri, "http://") || startsWithInsensitive(uri, "https://");
}

std::string normalizeFileUri(const std::string& uri) {
    if (!startsWithInsensitive(uri, "file://")) {
        return uri;
    }
    std::string p = urlDecode(uri.substr(7));
#ifdef _WIN32
    // file:///C:/path -> C:/path
    if (p.size() >= 3 && p[0] == '/' && std::isalpha(static_cast<unsigned char>(p[1])) && p[2] == ':') {
        p = p.substr(1);
    }
#endif
    return p;
}

std::map<std::string, std::string> parseHeadersJson(const std::string& jsonStr) {
    std::map<std::string, std::string> out;
    if (jsonStr.empty()) return out;
    auto parsed = nlohmann::json::parse(jsonStr, nullptr, false);
    if (!parsed.is_object()) return out;
    for (auto it = parsed.begin(); it != parsed.end(); ++it) {
        if (it.value().is_string()) {
            out[it.key()] = it.value().get<std::string>();
        }
    }
    return out;
}

FileInputStream::~FileInputStream() {
    close();
}

bool FileInputStream::open(const std::string& path, std::string& err) {
    close();
#ifdef _WIN32
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    if (wlen <= 0) {
        err = "Invalid UTF-8 file path";
        return false;
    }
    std::wstring wpath(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wpath.data(), wlen);
    file_ = _wfopen(wpath.c_str(), L"rb");
#else
    file_ = std::fopen(path.c_str(), "rb");
#endif
    if (!file_) {
        err = "Failed to open local file: " + path;
        return false;
    }

#ifdef _WIN32
    _fseeki64(file_, 0, SEEK_END);
    size_ = _ftelli64(file_);
    _fseeki64(file_, 0, SEEK_SET);
#else
    fseeko(file_, 0, SEEK_END);
    size_ = static_cast<int64_t>(ftello(file_));
    fseeko(file_, 0, SEEK_SET);
#endif
    pos_ = 0;
    return true;
}

size_t FileInputStream::read(void* dst, size_t bytes) {
    if (!file_ || !dst || bytes == 0) return 0;
    const size_t n = std::fread(dst, 1, bytes, file_);
    pos_ += static_cast<int64_t>(n);
    return n;
}

bool FileInputStream::seek(int64_t offset, int whence) {
    if (!file_) return false;
    int64_t target = pos_;
    if (whence == SEEK_SET) target = offset;
    else if (whence == SEEK_CUR) target = pos_ + offset;
    else if (whence == SEEK_END) {
        if (size_ < 0) return false;
        target = size_ + offset;
    } else {
        return false;
    }
    if (target < 0) target = 0;
    if (size_ >= 0 && target > size_) target = size_;

#ifdef _WIN32
    if (_fseeki64(file_, target, SEEK_SET) != 0) return false;
#else
    if (fseeko(file_, static_cast<off_t>(target), SEEK_SET) != 0) return false;
#endif
    pos_ = target;
    return true;
}

int64_t FileInputStream::tell() const {
    return pos_;
}

void FileInputStream::close() {
    if (file_) {
        std::fclose(file_);
        file_ = nullptr;
    }
    size_ = -1;
    pos_ = 0;
}

std::unique_ptr<AudioInputStream> createInputStream(
    const std::string& uri,
    const std::string& headersJson,
    std::string& err) {
    if (isHttpUrl(uri)) {
        return createHttpInputStream(uri, parseHeadersJson(headersJson), err);
    }
    auto fs = std::make_unique<FileInputStream>();
    if (!fs->open(normalizeFileUri(uri), err)) {
        return nullptr;
    }
    return fs;
}

} // namespace square
