#include "BpmAnalyzer.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "audio/TransientDetector.h"

namespace scratch {
namespace {

template <typename Sample>
float AnalyzeInterleaved(const Sample* samples, int64_t frames, int sampleRate,
                         float scale) {
    if (samples == nullptr || frames < 1024 || sampleRate < 8000) {
        return 0.0f;
    }

    const int windowSize = std::max(128, sampleRate / 100);
    const int hopSize = std::max(32, sampleRate / 400);
    if (frames < static_cast<int64_t>(windowSize) * 4) {
        return 0.0f;
    }

    const int numWindows =
        static_cast<int>((frames - windowSize) / hopSize + 1);
    std::vector<float> energy(static_cast<size_t>(numWindows), 0.0f);

    for (int window = 0; window < numWindows; ++window) {
        const int64_t start = static_cast<int64_t>(window) * hopSize;
        double sum = 0.0;
        for (int i = 0; i < windowSize; ++i) {
            const int64_t frame = start + i;
            const float left =
                static_cast<float>(samples[static_cast<size_t>(frame) * 2]) * scale;
            const float right =
                static_cast<float>(samples[static_cast<size_t>(frame) * 2 + 1]) * scale;
            sum += static_cast<double>(left) * left + static_cast<double>(right) * right;
        }
        energy[static_cast<size_t>(window)] =
            static_cast<float>(sum / windowSize);
    }

    std::vector<float> flux(static_cast<size_t>(numWindows), 0.0f);
    double fluxSum = 0.0;
    for (int window = 1; window < numWindows; ++window) {
        const float difference =
            energy[static_cast<size_t>(window)] - energy[static_cast<size_t>(window - 1)];
        const float value = std::max(0.0f, difference);
        flux[static_cast<size_t>(window)] = value;
        fluxSum += value;
    }
    const double meanFlux = fluxSum / std::max(1, numWindows - 1);
    double variance = 0.0;
    for (float value : flux) {
        const double difference = value - meanFlux;
        variance += difference * difference;
    }
    const double deviation = std::sqrt(variance / std::max(1, numWindows));
    const float threshold = static_cast<float>(
        std::max(meanFlux * 1.4, meanFlux + deviation * 0.6));

    const int64_t minSpacing = static_cast<int64_t>(sampleRate) * 50 / 1000;
    std::vector<int64_t> transients;
    int64_t lastTransient = -minSpacing - 1;

    for (int window = 1; window + 1 < numWindows; ++window) {
        const float value = flux[static_cast<size_t>(window)];
        if (value <= threshold) {
            continue;
        }
        if (value < flux[static_cast<size_t>(window - 1)] ||
            value < flux[static_cast<size_t>(window + 1)]) {
            continue;
        }
        const int64_t position = static_cast<int64_t>(window) * hopSize;
        if (position - lastTransient >= minSpacing) {
            transients.push_back(position);
            lastTransient = position;
        }
    }

    if (transients.size() < 8) {
        return 0.0f;
    }

    double bpm = yawn::audio::TransientDetector::estimateBPM(
        transients, sampleRate, 60.0, 200.0);
    if (bpm <= 0.0) {
        return 0.0f;
    }
    while (bpm < 80.0) {
        bpm *= 2.0;
    }
    while (bpm > 180.0) {
        bpm *= 0.5;
    }
    return static_cast<float>(bpm);
}

}

float EstimateBpm(const int16_t* interleaved, int64_t frames, int sampleRate) {
    return AnalyzeInterleaved(interleaved, frames, sampleRate, 1.0f / 32768.0f);
}

float EstimateBpm(const float* interleaved, int64_t frames, int sampleRate) {
    return AnalyzeInterleaved(interleaved, frames, sampleRate, 1.0f);
}

float EstimateBpm(const TrackData& track) {
    if (track.frames < 1024 || track.sampleRate < 8000) {
        return 0.0f;
    }
    if (!track.samples.empty()) {
        return EstimateBpm(track.samples.data(), track.frames, track.sampleRate);
    }
    if (track.mapped) {
        return EstimateBpm(track.mapped->data, track.frames, track.sampleRate);
    }
    if (!track.pcm16.empty()) {
        return EstimateBpm(track.pcm16.data(), track.frames, track.sampleRate);
    }
    return 0.0f;
}

}
