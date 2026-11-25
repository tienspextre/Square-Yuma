#include "decoder.h"

#include "miniaudio.h"
#include <opusfile.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <limits>
#include <vector>

namespace square {

namespace {

std::string lowerExtension(const std::string& uri) {
    const size_t q = uri.find('?');
    const std::string clean = (q == std::string::npos) ? uri : uri.substr(0, q);
    const size_t dot = clean.find_last_of('.');
    if (dot == std::string::npos) return {};
    std::string ext = clean.substr(dot + 1);
    for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return ext;
}

SampleFormat fromMaFormat(ma_format f) {
    switch (f) {
    case ma_format_u8: return SampleFormat::U8;
    case ma_format_s16: return SampleFormat::S16;
    case ma_format_s24: return SampleFormat::S24;
    case ma_format_s32: return SampleFormat::S32;
    case ma_format_f32: return SampleFormat::F32;
    default: return SampleFormat::S16;
    }
}

uint16_t bitsForMaFormat(ma_format f) {
    switch (f) {
    case ma_format_u8: return 8;
    case ma_format_s16: return 16;
    case ma_format_s24: return 24;
    case ma_format_s32:
    case ma_format_f32: return 32;
    default: return 16;
    }
}

std::string detectCodecFromHeader(const uint8_t* hdr, size_t n, const std::string& ext) {
    if (n >= 4 && std::memcmp(hdr, "fLaC", 4) == 0) return "FLAC";
    if (n >= 12 && std::memcmp(hdr, "RIFF", 4) == 0 && std::memcmp(hdr + 8, "WAVE", 4) == 0) return "WAV";
    if (n >= 4 && std::memcmp(hdr, "OggS", 4) == 0) {
        for (size_t i = 0; i + 8 <= n; ++i) {
            if (std::memcmp(hdr + i, "OpusHead", 8) == 0) return "Opus";
            if (std::memcmp(hdr + i, "\x01vorbis", 7) == 0) return "Vorbis";
        }
        if (ext == "opus") return "Opus";
        return "Vorbis";
    }
    if (n >= 3 && std::memcmp(hdr, "ID3", 3) == 0) return "MP3";
    if (n >= 2 && hdr[0] == 0xFF && (hdr[1] & 0xE0) == 0xE0) return "MP3";
    if (n >= 8 && std::memcmp(hdr + 4, "ftyp", 4) == 0) return "AAC";
    if (ext == "flac") return "FLAC";
    if (ext == "wav") return "WAV";
    if (ext == "ogg" || ext == "oga") return "Vorbis";
    if (ext == "opus") return "Opus";
    if (ext == "mp3") return "MP3";
    if (ext == "m4a" || ext == "aac" || ext == "mp4") return "AAC";
    return "PCM";
}

// --- Miniaudio Decoder (WAV, FLAC, MP3, Ogg Vorbis) ---

class MiniaudioDecoder final : public AudioDecoder {
public:
    ~MiniaudioDecoder() override { close(); }

    bool open(std::unique_ptr<AudioInputStream> stream, const std::string& codecName, std::string& err) {
        stream_ = std::move(stream);
        ma_decoder_config cfg = ma_decoder_config_init(ma_format_unknown, 0, 0);
        const ma_result rc = ma_decoder_init(&MiniaudioDecoder::onRead, &MiniaudioDecoder::onSeek, this, &cfg, &dec_);
        if (rc != MA_SUCCESS) {
            err = "miniaudio failed to initialize decoder (code " + std::to_string(static_cast<int>(rc)) + ")";
            return false;
        }
        initialized_ = true;

        ma_uint64 len = 0;
        const ma_result lenRc = ma_decoder_get_length_in_pcm_frames(&dec_, &len);

        info_.codec = codecName;
        info_.sampleRate = dec_.outputSampleRate > 0 ? dec_.outputSampleRate : 44100;
        info_.channels = dec_.outputChannels > 0 ? static_cast<uint16_t>(dec_.outputChannels) : 2;
        info_.nativeFormat = fromMaFormat(dec_.outputFormat);
        info_.bitsPerSample = bitsForMaFormat(dec_.outputFormat);
        info_.totalFrames = (lenRc == MA_SUCCESS && len > 0) ? static_cast<int64_t>(len) : -1;
        info_.durationMs = (info_.totalFrames > 0)
            ? (info_.totalFrames * 1000LL / static_cast<int64_t>(info_.sampleRate))
            : 0;
        info_.lossless = (codecName == "FLAC" || codecName == "WAV");
        return true;
    }

    const TrackFormatInfo& format() const override { return info_; }

    size_t readFloatFrames(float* interleavedOut, size_t maxFrames) override {
        if (!initialized_ || !interleavedOut || maxFrames == 0) return 0;
        if (dec_.outputFormat == ma_format_f32) {
            ma_uint64 got = 0;
            ma_decoder_read_pcm_frames(&dec_, interleavedOut, maxFrames, &got);
            return static_cast<size_t>(got);
        }

        const size_t bytesPerFrame = ma_get_bytes_per_frame(dec_.outputFormat, dec_.outputChannels);
        rawScratch_.resize(maxFrames * bytesPerFrame);
        ma_uint64 got = 0;
        ma_decoder_read_pcm_frames(&dec_, rawScratch_.data(), maxFrames, &got);
        if (got == 0) return 0;

        ma_pcm_convert(
            interleavedOut, ma_format_f32,
            rawScratch_.data(), dec_.outputFormat,
            got * dec_.outputChannels,
            ma_dither_mode_none);
        return static_cast<size_t>(got);
    }

    size_t readRawFrames(void* out, size_t maxFrames) override {
        if (!initialized_ || !out || maxFrames == 0) return 0;
        ma_uint64 got = 0;
        ma_decoder_read_pcm_frames(&dec_, out, maxFrames, &got);
        return static_cast<size_t>(got);
    }

    bool seekFrame(int64_t frameIndex) override {
        if (!initialized_) return false;
        const ma_uint64 target = frameIndex > 0 ? static_cast<ma_uint64>(frameIndex) : 0;
        return ma_decoder_seek_to_pcm_frame(&dec_, target) == MA_SUCCESS;
    }

    void close() override {
        if (initialized_) {
            ma_decoder_uninit(&dec_);
            initialized_ = false;
        }
        if (stream_) {
            stream_->close();
            stream_.reset();
        }
    }

private:
    static ma_result onRead(ma_decoder* pDecoder, void* pBufferOut, size_t bytesToRead, size_t* pBytesRead) {
        auto* self = static_cast<MiniaudioDecoder*>(pDecoder->pUserData);
        if (!self || !self->stream_) {
            if (pBytesRead) *pBytesRead = 0;
            return MA_ERROR;
        }
        const size_t got = self->stream_->read(pBufferOut, bytesToRead);
        if (pBytesRead) *pBytesRead = got;
        return (got == 0) ? MA_AT_END : MA_SUCCESS;
    }

    static ma_result onSeek(ma_decoder* pDecoder, ma_int64 byteOffset, ma_seek_origin origin) {
        auto* self = static_cast<MiniaudioDecoder*>(pDecoder->pUserData);
        if (!self || !self->stream_) return MA_ERROR;
        int whence = SEEK_SET;
        if (origin == ma_seek_origin_current) whence = SEEK_CUR;
        else if (origin == ma_seek_origin_end) whence = SEEK_END;
        return self->stream_->seek(byteOffset, whence) ? MA_SUCCESS : MA_ERROR;
    }

    std::unique_ptr<AudioInputStream> stream_;
    ma_decoder dec_{};
    bool initialized_ = false;
    TrackFormatInfo info_{};
    std::vector<uint8_t> rawScratch_;
};

// --- Opus Decoder (via opusfile) ---

class OpusAudioDecoder final : public AudioDecoder {
public:
    ~OpusAudioDecoder() override { close(); }

    bool open(std::unique_ptr<AudioInputStream> stream, std::string& err) {
        stream_ = std::move(stream);
        static const OpusFileCallbacks cb = {
            &OpusAudioDecoder::cbRead,
            &OpusAudioDecoder::cbSeek,
            &OpusAudioDecoder::cbTell,
            &OpusAudioDecoder::cbClose,
        };
        int rc = 0;
        of_ = op_open_callbacks(this, &cb, nullptr, 0, &rc);
        if (!of_) {
            err = "opusfile failed to open stream (code " + std::to_string(rc) + ")";
            return false;
        }
        const OpusHead* head = op_head(of_, -1);
        const int ch = (head && head->channel_count > 0) ? head->channel_count : 2;
        const ogg_int64_t total = op_pcm_total(of_, -1);

        info_.codec = "Opus";
        info_.sampleRate = 48000;
        info_.channels = static_cast<uint16_t>(ch);
        info_.bitsPerSample = 32;
        info_.nativeFormat = SampleFormat::F32;
        info_.totalFrames = total > 0 ? static_cast<int64_t>(total) : -1;
        info_.durationMs = (info_.totalFrames > 0) ? (info_.totalFrames * 1000LL / 48000LL) : 0;
        info_.lossless = false;
        return true;
    }

    const TrackFormatInfo& format() const override { return info_; }

    size_t readFloatFrames(float* interleavedOut, size_t maxFrames) override {
        if (!of_ || !interleavedOut || maxFrames == 0) return 0;
        size_t totalGot = 0;
        const int ch = info_.channels;
        while (totalGot < maxFrames) {
            const int wantSamples = static_cast<int>(maxFrames - totalGot) * ch;
            const int got = op_read_float(of_, interleavedOut + totalGot * ch, wantSamples, nullptr);
            if (got <= 0) break;
            totalGot += static_cast<size_t>(got);
        }
        return totalGot;
    }

    size_t readRawFrames(void* out, size_t maxFrames) override {
        return readFloatFrames(static_cast<float*>(out), maxFrames);
    }

    bool seekFrame(int64_t frameIndex) override {
        if (!of_) return false;
        return op_pcm_seek(of_, frameIndex > 0 ? static_cast<ogg_int64_t>(frameIndex) : 0) == 0;
    }

    void close() override {
        if (of_) {
            op_free(of_);
            of_ = nullptr;
        }
        if (stream_) {
            stream_->close();
            stream_.reset();
        }
    }

private:
    static int cbRead(void* ctx, unsigned char* ptr, int nbytes) {
        auto* self = static_cast<OpusAudioDecoder*>(ctx);
        if (!self || !self->stream_ || nbytes < 0) return -1;
        return static_cast<int>(self->stream_->read(ptr, static_cast<size_t>(nbytes)));
    }
    static int cbSeek(void* ctx, opus_int64 offset, int whence) {
        auto* self = static_cast<OpusAudioDecoder*>(ctx);
        if (!self || !self->stream_ || !self->stream_->isSeekable()) return -1;
        return self->stream_->seek(static_cast<int64_t>(offset), whence) ? 0 : -1;
    }
    static opus_int64 cbTell(void* ctx) {
        auto* self = static_cast<OpusAudioDecoder*>(ctx);
        if (!self || !self->stream_) return -1;
        return static_cast<opus_int64>(self->stream_->tell());
    }
    static int cbClose(void* /*ctx*/) { return 0; }

    std::unique_ptr<AudioInputStream> stream_;
    OggOpusFile* of_ = nullptr;
    TrackFormatInfo info_{};
};

} // namespace

#ifndef _WIN32
std::unique_ptr<AudioDecoder> createMfAacDecoder(
    std::unique_ptr<AudioInputStream> /*stream*/,
    const std::string& /*uriHint*/,
    std::string& err) {
    err = "AAC/M4A decoding requires Windows Media Foundation (unsupported on Linux dev build)";
    return nullptr;
}
#endif

std::unique_ptr<AudioDecoder> createDecoder(
    std::unique_ptr<AudioInputStream> stream,
    const std::string& uriHint,
    std::string& err) {
    if (!stream) {
        err = "Null audio input stream";
        return nullptr;
    }

    uint8_t header[64]{};
    const size_t n = stream->read(header, sizeof(header));
    stream->seek(0, SEEK_SET);

    const std::string ext = lowerExtension(uriHint);
    const std::string codec = detectCodecFromHeader(header, n, ext);

    if (codec == "Opus") {
        auto dec = std::make_unique<OpusAudioDecoder>();
        if (dec->open(std::move(stream), err)) return dec;
        return nullptr;
    }
    if (codec == "AAC") {
        return createMfAacDecoder(std::move(stream), uriHint, err);
    }

    auto dec = std::make_unique<MiniaudioDecoder>();
    if (dec->open(std::move(stream), codec, err)) {
        return dec;
    }
    return nullptr;
}

bool probeAudioUri(
    const std::string& uri,
    const std::string& headersJson,
    TrackFormatInfo& outInfo,
    std::string& err) {
    auto stream = createInputStream(uri, headersJson, err);
    if (!stream) return false;
    auto dec = createDecoder(std::move(stream), uri, err);
    if (!dec) return false;
    outInfo = dec->format();
    dec->close();
    return true;
}

bool decodeAudioToFloat(
    const std::string& uri,
    const std::string& headersJson,
    int64_t fromMs,
    int64_t durationMs,
    uint32_t targetRate,
    uint16_t targetChannels,
    std::vector<float>& outSamples,
    std::string& err) {
    outSamples.clear();
    auto stream = createInputStream(uri, headersJson, err);
    if (!stream) return false;
    auto dec = createDecoder(std::move(stream), uri, err);
    if (!dec) return false;

    const TrackFormatInfo fmt = dec->format();
    const uint32_t dstRate = targetRate > 0 ? targetRate : fmt.sampleRate;
    const uint16_t dstCh = targetChannels > 0 ? targetChannels : fmt.channels;

    if (fromMs > 0) {
        const int64_t startFrame = fromMs * static_cast<int64_t>(fmt.sampleRate) / 1000LL;
        dec->seekFrame(startFrame);
    }

    const int64_t maxSrcFrames = (durationMs > 0)
        ? (durationMs * static_cast<int64_t>(fmt.sampleRate) / 1000LL)
        : std::numeric_limits<int64_t>::max();

    const bool needResample = (dstRate != fmt.sampleRate);
    ma_resampler resampler{};
    bool resamplerInit = false;
    if (needResample) {
        ma_resampler_config rcfg = ma_resampler_config_init(
            ma_format_f32, dstCh, fmt.sampleRate, dstRate, ma_resample_algorithm_linear);
        if (ma_resampler_init(&rcfg, nullptr, &resampler) != MA_SUCCESS) {
            err = "Failed to initialize resampler";
            return false;
        }
        resamplerInit = true;
    }

    constexpr size_t kChunkFrames = 4096;
    std::vector<float> srcBuf(kChunkFrames * fmt.channels);
    std::vector<float> chBuf(kChunkFrames * dstCh);
    std::vector<float> rsOut(kChunkFrames * dstCh * 4);

    int64_t readFramesTotal = 0;
    while (readFramesTotal < maxSrcFrames) {
        const size_t want = static_cast<size_t>(std::min<int64_t>(kChunkFrames, maxSrcFrames - readFramesTotal));
        const size_t got = dec->readFloatFrames(srcBuf.data(), want);
        if (got == 0) break;
        readFramesTotal += static_cast<int64_t>(got);

        const float* channelConverted = srcBuf.data();
        if (fmt.channels != dstCh) {
            if (dstCh == 1 && fmt.channels >= 2) {
                for (size_t i = 0; i < got; ++i) {
                    float sum = 0.0f;
                    for (uint16_t c = 0; c < fmt.channels; ++c) {
                        sum += srcBuf[i * fmt.channels + c];
                    }
                    chBuf[i] = sum / static_cast<float>(fmt.channels);
                }
            } else if (dstCh == 2 && fmt.channels == 1) {
                for (size_t i = 0; i < got; ++i) {
                    chBuf[i * 2] = srcBuf[i];
                    chBuf[i * 2 + 1] = srcBuf[i];
                }
            } else {
                for (size_t i = 0; i < got; ++i) {
                    for (uint16_t c = 0; c < dstCh; ++c) {
                        chBuf[i * dstCh + c] = (c < fmt.channels) ? srcBuf[i * fmt.channels + c] : 0.0f;
                    }
                }
            }
            channelConverted = chBuf.data();
        }

        if (needResample) {
            ma_uint64 inCount = got;
            ma_uint64 outCount = rsOut.size() / dstCh;
            ma_resampler_process_pcm_frames(&resampler, channelConverted, &inCount, rsOut.data(), &outCount);
            if (outCount > 0) {
                outSamples.insert(outSamples.end(), rsOut.data(), rsOut.data() + outCount * dstCh);
            }
        } else {
            outSamples.insert(outSamples.end(), channelConverted, channelConverted + got * dstCh);
        }
    }

    if (resamplerInit) {
        ma_resampler_uninit(&resampler, nullptr);
    }
    dec->close();
    return !outSamples.empty();
}

} // namespace square
