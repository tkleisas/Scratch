#include <gtest/gtest.h>

#include <memory>
#include <vector>

#include "audio/DeckPair.h"

namespace {

std::shared_ptr<TrackData> MakeTrack(int64_t frames, int sampleRate, float value = 0.5f) {
    auto track = std::make_shared<TrackData>();
    track->sampleRate = sampleRate;
    track->frames = frames;
    track->channels = 2;
    track->samples.assign(static_cast<size_t>(frames) * 2, value);
    track->peaks.assign(64, std::fabs(value));
    return track;
}

void Render(DeckPair& pair, int frames, int sampleRate, float gain = 1.0f) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 0.0f);
    pair.Render(out.data(), frames, gain, gain, sampleRate);
}

}

TEST(DeckPair, CrossfadeRequiresIncomingTrack) {
    DeckPair pair;
    pair.Active().SetTrack(MakeTrack(1000, 1000));
    pair.Active().SetPlaying(true);

    EXPECT_FALSE(pair.StartCrossfade(0.1f, 1000));
    EXPECT_FALSE(pair.IsCrossfading());
}

TEST(DeckPair, CrossfadePromotesIncomingTrack) {
    DeckPair pair;
    auto outgoing = MakeTrack(10000, 1000, 0.5f);
    auto incoming = MakeTrack(10000, 1000, 0.1f);
    pair.Active().SetTrack(outgoing);
    pair.Active().SetPlaying(true);
    pair.Incoming().SetTrack(incoming);
    Render(pair, 100, 1000);

    ASSERT_TRUE(pair.StartCrossfade(0.1f, 1000));
    EXPECT_TRUE(pair.IsCrossfading());
    EXPECT_EQ(pair.CrossfadeTotalFrames(), 100);
    Render(pair, 50, 1000);
    EXPECT_TRUE(pair.IsCrossfading());
    EXPECT_NEAR(pair.CrossfadeProgress(), 0.5f, 0.011f);
    EXPECT_FALSE(pair.ConsumeCrossfadeComplete());

    Render(pair, 60, 1000);
    EXPECT_FALSE(pair.IsCrossfading());
    EXPECT_TRUE(pair.ConsumeCrossfadeComplete());
    EXPECT_EQ(pair.Active().GetTrackForUi(), incoming);
    EXPECT_FALSE(pair.Incoming().IsPlaying());
    EXPECT_GT(pair.Active().PositionFrames(), 50);
}

TEST(DeckPair, CrossfadeIsClampedToOutgoingTail) {
    DeckPair pair;
    pair.Active().SetTrack(MakeTrack(1000, 1000));
    pair.Active().SetPlaying(true);
    pair.Incoming().SetTrack(MakeTrack(10000, 1000));
    Render(pair, 900, 1000);

    ASSERT_TRUE(pair.StartCrossfade(0.5f, 1000));
    EXPECT_EQ(pair.CrossfadeTotalFrames(), 100);
}

TEST(DeckPair, CancelCrossfadeKeepsOutgoingActive) {
    DeckPair pair;
    auto outgoing = MakeTrack(10000, 1000, 0.5f);
    auto incoming = MakeTrack(10000, 1000, 0.1f);
    pair.Active().SetTrack(outgoing);
    pair.Active().SetPlaying(true);
    pair.Incoming().SetTrack(incoming);

    ASSERT_TRUE(pair.StartCrossfade(0.1f, 1000));
    pair.CancelCrossfade();
    EXPECT_FALSE(pair.IsCrossfading());
    EXPECT_EQ(pair.Active().GetTrackForUi(), outgoing);
    EXPECT_FALSE(pair.Incoming().IsPlaying());

    Render(pair, 100, 1000);
    EXPECT_GT(pair.Active().PositionFrames(), 50);
    EXPECT_EQ(pair.Incoming().PositionFrames(), 0);
}

TEST(DeckPair, CompletionAtTrackEndDoesNotEmitEndedEvent) {
    DeckPair pair;
    pair.Active().SetTrack(MakeTrack(100, 1000));
    pair.Active().SetPlaying(true);
    pair.Incoming().SetTrack(MakeTrack(10000, 1000));
    Render(pair, 50, 1000);

    ASSERT_TRUE(pair.StartCrossfade(0.2f, 1000));
    Render(pair, 60, 1000);

    EXPECT_FALSE(pair.IsCrossfading());
    EXPECT_TRUE(pair.ConsumeCrossfadeComplete());
    EXPECT_FALSE(pair.ConsumeEnded());
}
