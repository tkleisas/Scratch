#include <gtest/gtest.h>

#include <cmath>
#include <memory>

#include "audio/BpmAnalyzer.h"

namespace {

std::shared_ptr<TrackData> MakeClickTrack(int sampleRate, double bpm, double seconds) {
    auto track = std::make_shared<TrackData>();
    track->sampleRate = sampleRate;
    track->frames = static_cast<int64_t>(sampleRate * seconds);
    track->channels = 2;
    track->samples.assign(static_cast<size_t>(track->frames) * 2, 0.0f);

    const int64_t beatFrames = static_cast<int64_t>(sampleRate * 60.0 / bpm);
    for (int64_t start = 0; start < track->frames; start += beatFrames) {
        for (int i = 0; i < 500 && start + i < track->frames; ++i) {
            const float envelope = std::exp(-i / 90.0f);
            const float value = 0.8f * envelope * static_cast<float>(
                std::sin(2.0 * 3.14159265358979323846 * 160.0 * i / sampleRate));
            track->samples[static_cast<size_t>(start + i) * 2] = value;
            track->samples[static_cast<size_t>(start + i) * 2 + 1] = value;
        }
    }
    return track;
}

}

TEST(BpmAnalyzer, Detects120BpmClickTrack) {
    const auto track = MakeClickTrack(48000, 120.0, 16.0);
    const float bpm = scratch::EstimateBpm(*track);
    EXPECT_NEAR(bpm, 120.0f, 2.0f);
}

TEST(BpmAnalyzer, Detects128BpmClickTrack) {
    const auto track = MakeClickTrack(44100, 128.0, 16.0);
    const float bpm = scratch::EstimateBpm(*track);
    EXPECT_NEAR(bpm, 128.0f, 2.0f);
}

TEST(BpmAnalyzer, ReturnsZeroForSilence) {
    TrackData track;
    track.sampleRate = 48000;
    track.frames = 48000;
    track.samples.assign(48000 * 2, 0.0f);
    EXPECT_EQ(scratch::EstimateBpm(track), 0.0f);
}
