#pragma once

#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <string>

namespace square {

class AudioInputStream {
public:
    virtual ~AudioInputStream() = default;
    virtual size_t read(void* dst, size_t bytes) = 0;
    virtual bool seek(int64_t offset, int whence) = 0; // SEEK_SET / SEEK_CUR / SEEK_END
    virtual int64_t tell() const = 0;
    virtual int64_t size() const = 0; // -1 if unknown
    virtual bool isSeekable() const = 0;
    virtual void close() = 0;
};

class FileInputStream final : public AudioInputStream {
public:
    ~FileInputStream() override;
    bool open(const std::string& path, std::string& err);

    size_t read(void* dst, size_t bytes) override;
    bool seek(int64_t offset, int whence) override;
    int64_t tell() const override;
    int64_t size() const override { return size_; }
    bool isSeekable() const override { return file_ != nullptr; }
    void close() override;

private:
    std::FILE* file_ = nullptr;
    int64_t size_ = -1;
    int64_t pos_ = 0;
};

bool isHttpUrl(const std::string& uri);
std::map<std::string, std::string> parseHeadersJson(const std::string& jsonStr);
std::string normalizeFileUri(const std::string& uri);

std::unique_ptr<AudioInputStream> createInputStream(
    const std::string& uri,
    const std::string& headersJson,
    std::string& err);

// Platform HTTP stream factory (WinHTTP on Windows; libcurl or stub on Linux)
std::unique_ptr<AudioInputStream> createHttpInputStream(
    const std::string& url,
    const std::map<std::string, std::string>& headers,
    std::string& err);

} // namespace square
