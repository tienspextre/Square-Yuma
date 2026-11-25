#include "ring_buffer.h"

#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <numeric>
#include <thread>
#include <vector>

int main() {
    using square::SpscRing;

    // Basic wrap-around and capacity checks
    SpscRing<float> ring(16);
    assert(ring.capacity() == 16);
    assert(ring.availableRead() == 0);
    assert(ring.availableWrite() == 16);

    std::vector<float> in(10);
    std::iota(in.begin(), in.end(), 1.0f);
    assert(ring.write(in.data(), 10) == 10);
    assert(ring.availableRead() == 10);
    assert(ring.availableWrite() == 6);

    std::vector<float> out(6, 0.0f);
    assert(ring.read(out.data(), 6) == 6);
    for (int i = 0; i < 6; ++i) assert(out[i] == static_cast<float>(i + 1));

    // Write past end to force wrap-around
    std::vector<float> in2(12);
    std::iota(in2.begin(), in2.end(), 100.0f);
    assert(ring.write(in2.data(), 12) == 12);
    assert(ring.availableRead() == 16);
    assert(ring.availableWrite() == 0);

    std::vector<float> out2(16, 0.0f);
    assert(ring.read(out2.data(), 16) == 16);
    for (int i = 0; i < 4; ++i) assert(out2[i] == static_cast<float>(i + 7));
    for (int i = 0; i < 12; ++i) assert(out2[4 + i] == static_cast<float>(100 + i));

    // Concurrent SPSC stress test
    SpscRing<int> spsc(1024);
    constexpr int kCount = 200000;
    std::atomic<bool> done{false};

    std::thread prod([&]() {
        int v = 0;
        while (v < kCount) {
            int batch[37];
            const int n = std::min(37, kCount - v);
            for (int i = 0; i < n; ++i) batch[i] = v + i;
            const size_t w = spsc.write(batch, static_cast<size_t>(n));
            v += static_cast<int>(w);
        }
        done.store(true, std::memory_order_release);
    });

    int expected = 0;
    while (expected < kCount) {
        int buf[29];
        const size_t r = spsc.read(buf, 29);
        for (size_t i = 0; i < r; ++i) {
            assert(buf[i] == expected);
            expected++;
        }
    }
    prod.join();
    assert(expected == kCount);
    assert(spsc.availableRead() == 0);

    std::cout << "test_ring_buffer passed\n";
    return 0;
}
