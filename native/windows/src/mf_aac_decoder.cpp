#include "decoder.h"

#ifdef _WIN32

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <propvarutil.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <vector>

namespace square {

namespace {

std::once_flag gMfInitOnce;
HRESULT gMfInitHr = E_FAIL;

HRESULT ensureMfInitialized() {
    std::call_once(gMfInitOnce, []() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        gMfInitHr = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    });
    return gMfInitHr;
}

class StreamByteStream final : public IMFByteStream {
public:
    explicit StreamByteStream(std::unique_ptr<AudioInputStream> s) : stream_(std::move(s)) {}
    ~StreamByteStream() = default;

    ULONG STDMETHODCALLTYPE AddRef() override { return ++refCount_; }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG n = --refCount_;
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IMFByteStream) {
            *ppv = static_cast<IMFByteStream*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    HRESULT STDMETHODCALLTYPE GetCapabilities(DWORD* pdwCapabilities) override {
        if (!pdwCapabilities) return E_POINTER;
        DWORD caps = MFBYTESTREAM_IS_READABLE;
        if (stream_ && stream_->isSeekable()) caps |= MFBYTESTREAM_IS_SEEKABLE;
        *pdwCapabilities = caps;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE GetLength(QWORD* pqwLength) override {
        if (!pqwLength) return E_POINTER;
        const int64_t sz = stream_ ? stream_->size() : -1;
        if (sz < 0) return E_NOTIMPL;
        *pqwLength = static_cast<QWORD>(sz);
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE SetLength(QWORD) override { return E_NOTIMPL; }

    HRESULT STDMETHODCALLTYPE GetCurrentPosition(QWORD* pqwPosition) override {
        if (!pqwPosition) return E_POINTER;
        *pqwPosition = stream_ ? static_cast<QWORD>(std::max<int64_t>(0, stream_->tell())) : 0;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE SetCurrentPosition(QWORD qwPosition) override {
        if (!stream_) return E_FAIL;
        return stream_->seek(static_cast<int64_t>(qwPosition), SEEK_SET) ? S_OK : E_FAIL;
    }

    HRESULT STDMETHODCALLTYPE IsEndOfStream(BOOL* pfEndOfStream) override {
        if (!pfEndOfStream) return E_POINTER;
        const int64_t sz = stream_ ? stream_->size() : -1;
        *pfEndOfStream = (sz >= 0 && stream_->tell() >= sz) ? TRUE : FALSE;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE Read(BYTE* pb, ULONG cb, ULONG* pcbRead) override {
        if (!pb || !pcbRead) return E_POINTER;
        if (!stream_) { *pcbRead = 0; return E_FAIL; }
        std::lock_guard<std::mutex> lock(mu_);
        *pcbRead = static_cast<ULONG>(stream_->read(pb, cb));
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE BeginRead(BYTE*, ULONG, IMFAsyncCallback*, IUnknown*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE EndRead(IMFAsyncResult*, ULONG*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Write(const BYTE*, ULONG, ULONG*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE BeginWrite(const BYTE*, ULONG, IMFAsyncCallback*, IUnknown*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE EndWrite(IMFAsyncResult*, ULONG*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Seek(MFBYTESTREAM_SEEK_ORIGIN SeekOrigin, LONGLONG llSeekOffset, DWORD, QWORD* pqwCurrentPosition) override {
        if (!stream_) return E_FAIL;
        std::lock_guard<std::mutex> lock(mu_);
        int whence = SEEK_SET;
        if (SeekOrigin == msoCurrent) whence = SEEK_CUR;
        if (!stream_->seek(llSeekOffset, whence)) return E_FAIL;
        if (pqwCurrentPosition) *pqwCurrentPosition = static_cast<QWORD>(stream_->tell());
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Flush() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE Close() override {
        std::lock_guard<std::mutex> lock(mu_);
        if (stream_) stream_->close();
        return S_OK;
    }

private:
    std::atomic<ULONG> refCount_{1};
    std::mutex mu_;
    std::unique_ptr<AudioInputStream> stream_;
};

class MfAacDecoder final : public AudioDecoder {
public:
    ~MfAacDecoder() override { close(); }

    bool open(std::unique_ptr<AudioInputStream> stream, std::string& err) {
        if (FAILED(ensureMfInitialized())) {
            err = "Media Foundation initialization failed";
            return false;
        }

        byteStream_ = new StreamByteStream(std::move(stream));
        IMFSourceResolver* resolver = nullptr;
        MF_OBJECT_TYPE objType = MF_OBJECT_INVALID;
        IUnknown* sourceUnk = nullptr;
        IMFMediaSource* mediaSource = nullptr;

        HRESULT hr = MFCreateSourceResolver(&resolver);
        if (SUCCEEDED(hr)) {
            hr = resolver->CreateObjectFromByteStream(byteStream_, nullptr, MF_RESOLUTION_MEDIASOURCE, nullptr, &objType, &sourceUnk);
        }
        if (SUCCEEDED(hr) && sourceUnk) {
            hr = sourceUnk->QueryInterface(IID_PPV_ARGS(&mediaSource));
        }
        if (SUCCEEDED(hr) && mediaSource) {
            hr = MFCreateSourceReaderFromMediaSource(mediaSource, nullptr, &reader_);
        }
        if (mediaSource) mediaSource->Release();
        if (sourceUnk) sourceUnk->Release();
        if (resolver) resolver->Release();

        if (FAILED(hr) || !reader_) {
            err = "Media Foundation failed to open AAC/M4A source";
            close();
            return false;
        }

        IMFMediaType* partialType = nullptr;
        hr = MFCreateMediaType(&partialType);
        if (SUCCEEDED(hr)) {
            partialType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
            partialType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_Float);
            hr = reader_->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, partialType);
            partialType->Release();
        }
        if (FAILED(hr)) {
            err = "Media Foundation failed to configure float PCM output";
            close();
            return false;
        }

        IMFMediaType* actualType = nullptr;
        hr = reader_->GetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, &actualType);
        if (SUCCEEDED(hr) && actualType) {
            UINT32 ch = 2, sr = 44100;
            actualType->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &ch);
            actualType->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &sr);
            info_.channels = static_cast<uint16_t>(std::max<UINT32>(1, ch));
            info_.sampleRate = std::max<UINT32>(8000, sr);
            actualType->Release();
        }

        PROPVARIANT var{};
        PropVariantInit(&var);
        if (SUCCEEDED(reader_->GetPresentationAttribute(MF_SOURCE_READER_MEDIASOURCE, MF_PD_DURATION, &var))) {
            if (var.vt == VT_UI8) {
                const int64_t hns = static_cast<int64_t>(var.uhVal.QuadPart); // 100-ns units
                info_.durationMs = hns / 10000LL;
                info_.totalFrames = (info_.durationMs * info_.sampleRate) / 1000LL;
            }
            PropVariantClear(&var);
        }

        info_.codec = "AAC";
        info_.bitsPerSample = 32;
        info_.nativeFormat = SampleFormat::F32;
        info_.lossless = false;
        return true;
    }

    const TrackFormatInfo& format() const override { return info_; }

    size_t readFloatFrames(float* interleavedOut, size_t maxFrames) override {
        if (!reader_ || !interleavedOut || maxFrames == 0) return 0;
        const size_t ch = info_.channels;
        size_t written = 0;

        while (written < maxFrames) {
            if (leftoverPos_ < leftover_.size()) {
                const size_t availFrames = (leftover_.size() - leftoverPos_) / ch;
                const size_t takeFrames = std::min(availFrames, maxFrames - written);
                std::memcpy(interleavedOut + written * ch, leftover_.data() + leftoverPos_, takeFrames * ch * sizeof(float));
                leftoverPos_ += takeFrames * ch;
                written += takeFrames;
                if (leftoverPos_ >= leftover_.size()) {
                    leftover_.clear();
                    leftoverPos_ = 0;
                }
                continue;
            }

            DWORD flags = 0;
            IMFSample* sample = nullptr;
            const HRESULT hr = reader_->ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, nullptr, &flags, nullptr, &sample);
            if (FAILED(hr) || (flags & MF_SOURCE_READERF_ENDOFSTREAM)) {
                if (sample) sample->Release();
                break;
            }
            if (!sample) continue;

            IMFMediaBuffer* buf = nullptr;
            if (SUCCEEDED(sample->ConvertToContiguousBuffer(&buf)) && buf) {
                BYTE* data = nullptr;
                DWORD len = 0;
                if (SUCCEEDED(buf->Lock(&data, nullptr, &len)) && data && len > 0) {
                    const size_t floatCount = len / sizeof(float);
                    const float* fdata = reinterpret_cast<const float*>(data);
                    leftover_.assign(fdata, fdata + floatCount);
                    leftoverPos_ = 0;
                    buf->Unlock();
                }
                buf->Release();
            }
            sample->Release();
        }
        return written;
    }

    size_t readRawFrames(void* out, size_t maxFrames) override {
        return readFloatFrames(static_cast<float*>(out), maxFrames);
    }

    bool seekFrame(int64_t frameIndex) override {
        if (!reader_) return false;
        leftover_.clear();
        leftoverPos_ = 0;
        const int64_t hns = (frameIndex > 0 ? frameIndex : 0) * 10000000LL / static_cast<int64_t>(info_.sampleRate);
        PROPVARIANT var{};
        PropVariantInit(&var);
        var.vt = VT_I8;
        var.hVal.QuadPart = hns;
        const HRESULT hr = reader_->SetCurrentPosition(GUID_NULL, var);
        PropVariantClear(&var);
        return SUCCEEDED(hr);
    }

    void close() override {
        if (reader_) { reader_->Release(); reader_ = nullptr; }
        if (byteStream_) { byteStream_->Close(); byteStream_->Release(); byteStream_ = nullptr; }
        leftover_.clear();
        leftoverPos_ = 0;
    }

private:
    StreamByteStream* byteStream_ = nullptr;
    IMFSourceReader* reader_ = nullptr;
    TrackFormatInfo info_{};
    std::vector<float> leftover_;
    size_t leftoverPos_ = 0;
};

} // namespace

std::unique_ptr<AudioDecoder> createMfAacDecoder(
    std::unique_ptr<AudioInputStream> stream,
    const std::string& /*uriHint*/,
    std::string& err) {
    auto dec = std::make_unique<MfAacDecoder>();
    if (!dec->open(std::move(stream), err)) return nullptr;
    return dec;
}

} // namespace square

#endif // _WIN32
