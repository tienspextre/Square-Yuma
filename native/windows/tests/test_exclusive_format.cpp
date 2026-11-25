#include "dsp_chain.h"

#include <cassert>
#include <iostream>

int main() {
    using namespace square;

    // 1. Shared requested -> never uses Exclusive
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = false;
        req.dspAllOff = true;
        req.sourceSampleRate = 96000;
        req.sourceBitsPerSample = 24;
        const auto d = selectExclusiveFormat(req);
        assert(!d.useExclusive);
        assert(d.fallbackReason.empty());
    }

    // 2. Exclusive requested, DSP on -> falls back with reason
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = true;
        req.dspAllOff = false;
        req.sourceSampleRate = 44100;
        req.sourceBitsPerSample = 16;
        const auto d = selectExclusiveFormat(req);
        assert(!d.useExclusive);
        assert(!d.fallbackReason.empty());
    }

    // 3. Exclusive requested, rate > 384 kHz -> falls back with reason
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = true;
        req.dspAllOff = true;
        req.sourceSampleRate = 768000;
        req.sourceBitsPerSample = 32;
        const auto d = selectExclusiveFormat(req);
        assert(!d.useExclusive);
        assert(d.fallbackReason.find("384 kHz") != std::string::npos);
    }

    // 4. Exclusive requested, DSP off, 24-bit/192 kHz supported by device
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = true;
        req.dspAllOff = true;
        req.sourceSampleRate = 192000;
        req.sourceBitsPerSample = 24;
        req.sourceChannels = 2;
        req.deviceCapabilities = {
            {48000, 16, 2, false},
            {192000, 24, 2, false},
        };
        const auto d = selectExclusiveFormat(req);
        assert(d.useExclusive);
        assert(d.targetSampleRate == 192000);
        assert(d.targetBitsPerSample == 24);
        assert(d.targetChannels == 2);
        assert(!d.targetIsFloat);
    }

    // 5. Exclusive 24-bit source on device exposing 32-bit PCM container -> allowed
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = true;
        req.dspAllOff = true;
        req.sourceSampleRate = 96000;
        req.sourceBitsPerSample = 24;
        req.sourceChannels = 2;
        req.deviceCapabilities = {
            {96000, 32, 2, false},
        };
        const auto d = selectExclusiveFormat(req);
        assert(d.useExclusive);
        assert(d.targetSampleRate == 96000);
        assert(d.targetBitsPerSample == 32);
    }

    // 6. Device lacks rate -> falls back with reason
    {
        ExclusiveFormatRequest req;
        req.exclusiveRequested = true;
        req.dspAllOff = true;
        req.sourceSampleRate = 176400;
        req.sourceBitsPerSample = 24;
        req.sourceChannels = 2;
        req.deviceCapabilities = {
            {48000, 24, 2, false},
        };
        const auto d = selectExclusiveFormat(req);
        assert(!d.useExclusive);
        assert(!d.fallbackReason.empty());
    }

    std::cout << "test_exclusive_format passed\n";
    return 0;
}
