#include "decoder.h"
#include "engine.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <vector>

namespace {

void writeLe16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

void writeLe32(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

void writeBe16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(v & 0xFF));
}

void writeBe32(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(v & 0xFF));
}

uint8_t crc8Flac(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int b = 0; b < 8; ++b) {
            crc = (crc & 0x80) ? static_cast<uint8_t>((crc << 1) ^ 0x07) : static_cast<uint8_t>(crc << 1);
        }
    }
    return crc;
}

uint16_t crc16Flac(const uint8_t* data, size_t len) {
    uint16_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int b = 0; b < 8; ++b) {
            crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x8005) : static_cast<uint16_t>(crc << 1);
        }
    }
    return crc;
}

std::vector<uint8_t> buildTestWav(int sampleRate, int channels, int frames) {
    std::vector<uint8_t> wav;
    const uint32_t dataBytes = static_cast<uint32_t>(frames * channels * 2);
    wav.insert(wav.end(), {'R', 'I', 'F', 'F'});
    writeLe32(wav, 36 + dataBytes);
    wav.insert(wav.end(), {'W', 'A', 'V', 'E'});
    wav.insert(wav.end(), {'f', 'm', 't', ' '});
    writeLe32(wav, 16);
    writeLe16(wav, 1); // PCM
    writeLe16(wav, static_cast<uint16_t>(channels));
    writeLe32(wav, static_cast<uint32_t>(sampleRate));
    writeLe32(wav, static_cast<uint32_t>(sampleRate * channels * 2));
    writeLe16(wav, static_cast<uint16_t>(channels * 2));
    writeLe16(wav, 16);
    wav.insert(wav.end(), {'d', 'a', 't', 'a'});
    writeLe32(wav, dataBytes);
    for (int i = 0; i < frames; ++i) {
        const double s = 0.5 * std::sin(2.0 * 3.14159265358979323846 * 440.0 * i / sampleRate);
        const int16_t pcm = static_cast<int16_t>(s * 32767.0);
        for (int c = 0; c < channels; ++c) {
            writeLe16(wav, static_cast<uint16_t>(pcm));
        }
    }
    return wav;
}

// Builds a minimal valid 16-bit stereo 44100 Hz FLAC file using VERBATIM subframes.
std::vector<uint8_t> buildTestFlac(int sampleRate, int blockSize, int totalBlocks) {
    const uint64_t totalSamples = static_cast<uint64_t>(blockSize) * totalBlocks;
    std::vector<uint8_t> flac;
    flac.insert(flac.end(), {'f', 'L', 'a', 'C'});

    // STREAMINFO metadata block (last = 1, type = 0, length = 34)
    flac.push_back(0x80);
    flac.push_back(0x00);
    flac.push_back(0x00);
    flac.push_back(34);
    writeBe16(flac, static_cast<uint16_t>(blockSize)); // min block size
    writeBe16(flac, static_cast<uint16_t>(blockSize)); // max block size
    flac.push_back(0); flac.push_back(0); flac.push_back(0); // min frame size
    flac.push_back(0); flac.push_back(0); flac.push_back(0); // max frame size
    // 20 bits sampleRate, 3 bits (channels-1)=1, 5 bits (bps-1)=15, 36 bits totalSamples
    const uint64_t packed =
        (static_cast<uint64_t>(sampleRate) << 44) |
        (static_cast<uint64_t>(1) << 41) |
        (static_cast<uint64_t>(15) << 36) |
        (totalSamples & 0xFFFFFFFFFULL);
    for (int shift = 56; shift >= 0; shift -= 8) {
        flac.push_back(static_cast<uint8_t>((packed >> shift) & 0xFF));
    }
    for (int i = 0; i < 16; ++i) flac.push_back(0); // MD5 (optional zeros)

    for (int b = 0; b < totalBlocks; ++b) {
        const size_t frameStart = flac.size();
        // Frame header: sync 0xFFF8, blocking=0 (fixed), bs=0111 (16-bit bs-1 follows), sr=1001 (44100), ch=0001 (2ch), bps=100 (16-bit)
        flac.push_back(0xFF);
        flac.push_back(0xF8);
        flac.push_back(0x79); // bs code 7 (16-bit-1), sr code 9 (44100)
        flac.push_back(0x18); // ch=1 (stereo indep), bps=4 (16-bit), res=0
        // UTF-8 coded frame number (b < 128)
        flac.push_back(static_cast<uint8_t>(b));
        writeBe16(flac, static_cast<uint16_t>(blockSize - 1));
        flac.push_back(crc8Flac(flac.data() + frameStart, flac.size() - frameStart));

        // Two VERBATIM subframes (header byte: 0b00000010 = 0x02: type=verbatim, wasted=0)
        for (int ch = 0; ch < 2; ++ch) {
            flac.push_back(0x02);
            for (int i = 0; i < blockSize; ++i) {
                const int idx = b * blockSize + i;
                const double s = 0.5 * std::sin(2.0 * 3.14159265358979323846 * 440.0 * idx / sampleRate);
                const int16_t pcm = static_cast<int16_t>(s * 32767.0);
                writeBe16(flac, static_cast<uint16_t>(pcm));
            }
        }

        const uint16_t fcrc = crc16Flac(flac.data() + frameStart, flac.size() - frameStart);
        writeBe16(flac, fcrc);
    }

    return flac;
}

void writeFile(const std::string& path, const std::vector<uint8_t>& data) {
    std::ofstream f(path, std::ios::binary);
    assert(f.good());
    f.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    assert(f.good());
}

} // namespace

int main() {
    using namespace square;

    const std::string wavPath = "test_sample.wav";
    const std::string flacPath = "test_sample.flac";
    writeFile(wavPath, buildTestWav(44100, 2, 44100));
    writeFile(flacPath, buildTestFlac(44100, 1024, 20));

    // WAV probe + decode + seek
    {
        TrackFormatInfo info{};
        std::string err;
        assert(probeAudioUri(wavPath, "", info, err));
        assert(info.codec == "WAV");
        assert(info.sampleRate == 44100);
        assert(info.channels == 2);
        assert(info.bitsPerSample == 16);
        assert(info.lossless);
        assert(info.totalFrames == 44100);

        std::vector<float> samples;
        assert(decodeAudioToFloat(wavPath, "", 250, 500, 11025, 1, samples, err));
        assert(samples.size() > 4000 && samples.size() < 7000);
    }

    // FLAC probe + decode + seek
    {
        TrackFormatInfo info{};
        std::string err;
        assert(probeAudioUri(flacPath, "", info, err));
        assert(info.codec == "FLAC");
        assert(info.sampleRate == 44100);
        assert(info.channels == 2);
        assert(info.bitsPerSample == 16);
        assert(info.lossless);
        assert(info.totalFrames == 20480);

        auto stream = createInputStream(flacPath, "", err);
        assert(stream != nullptr);
        auto dec = createDecoder(std::move(stream), flacPath, err);
        assert(dec != nullptr);
        assert(dec->seekFrame(4096));
        std::vector<float> buf(1024 * 2);
        const size_t got = dec->readFloatFrames(buf.data(), 1024);
        assert(got == 1024);
        double energy = 0.0;
        for (float s : buf) energy += s * s;
        assert(energy > 1.0);
        dec->close();
    }

    // Full engine self-check render (headless)
    {
        NativeAudioEngine engine;
        assert(engine.init(true));
        const SelfCheckReport rep = engine.runSelfCheck();
        assert(rep.ok);
        assert(rep.analyzedDurationSec > 0.5);
        engine.shutdown();
    }

    std::remove(wavPath.c_str());
    std::remove(flacPath.c_str());

    std::cout << "test_decoders passed\n";
    return 0;
}
