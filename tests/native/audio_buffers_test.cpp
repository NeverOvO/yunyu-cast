#include "latency_probe.h"
#include "timeline_buffer.h"
#include <cassert>
#include <thread>
#include <iostream>

static void probeDoesNotOverwriteUnreadSamples() {
    LatencyProbe probe;
    for (int64_t i = 0; i < 64; ++i) assert(probe.record(i, 1000 + i));
    assert(!probe.record(64, 1064));
    assert(probe.inFlight() == 64);
    for (int64_t i = 0; i < 64; ++i) assert(probe.match(i, 2000 + i) == 1000);
    assert(probe.inFlight() == 0);
    assert(probe.record(65, 1065));
    assert(probe.match(65, 2065) == 1000);
}

static void missingCodecTimestampsDoNotPermanentlyFillProbe() {
    LatencyProbe probe;
    for (int i = 0; i < 64; ++i) assert(probe.record(i, i));
    assert(probe.match(100, 200) == -1);
    assert(probe.inFlight() == 0);
    assert(probe.record(101, 201));
    assert(probe.match(101, 301) == 100);
    probe.reset();
    assert(probe.inFlight() == 0);
}

static void concurrentProbeKeepsEveryAcceptedSampleIntact() {
    LatencyProbe probe;
    constexpr int count = 100000;
    std::thread writer([&] {
        for (int i = 0; i < count; ++i) {
            while (!probe.record(i, i + 50)) std::this_thread::yield();
        }
    });
    for (int i = 0; i < count; ++i) {
        while (probe.inFlight() == 0) std::this_thread::yield();
        assert(probe.match(i, i + 150) == 100);
    }
    writer.join();
    assert(probe.inFlight() == 0);
}

static void discontinuityDiscardsOnlyOldPcm() {
    TimelineBuffer timeline(1000, 1, 0, 95);
    const int16_t oldPcm[] = {1, 2, 3};
    const int16_t newPcm[] = {7, 8, 9};
    int16_t output[3]{};
    timeline.write(oldPcm, 3, 0);
    timeline.resetForDiscontinuity();
    timeline.write(newPcm, 3, 0);
    timeline.read(output, 3);
    assert(output[0] == 7 && output[1] == 8 && output[2] == 9);
}

static void consumedResetBoundaryNeverRewindsRing() {
    SpscRing ring(8);
    const int16_t first[] = {1, 2, 3};
    const int16_t second[] = {4, 5};
    int16_t out[3]{};
    ring.write(first, 3);
    const size_t boundary = ring.writePosition();
    ring.read(out, 3);
    ring.write(second, 2);
    ring.read(out, 1);
    ring.skipThrough(boundary);
    assert(ring.available() == 1);
    assert(ring.read(out, 1) == 1 && out[0] == 5);
}

int main() {
    probeDoesNotOverwriteUnreadSamples();
    missingCodecTimestampsDoNotPermanentlyFillProbe();
    concurrentProbeKeepsEveryAcceptedSampleIntact();
    discontinuityDiscardsOnlyOldPcm();
    consumedResetBoundaryNeverRewindsRing();
    std::cout << "5 native audio buffer regression tests passed\n";
}
