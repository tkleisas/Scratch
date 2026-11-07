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
    for (auto& pair : deckPairs_) {
        pair.PrepareForSampleRate(outputSampleRate);
    }
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

void AudioEngine::ReportError(const std::string& error) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    pendingError_ = error;
}

std::string AudioEngine::TakeError() {
    std::lock_guard<std::mutex> lock(errorMutex_);
    std::string error = std::move(pendingError_);
    pendingError_.clear();
    return error;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_ || stream != stream_.get()) {
        return;
    }

    // Oboe has already stopped and closed the underlying stream. Reopen on
    // the new default device (headphone/BT/SCO change) and keep rendering.
    stream_.reset();
    std::string openError;
    if (!OpenStream(openError, oboe::SharingMode::Exclusive) &&
        !OpenStream(openError, oboe::SharingMode::Shared)) {
        ReportError(
            std::string("Audio output unavailable: ") + oboe::convertToText(error) +
            ". Another app may be using the audio device.");
        return;
    }

    const int newRate = stream_->getSampleRate();
    if (newRate > 0 && newRate != sampleRate_.load(std::memory_order_relaxed)) {
        sampleRate_.store(newRate, std::memory_order_relaxed);
        for (auto& pair : deckPairs_) {
            pair.CancelCrossfade();
            pair.PrepareForSampleRate(newRate);
        }
        spectrum_.Init(newRate);
        for (auto& rack : fxRacks_) {
            rack.Init(newRate, 16384);
        }
    }
    if (stream_->requestStart() != oboe::Result::OK) {
        stream_->close();
        stream_.reset();
        ReportError(
            "Could not restart audio output. Another app may be using the audio device.");
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
    deckPairs_[0].Render(deckScratch_[0].data(), frames, gainsStart.a, gainsEnd.a, sampleRate);
    deckPairs_[1].Render(deckScratch_[1].data(), frames, gainsStart.b, gainsEnd.b, sampleRate);
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
    return deckPairs_[index == 0 ? 0 : 1].Active();
}

Deck& AudioEngine::GetIncomingDeck(int index) {
    return deckPairs_[index == 0 ? 0 : 1].Incoming();
}

bool AudioEngine::StartCrossfade(int index, float seconds) {
    return deckPairs_[index == 0 ? 0 : 1].StartCrossfade(seconds, SampleRate());
}

bool AudioEngine::IsCrossfading(int index) const {
    return deckPairs_[index == 0 ? 0 : 1].IsCrossfading();
}

float AudioEngine::CrossfadeProgress(int index) const {
    return deckPairs_[index == 0 ? 0 : 1].CrossfadeProgress();
}

int64_t AudioEngine::CrossfadeDurationMs(int index) const {
    const int rate = SampleRate();
    if (rate <= 0) return 0;
    const int64_t frames = deckPairs_[index == 0 ? 0 : 1].CrossfadeTotalFrames();
    return frames * 1000 / rate;
}

int64_t AudioEngine::CrossfadePositionMs(int index) const {
    return static_cast<int64_t>(
        std::llround(deckPairs_[index == 0 ? 0 : 1].Incoming().PositionSeconds() * 1000.0));
}

bool AudioEngine::ConsumeCrossfadeComplete(int index) {
    return deckPairs_[index == 0 ? 0 : 1].ConsumeCrossfadeComplete();
}

void AudioEngine::CancelCrossfade(int index) {
    deckPairs_[index == 0 ? 0 : 1].CancelCrossfade();
}

bool AudioEngine::ConsumeEnded(int index) {
    return deckPairs_[index == 0 ? 0 : 1].ConsumeEnded();
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
