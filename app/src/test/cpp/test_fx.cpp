#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "audio/FxRack.h"

namespace {

constexpr int kSampleRate = 48000;
constexpr int kFrames = 480;

void fillSine(std::vector<float>& buffer, float frequency, float amplitude = 0.25f) {
    for (int frame = 0; frame < kFrames; ++frame) {
        const float sample = amplitude * std::sin(
            2.0f * 3.14159265358979f * frequency * frame / kSampleRate);
        buffer[frame * 2] = sample;
        buffer[frame * 2 + 1] = sample;
    }
}

float rms(const std::vector<float>& buffer) {
    double sum = 0.0;
    for (float sample : buffer) {
        sum += static_cast<double>(sample) * sample;
    }
    return static_cast<float>(std::sqrt(sum / buffer.size()));
}

} // namespace

TEST(FxRack, BypassLeavesSignalUntouched) {
    FxRack rack;
    rack.Init(kSampleRate, 2048);
    std::vector<float> buffer(kFrames * 2);
    fillSine(buffer, 1000.0f);
    const std::vector<float> original = buffer;

    rack.Process(buffer.data(), kFrames);

    for (size_t i = 0; i < buffer.size(); ++i) {
        EXPECT_NEAR(buffer[i], original[i], 1e-6f);
    }
}

TEST(FxRack, InsertLowPassReducesHighFrequency) {
    FxRack rack;
    rack.Init(kSampleRate, 2048);
    rack.SetEnabled(FxRack::kInsert, FxRack::kFilter, true);
    rack.SetParam(FxRack::kInsert, FxRack::kFilter, 0, 0.4f);
    rack.SetParam(FxRack::kInsert, FxRack::kFilter, 1, 0.707f);
    rack.SetParam(FxRack::kInsert, FxRack::kFilter, 2, 0.0f);

    std::vector<float> high(kFrames * 2);
    fillSine(high, 6000.0f);
    const float before = rms(high);
    rack.Process(high.data(), kFrames);

    std::vector<float> low(kFrames * 2);
    fillSine(low, 200.0f);
    const float lowBefore = rms(low);
    rack.Process(low.data(), kFrames);

    EXPECT_LT(rms(high), before * 0.6f);
    EXPECT_GT(rms(low), lowBefore * 0.5f);
}

TEST(FxRack, PunchEchoProducesOutputAfterRelease) {
    FxRack rack;
    rack.Init(kSampleRate, 2048);
    rack.SetEnabled(FxRack::kPunch, FxRack::kEcho, true);
    rack.SetParam(FxRack::kPunch, FxRack::kEcho, 0, 20.0f);
    rack.SetParam(FxRack::kPunch, FxRack::kEcho, 1, 0.5f);
    rack.SetPunchSend(FxRack::kEcho, 1.0f);
    rack.SetPunchActive(FxRack::kEcho, true);

    std::vector<float> buffer(kFrames * 2);
    fillSine(buffer, 1000.0f);
    rack.Process(buffer.data(), kFrames);
    const float activeRms = rms(buffer);
    EXPECT_GT(activeRms, 0.1f);

    rack.SetPunchActive(FxRack::kEcho, false);
    for (int block = 0; block < 20; ++block) {
        std::fill(buffer.begin(), buffer.end(), 0.0f);
        rack.Process(buffer.data(), kFrames);
    }
    EXPECT_GT(rms(buffer), 1e-5f);
}

TEST(FxRack, PunchLimiterActsAsSerialInsert) {
    FxRack rack;
    rack.Init(kSampleRate, 2048);
    rack.SetEnabled(FxRack::kPunch, FxRack::kLimiter, true);
    rack.SetParam(FxRack::kPunch, FxRack::kLimiter, 0, -6.0f);
    rack.SetPunchSend(FxRack::kLimiter, 1.0f);
    rack.SetPunchActive(FxRack::kLimiter, true);

    std::vector<float> buffer(kFrames * 2);
    float peak = 0.0f;
    for (int block = 0; block < 8; ++block) {
        fillSine(buffer, 1000.0f, 0.8f);
        rack.Process(buffer.data(), kFrames);
        if (block == 7) {
            for (float sample : buffer) {
                peak = std::max(peak, std::fabs(sample));
            }
        }
    }
    // The limiter ceiling is -6 dBFS (~0.5). A parallel send would leave
    // the dry 0.8 peak in place; the serial path must reduce it.
    EXPECT_LT(peak, 0.75f);
}
