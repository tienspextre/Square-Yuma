#pragma once

#include "audio_stream.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace square {

enum class SampleFormat {
    U8,
    S16,
    S24,
    S32,
    F32,
};

struct TrackFormatInfo {
    std::string codec;
    uint32_t sampleRate = 44100;
    uint16_t channels = 2;
    uint16_t bitsPerSample = 16;
    SampleFormat nativeFormat = SampleFormat::S16;
    int64_t totalFrames = -1;
    int64_t durationMs = 0;
    bool lossless = false;
};

class AudioDecoder {
public:
    virtual ~AudioDecoder() = default;
    virtual const TrackFormatInfo& format() const = 0;
    virtual size_t readFloatFrames(float* interleavedOut, size_t maxFrames) = 0;
    virtual size_t readRawFrames(void* out, size_t maxFrames) = 0;
    virtual bool seekFrame(int64_t frameIndex) = 0;
    virtual void close() = 0;
};

std::unique_ptr<AudioDecoder> createDecoder(
    std::unique_ptr<AudioInputStream> stream,
    const std::string& uriHint,
    std::string& err);

// Windows Media Foundation AAC/M4A decoder (implemented in mf_aac_decoder.cpp on Windows)
std::unique_ptr<AudioDecoder> createMfAacDecoder(
    std::unique_ptr<AudioInputStream> stream,
    const std::string& uriHint,
    std::string& err);

bool probeAudioUri(
    const std::string& uri,
    const std::string& headersJson,
    TrackFormatInfo& outInfo,
    std::string& err);

bool decodeAudioToFloat(
    const std::string& uri,
    const std::string& headersJson,
    int64_t fromMs,
    int64_t durationMs,
    uint32_t targetRate,
    uint16_t targetChannels,
    std::vector<float>& outSamples,
    std::string& err);

} // namespace square
