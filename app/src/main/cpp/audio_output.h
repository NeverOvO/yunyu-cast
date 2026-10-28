#ifndef AUDIO_OUTPUT_H
#define AUDIO_OUTPUT_H

#include <oboe/Oboe.h>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>

#include "log_sink.h"
#include "timeline_buffer.h"

class AudioOutput;

// https://github.com/google/oboe/wiki/TechNote_HowToAvoidCrashes#avoid-deleting-objects-that-are-used-by-callbacks
/*
 * oboe data/error callbacks. oboe can invoke these even after stop()/close():
 * this object owns refs to everything the callbacks touch: a late callback can never
 * reach freed state. holds only weak_ptr to AudioOutput to break the ownership cycles
 * AudioOutput -> OboeCallbacks -> AudioOutput and
 * AudioOutput -> oboe::AudioStream -> OboeCallbacks -> AudioOutput
 */
class OboeCallbacks : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    OboeCallbacks(std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log,
                  int channels)
        : mTimeline(std::move(timeline)), mLog(std::move(log)), mChannels(channels) {}

    void setClientGain(float g) { mClientGain.store(g, std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override {
        auto *out = static_cast<int16_t *>(audioData);
        if (mActiveStream.load(std::memory_order_acquire) != stream) {
            // A retired stream must not become a second consumer after replacement.
            if (numFrames > 0) memset(out, 0, (size_t)numFrames * mChannels * sizeof(int16_t));
            return oboe::DataCallbackResult::Stop;
        }
        mTimeline->read(out, numFrames);

        const float gain = mClientGain.load(std::memory_order_relaxed);
        if (gain < 1.0f) {
            const size_t n = (size_t)numFrames * mChannels;
            for (size_t i = 0; i < n; i++) out[i] = (int16_t)(out[i] * gain);
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override;  // needs AudioOutput

private:
    friend class AudioOutput;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    const int mChannels;
    std::atomic<float> mClientGain{1.0f};
    std::atomic<oboe::AudioStream *> mActiveStream{nullptr};
    std::weak_ptr<AudioOutput> mOwner;
};

/*
 * low-latency audio output via oboe (AAudio or OpenSL ES). data callback pulls PCM from
 * TimelineBuffer and applies sender gain; transparently handles device-loss errors
 * (e.g. BT/headset change)
 */
class AudioOutput {
public:
    static std::shared_ptr<AudioOutput> create(int sampleRate, int channels, int oboeBufferFrames,
                                               bool lowLatency,
                                               std::shared_ptr<TimelineBuffer> timeline,
                                               std::shared_ptr<LogSink> log) {
        auto out = std::make_shared<AudioOutput>(sampleRate, channels, oboeBufferFrames,
                                                 lowLatency, std::move(timeline), std::move(log));
        out->mCallbacks->mOwner = out;
        return out;
    }

    AudioOutput(int sampleRate, int channels, int oboeBufferFrames, bool lowLatency,
                std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log)
        : mSampleRate(sampleRate), mChannels(channels), mOboeBufferFrames(oboeBufferFrames),
          mLowLatency(lowLatency), mTimeline(std::move(timeline)), mLog(std::move(log)),
          mCallbacks(std::make_shared<OboeCallbacks>(mTimeline, mLog, channels)) {}

    ~AudioOutput() { stop(); }

    bool start() {
        std::lock_guard<std::mutex> lk(mLock);
        if (isActive()) return true;
        closeLocked();
        // No old data callback can still consume the ring after close returns.
        mTimeline->flushAndReprime();
        if (!openLocked()) {
            closeLocked();
            return false;
        }
        // Publish before requestStart: an immediate error callback must be able to
        // invalidate this stream without waiting for mLock.
        mCallbacks->mActiveStream.store(mStream.get(), std::memory_order_release);
        const oboe::Result result = mStream->requestStart();
        if (result != oboe::Result::OK || !isActive()) {
            mLog->error("Failed to start Oboe stream: %s", oboe::convertToText(result));
            closeLocked();
            return false;
        }
        return true;
    }

    bool isActive() const { return mCallbacks->mActiveStream.load(std::memory_order_acquire) != nullptr; }

    void stop() {
        std::lock_guard<std::mutex> lk(mLock);
        closeLocked();
    }

    void setClientGain(float g) { mCallbacks->setClientGain(g); }

    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        int32_t xrun;  // cumulative
    };
    Debug debugInfo() {
        std::unique_lock<std::mutex> lk(mLock, std::try_to_lock);
        if (lk.owns_lock() && mStream) {
            auto r = mStream->getXRunCount();
            if (r) mLastXrun = r.value();
        }
        return Debug{mLastXrun};
    }

private:
    friend class OboeCallbacks;

    // Error callbacks only publish state. close()/stop() may wait for a callback,
    // so taking mLock or reopening here would risk a lock inversion.
    bool markClosedAfterError(oboe::AudioStream *failedStream) {
        return mCallbacks->mActiveStream.compare_exchange_strong(failedStream, nullptr, std::memory_order_acq_rel);
    }

    void closeLocked() {
        // Retire identity before stopping. A late error from this stream cannot
        // invalidate a later replacement, and no callback can restart after stop.
        mCallbacks->mActiveStream.store(nullptr, std::memory_order_release);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }

    bool openLocked() {  // caller holds mLock
        // see: https://developer.android.com/games/sdk/oboe/low-latency-audio
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(mChannels)
            ->setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(mCallbacks)
            ->setErrorCallback(mCallbacks);
        if (mLowLatency) {
            // game use case may enable extra latency optimizations
            b.setPerformanceMode(oboe::PerformanceMode::LowLatency)->setUsage(oboe::Usage::Game);
        } else {
            // media use case may have better quality and lower power
            b.setPerformanceMode(oboe::PerformanceMode::PowerSaving)->setUsage(oboe::Usage::Media);
        }
        oboe::Result r = b.openStream(mStream);
        if (r != oboe::Result::OK) {
            mLog->error("Failed to open Oboe stream: %s", oboe::convertToText(r));
            return false;
        }
        if (mOboeBufferFrames > 0) {
            mStream->setBufferSizeInFrames(mOboeBufferFrames);
        } else if (mLowLatency) {
            // smallest reasonable buffer in low-latency mode
            mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
        }

        // log what we actually got: oboe may clamp the buffer, fall back to a
        // higher-latency API, or deny the low-latency path
        const bool aaudio = mStream->getAudioApi() == oboe::AudioApi::AAudio;
        const char *api = aaudio ? "AAudio"
                        : mStream->getAudioApi() == oboe::AudioApi::OpenSLES ? "OpenSLES" : "?";
        const bool lowLatency = mStream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
        const bool mmap = aaudio && oboe::OboeExtensions::isMMapUsed(mStream.get());
        mLog->info("Oboe out: %s%s, mmap=%s, buffer=%d/%d frames, burst=%d, %d Hz",
                  api, lowLatency ? " (low-latency)" : "", mmap ? "yes" : "no",
                  mStream->getBufferSizeInFrames(), mStream->getBufferCapacityInFrames(),
                  mStream->getFramesPerBurst(), mStream->getSampleRate());
        // adaptive tuner needs final buffer size
        mTimeline->noteOutputBufferFrames(mStream->getBufferSizeInFrames());
        return true;
    }

    const int mSampleRate;
    const int mChannels;

    const int mOboeBufferFrames;
    const bool mLowLatency;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    std::shared_ptr<OboeCallbacks> mCallbacks;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mLock;                    // guards open/close
    int32_t mLastXrun = 0;               // debug-poll thread only
};

inline void OboeCallbacks::onErrorAfterClose(oboe::AudioStream *failedStream, oboe::Result error) {
    // The engine retries on its producer thread while playback is requested.
    // Ignore errors from streams already retired by pause/reconfiguration.
    if (auto owner = mOwner.lock(); owner && owner->markClosedAfterError(failedStream))
        mLog->error("Oboe stream error: %s - recovery pending", oboe::convertToText(error));
}

#endif  // AUDIO_OUTPUT_H
