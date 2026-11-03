#include "SpectrumAnalyzer.h"

#include <algorithm>
#include <cmath>

#include "audio/TimeStretcher.h"

namespace scratch {
namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinFrequency = 30.0f;
constexpr float kMaxFrequency = 18000.0f;
}

void SpectrumAnalyzer::Init(int sampleRate) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    window_.resize(kFftSize);
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * i / (kFftSize - 1)));
    }
    real_.assign(kFftSize, 0.0f);
    imag_.assign(kFftSize, 0.0f);
    ring_.assign(kFftSize, 0.0f);
    Reset();
}

void SpectrumAnalyzer::Reset() {
    std::fill(real_.begin(), real_.end(), 0.0f);
    std::fill(imag_.begin(), imag_.end(), 0.0f);
    std::fill(ring_.begin(), ring_.end(), 0.0f);
    writeIndex_ = 0;
    for (auto& band : bands_) {
        band.store(0.0f, std::memory_order_relaxed);
    }
}

float SpectrumAnalyzer::BandFrequency(int index) {
    const float fraction = static_cast<float>(index) / (kBandCount - 1);
    return kMinFrequency * std::pow(kMaxFrequency / kMinFrequency, fraction);
}

void SpectrumAnalyzer::Process(const float* interleavedStereo, int frames) {
    if (interleavedStereo == nullptr || frames <= 0) {
        return;
    }
    for (int frame = 0; frame < frames; ++frame) {
        Push(interleavedStereo[static_cast<size_t>(frame) * 2],
             interleavedStereo[static_cast<size_t>(frame) * 2 + 1]);
    }
}

void SpectrumAnalyzer::Push(float left, float right) {
    if (ring_.empty()) {
        return;
    }
    ring_[writeIndex_++] = 0.5f * (left + right);
    if (writeIndex_ >= kFftSize) {
        Analyze();
        writeIndex_ = 0;
    }
}

void SpectrumAnalyzer::Analyze() {
    for (int i = 0; i < kFftSize; ++i) {
        real_[i] = ring_[i] * window_[i];
        imag_[i] = 0.0f;
    }

    yawn::audio::TimeStretcher::fft(real_.data(), imag_.data(), kFftSize, false);

    const float normalization = 1.0f / (kFftSize / 4.0f);
    for (int band = 0; band < kBandCount; ++band) {
        const float lowFrequency = kMinFrequency * std::pow(
            kMaxFrequency / kMinFrequency, static_cast<float>(band) / kBandCount);
        const float highFrequency = kMinFrequency * std::pow(
            kMaxFrequency / kMinFrequency, static_cast<float>(band + 1) / kBandCount);

        int lowBin = static_cast<int>(lowFrequency * kFftSize / sampleRate_);
        int highBin = static_cast<int>(highFrequency * kFftSize / sampleRate_);
        lowBin = std::clamp(lowBin, 1, kFftSize / 2 - 1);
        highBin = std::clamp(highBin, lowBin + 1, kFftSize / 2);

        float magnitude = 0.0f;
        for (int bin = lowBin; bin < highBin; ++bin) {
            const float re = real_[bin];
            const float im = imag_[bin];
            magnitude = std::max(magnitude, std::sqrt(re * re + im * im));
        }

        const float db = 20.0f * std::log10(std::max(magnitude * normalization, 1e-7f));
        const float normalized = std::clamp((db + 60.0f) / 60.0f, 0.0f, 1.0f);
        const float previous = bands_[band].load(std::memory_order_relaxed);
        const float smoothed = std::max(normalized, previous * 0.80f);
        bands_[band].store(smoothed, std::memory_order_relaxed);
    }
}

void SpectrumAnalyzer::GetBands(float* out, int count) const {
    if (out == nullptr || count <= 0) {
        return;
    }
    const int copied = std::min(count, kBandCount);
    for (int i = 0; i < copied; ++i) {
        out[i] = bands_[i].load(std::memory_order_relaxed);
    }
    for (int i = copied; i < count; ++i) {
        out[i] = 0.0f;
    }
}

}
