#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <vector>

namespace square {

/**
 * Lock-free single-producer single-consumer ring buffer for trivially copyable items
 * (audio samples, raw bytes, or fixed-size control commands).
 * Power-of-two capacity is not required; modulo/wrap is handled with two memcpy segments.
 */
template <typename T>
class SpscRing {
public:
    explicit SpscRing(size_t capacity = 0) {
        if (capacity > 0) reset(capacity);
    }

    void reset(size_t capacity) {
        // One extra slot distinguishes full from empty without a separate counter.
        storage_.assign(capacity + 1, T{});
        cap_ = capacity + 1;
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const {
        return cap_ > 0 ? cap_ - 1 : 0;
    }

    size_t availableRead() const {
        if (cap_ == 0) return 0;
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        return (h >= t) ? (h - t) : (cap_ - t + h);
    }

    size_t availableWrite() const {
        if (cap_ <= 1) return 0;
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        return (t > h) ? (t - h - 1) : (cap_ - 1 - h + t);
    }

    size_t write(const T* src, size_t count) {
        if (cap_ <= 1 || count == 0) return 0;
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        const size_t avail = (t > h) ? (t - h - 1) : (cap_ - 1 - h + t);
        const size_t n = (count < avail) ? count : avail;
        if (n == 0) return 0;

        const size_t first = ((cap_ - h) < n) ? (cap_ - h) : n;
        std::memcpy(&storage_[h], src, first * sizeof(T));
        if (n > first) {
            std::memcpy(&storage_[0], src + first, (n - first) * sizeof(T));
        }
        head_.store((h + n) % cap_, std::memory_order_release);
        return n;
    }

    size_t read(T* dst, size_t count) {
        if (cap_ <= 1 || count == 0) return 0;
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t avail = (h >= t) ? (h - t) : (cap_ - t + h);
        const size_t n = (count < avail) ? count : avail;
        if (n == 0) return 0;

        const size_t first = ((cap_ - t) < n) ? (cap_ - t) : n;
        if (dst) {
            std::memcpy(dst, &storage_[t], first * sizeof(T));
            if (n > first) {
                std::memcpy(dst + first, &storage_[0], (n - first) * sizeof(T));
            }
        }
        tail_.store((t + n) % cap_, std::memory_order_release);
        return n;
    }

    bool pushOne(const T& item) {
        return write(&item, 1) == 1;
    }

    bool popOne(T& item) {
        return read(&item, 1) == 1;
    }

private:
    std::vector<T> storage_;
    size_t cap_ = 0;
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

} // namespace square
