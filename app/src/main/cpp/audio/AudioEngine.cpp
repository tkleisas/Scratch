#include "AudioEngine.h"

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
    if (!stream_) {
        return;
    }
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    if (stream->getChannelCount() != 2 || stream->getFormat() != oboe::AudioFormat::Float) {
        std::memset(output, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    std::memset(output, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);

    const float targetCrossfader = crossfader_.load(std::memory_order_relaxed);
    crossfaderSmoothed_ += (targetCrossfader - crossfaderSmoothed_) * 0.25f;
    const StereoGains gains = EqualPowerGains(crossfaderSmoothed_);
    const int sampleRate = stream->getSampleRate();

    decks_[0].Render(output, numFrames, gains.a, sampleRate);
    decks_[1].Render(output, numFrames, gains.b, sampleRate);

    const float targetMaster = masterGain_.load(std::memory_order_relaxed);
    masterSmoothed_ += (targetMaster - masterSmoothed_) * 0.25f;
    const float master = masterSmoothed_;

    for (int32_t i = 0; i < numFrames * 2; ++i) {
        output[i] = std::clamp(output[i] * master, -1.0f, 1.0f);
    }

    spectrum_.Process(output, numFrames);

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
