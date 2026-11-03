#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "audio/SpectrumAnalyzer.h"

namespace {

void FeedSine(scratch::SpectrumAnalyzer& analyzer, double frequency, float amplitude,
              int frames, int sampleRate) {
    std::vector<float> buffer(static_cast<size_t>(frames) * 2, 0.0f);
    for (int frame = 0; frame < frames; ++frame) {
        const float value = amplitude * static_cast<float>(
            std::sin(2.0 * 3.14159265358979323846 * frequency * frame / sampleRate));
        buffer[static_cast<size_t>(frame) * 2] = value;
        buffer[static_cast<size_t>(frame) * 2 + 1] = value;
    }
    analyzer.Process(buffer.data(), frames);
}

int LoudestBand(const scratch::SpectrumAnalyzer& analyzer) {
    float bands[scratch::SpectrumAnalyzer::kBandCount] = {};
    analyzer.GetBands(bands, scratch::SpectrumAnalyzer::kBandCount);
    int loudest = 0;
    for (int i = 1; i < scratch::SpectrumAnalyzer::kBandCount; ++i) {
        if (bands[i] > bands[loudest]) loudest = i;
    }
    return loudest;
}

}

TEST(SpectrumAnalyzer, LocatesOneKhzTone) {
    scratch::SpectrumAnalyzer analyzer;
    analyzer.Init(48000);
    FeedSine(analyzer, 1000.0, 0.5f, 4800, 48000);

    const int band = LoudestBand(analyzer);
    const float frequency = scratch::SpectrumAnalyzer::BandFrequency(band);
    EXPECT_GT(frequency, 700.0f);
    EXPECT_LT(frequency, 1500.0f);
}

TEST(SpectrumAnalyzer, LocatesLowTone) {
    scratch::SpectrumAnalyzer analyzer;
    analyzer.Init(48000);
    FeedSine(analyzer, 100.0, 0.5f, 4800, 48000);

    const int band = LoudestBand(analyzer);
    const float frequency = scratch::SpectrumAnalyzer::BandFrequency(band);
    EXPECT_GT(frequency, 60.0f);
    EXPECT_LT(frequency, 160.0f);
}

TEST(SpectrumAnalyzer, SilenceIsEmpty) {
    scratch::SpectrumAnalyzer analyzer;
    analyzer.Init(48000);
    std::vector<float> silence(4800 * 2, 0.0f);
    analyzer.Process(silence.data(), 4800);

    float bands[scratch::SpectrumAnalyzer::kBandCount] = {};
    analyzer.GetBands(bands, scratch::SpectrumAnalyzer::kBandCount);
    for (float value : bands) {
        EXPECT_NEAR(value, 0.0f, 1e-6f);
    }
}
