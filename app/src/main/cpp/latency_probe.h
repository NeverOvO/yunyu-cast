#ifndef LATENCY_PROBE_H
#define LATENCY_PROBE_H

#include <atomic>
#include <cstddef>
#include <cstdint>

// Diagnostics only: bounded SPSC queue between codec input and drain threads.
// A slow decoder may exceed capacity. Drop the new measurement rather than
// overwrite a slot still owned by the reader; media packets are never dropped here.
struct LatencyProbe {
    struct Entry { int64_t ptsUs; int64_t queueNs; };
    static constexpr uint64_t CAP = 64;

    bool record(int64_t ptsUs, int64_t queueNs) {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        if (w - mRead.load(std::memory_order_acquire) >= CAP) return false;
        mBuf[w % CAP] = {ptsUs, queueNs};
        mWrite.store(w + 1, std::memory_order_release);
        return true;
    }

    int64_t match(int64_t ptsUs, int64_t nowNs) {
        uint64_t r = mRead.load(std::memory_order_relaxed);
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        for (; r < w; r++) {
            if (mBuf[r % CAP].ptsUs == ptsUs) {
                const int64_t latency = nowNs - mBuf[r % CAP].queueNs;
                mRead.store(r + 1, std::memory_order_release);
                return latency;
            }
        }
        // Some codecs discard input or emit a different PTS. Those unmatched
        // measurements must not fill the queue forever and block future samples.
        mRead.store(w, std::memory_order_release);
        return -1;
    }

    size_t inFlight() const {
        const uint64_t r = mRead.load(std::memory_order_acquire);
        return static_cast<size_t>(mWrite.load(std::memory_order_acquire) - r);
    }

    // Only while both feeding and draining the codec are stopped.
    void reset() {
        mWrite.store(0, std::memory_order_relaxed);
        mRead.store(0, std::memory_order_relaxed);
    }

private:
    Entry mBuf[CAP] = {};
    std::atomic<uint64_t> mWrite{0};
    std::atomic<uint64_t> mRead{0};
};

#endif
