#include "AudioEngine.h"
#include "SoftClip.h"

#include <algorithm>
#include <cmath>
#include <cstring>

AudioEngine::~AudioEngine() {
    Stop();
}

bool AudioEngine::OpenStream(std::string& error, oboe::SharingMode sharingMode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)
        ->setSampleRate(48000)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(sharingMode)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setChannelConversionAllowed(true)
        ->setFormatConversionAllowed(true)
        ->setDataCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK || !stream_) {
        error = std::string("Oboe open failed: ") + oboe::convertToText(result);
        stream_.reset();
        return false;
    }

    const int32_t burst = stream_->getFramesPerBurst();
    stream_->setBufferSizeInFrames(burst * 2);
    bufferFrames_.store(stream_->getBufferSizeInFrames(), std::memory_order_relaxed);
    sampleRate_.store(stream_->getSampleRate(), std::memory_order_relaxed);
    const auto latency = stream_->calculateLatencyMillis();
    latencyMs_.store(latency ? static_cast<int>(std::llround(latency.value()))
                             : (burst * 2 * 1000) / 48000,
                     std::memory_order_relaxed);
    return true;
}

bool AudioEngine::Start(std::string& error) {
    if (stream_) {
        return true;
    }

    if (!OpenStream(error, oboe::SharingMode::Exclusive)) {
        if (!OpenStream(error, oboe::SharingMode::Shared)) {
            return false;
        }
    }

    const int outputSampleRate = stream_->getSampleRate();
    decks_[0].PrepareForSampleRate(outputSampleRate);
    decks_[1].PrepareForSampleRate(outputSampleRate);
    spectrum_.Init(outputSampleRate);
    for (auto& scratchBuffer : deckScratch_) {
        scratchBuffer.assign(16384 * 2, 0.0f);
    }
    for (auto& rack : fxRacks_) {
        rack.Init(outputSampleRate, 16384);
    }

    const oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        error = std::string("Oboe start failed: ") + oboe::convertToText(result);
        stream_->close();
        stream_.reset();
        return false;
    }

    sampleRate_.store(stream_->getSampleRate(), std::memory_order_relaxed);
    return true;
}

void AudioEngine::Stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) {
        return;
    }
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream*, oboe::Result) {}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_ || stream != stream_.get()) {
        return;
    }

    // Oboe has already stopped and closed the underlying stream. Reopen on
    // the new default device (headphone/BT/SCO change) and keep rendering.
    stream_.reset();
    std::string error;
    if (!OpenStream(error, oboe::SharingMode::Exclusive) &&
        !OpenStream(error, oboe::SharingMode::Shared)) {
        return;
    }

    const int newRate = stream_->getSampleRate();
    if (newRate > 0 && newRate != sampleRate_.load(std::memory_order_relaxed)) {
        sampleRate_.store(newRate, std::memory_order_relaxed);
        decks_[0].PrepareForSampleRate(newRate);
        decks_[1].PrepareForSampleRate(newRate);
        spectrum_.Init(newRate);
        for (auto& rack : fxRacks_) {
            rack.Init(newRate, 16384);
        }
    }
    if (stream_->requestStart() != oboe::Result::OK) {
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    if (stream->getChannelCount() != 2 || stream->getFormat() != oboe::AudioFormat::Float) {
        std::memset(output, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    const int32_t frames = std::min<int32_t>(numFrames, 16384);
    std::memset(output, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);

    const float targetCrossfader = crossfader_.load(std::memory_order_relaxed);
    const float previousCrossfader = crossfaderSmoothed_;
    crossfaderSmoothed_ += (targetCrossfader - crossfaderSmoothed_) * 0.25f;
    const StereoGains gainsStart = EqualPowerGains(previousCrossfader);
    const StereoGains gainsEnd = EqualPowerGains(crossfaderSmoothed_);
    const int sampleRate = stream->getSampleRate();

    for (int deck = 0; deck < 2; ++deck) {
        std::fill(deckScratch_[deck].begin(), deckScratch_[deck].begin() + frames * 2, 0.0f);
    }
    decks_[0].Render(deckScratch_[0].data(), frames, gainsStart.a, sampleRate, gainsEnd.a);
    decks_[1].Render(deckScratch_[1].data(), frames, gainsStart.b, sampleRate, gainsEnd.b);
    fxRacks_[0].Process(deckScratch_[0].data(), frames);
    fxRacks_[1].Process(deckScratch_[1].data(), frames);

    for (int32_t i = 0; i < frames * 2; ++i) {
        output[i] = deckScratch_[0][i] + deckScratch_[1][i];
    }
    fxRacks_[2].Process(output, frames);

    const float targetMaster = masterGain_.load(std::memory_order_relaxed);
    masterSmoothed_ += (targetMaster - masterSmoothed_) * 0.25f;
    const float master = masterSmoothed_;

    for (int32_t i = 0; i < frames * 2; ++i) {
        output[i] = scratch::SoftClip(output[i] * master);
    }

    spectrum_.Process(output, frames);

    if ((++callbackCount_ & 0x7F) == 0) {
        const auto latency = stream->calculateLatencyMillis();
        if (latency) {
            latencyMs_.store(static_cast<int>(std::llround(latency.value())),
                             std::memory_order_relaxed);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

Deck& AudioEngine::GetDeck(int index) {
    return decks_[index == 0 ? 0 : 1];
}

FxRack& AudioEngine::GetFxRack(int index) {
    return fxRacks_[index < 0 ? 0 : (index > 2 ? 2 : index)];
}

void AudioEngine::SetCrossfader(float value) {
    crossfader_.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AudioEngine::SetMasterGain(float value) {
    masterGain_.store(std::clamp(value, 0.0f, 2.0f), std::memory_order_relaxed);
}

int AudioEngine::SampleRate() const {
    return sampleRate_.load(std::memory_order_relaxed);
}

int AudioEngine::LatencyMs() const {
    return latencyMs_.load(std::memory_order_relaxed);
}

int AudioEngine::BufferFrames() const {
    return bufferFrames_.load(std::memory_order_relaxed);
}

void AudioEngine::GetSpectrum(float* out, int count) const {
    spectrum_.GetBands(out, count);
}
