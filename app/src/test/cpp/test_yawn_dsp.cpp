#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "audio/AudioBuffer.h"
#include "audio/TimeStretcher.h"
#include "audio/TransientDetector.h"

using yawn::audio::AudioBuffer;
using yawn::audio::TimeStretcher;
using yawn::audio::TransientDetector;

namespace {

std::vector<float> MakeSine(int frames, double sampleRate, double frequency) {
    std::vector<float> samples(static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        samples[static_cast<size_t>(i)] = static_cast<float>(
            std::sin(2.0 * 3.14159265358979323846 * frequency * i / sampleRate));
    }
    return samples;
}

}

TEST(YawnTimeStretcher, WsolaProducesOutputAndConsumesInput) {
    TimeStretcher stretcher;
    stretcher.init(44100.0, 4096, TimeStretcher::Algorithm::WSOLA);
    stretcher.setSpeedRatio(1.0);

    const auto input = MakeSine(44100, 44100.0, 440.0);
    std::vector<float> output(input.size() * 2, 0.0f);
    int consumed = 0;
    const int written = stretcher.process(
        input.data(), static_cast<int>(input.size()),
        output.data(), static_cast<int>(output.size()), consumed);

    EXPECT_GT(written, 0);
    EXPECT_GT(consumed, 0);
}

TEST(YawnTimeStretcher, WsolaCompressesAndStretches) {
    for (double ratio : {0.5, 2.0}) {
        TimeStretcher stretcher;
        stretcher.init(48000.0, 4096, TimeStretcher::Algorithm::WSOLA);
        stretcher.setSpeedRatio(ratio);

        const auto input = MakeSine(48000, 48000.0, 220.0);
        std::vector<float> output(input.size() * 4, 0.0f);
        int consumed = 0;
        const int written = stretcher.process(
            input.data(), static_cast<int>(input.size()),
            output.data(), static_cast<int>(output.size()), consumed);

        EXPECT_GT(written, 0) << "ratio " << ratio;
        for (int i = 0; i < written; ++i) {
            EXPECT_TRUE(std::isfinite(output[static_cast<size_t>(i)]));
        }
    }
}

TEST(YawnTransientDetector, DetectsClickTrackAndEstimatesBpm) {
    constexpr double kSampleRate = 44100.0;
    constexpr double kBpm = 120.0;
    const int beatFrames = static_cast<int>(kSampleRate * 60.0 / kBpm);
    const int totalFrames = beatFrames * 16;
    AudioBuffer buffer(1, totalFrames);

    float* channel = buffer.channelData(0);
    for (int beat = 0; beat < 16; ++beat) {
        const int start = beat * beatFrames;
        for (int i = 0; i < 400 && start + i < totalFrames; ++i) {
            const float envelope = std::exp(-i / 80.0f);
            channel[start + i] = 0.8f * envelope *
                static_cast<float>(std::sin(2.0 * 3.14159265358979323846 * 180.0 * i / kSampleRate));
        }
    }

    const auto transients = TransientDetector::detect(buffer);
    EXPECT_GE(transients.size(), 8u);

    const double bpm = TransientDetector::estimateBPM(transients, kSampleRate, 60.0, 200.0);
    EXPECT_NEAR(bpm, kBpm, 2.0);
}
