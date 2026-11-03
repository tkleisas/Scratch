#include <gtest/gtest.h>

#include <cmath>
#include <memory>
#include <vector>

#include "audio/Deck.h"
#include "audio/EqualPower.h"
#include "audio/Svf.h"

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

std::vector<float> Render(Deck& deck, int frames, int sampleRate, float mixGain = 1.0f) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 0.0f);
    deck.Render(out.data(), frames, mixGain, sampleRate);
    return out;
}

double RenderEnergy(Deck& deck, int blocks, int blockFrames, int sampleRate) {
    double energy = 0.0;
    for (int block = 0; block < blocks; ++block) {
        const auto out = Render(deck, blockFrames, sampleRate);
        for (float sample : out) {
            energy += std::fabs(sample);
        }
    }
    return energy;
}

std::shared_ptr<TrackData> MakeSineTrack(int64_t frames, int sampleRate, double frequency = 440.0) {
    auto track = MakeTrack(frames, sampleRate, 0.0f);
    for (int64_t i = 0; i < frames; ++i) {
        const float value = 0.5f * static_cast<float>(
            std::sin(2.0 * 3.14159265358979323846 * frequency * i / sampleRate));
        track->samples[static_cast<size_t>(i) * 2] = value;
        track->samples[static_cast<size_t>(i) * 2 + 1] = value;
    }
    return track;
}

}

TEST(Deck, PlayAdvancesAtSourceRate) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    deck.SetPlaying(true);

    Render(deck, 100, 1000);

    EXPECT_EQ(deck.PositionFrames(), 100);
    EXPECT_TRUE(deck.IsPlaying());
}

TEST(Deck, PlaybackScalesWithSampleRateRatio) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 2000));
    deck.SetPlaying(true);

    Render(deck, 100, 1000);

    EXPECT_EQ(deck.PositionFrames(), 200);
}

TEST(Deck, PauseStopsAdvancing) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    deck.SetPlaying(true);
    Render(deck, 100, 1000);
    const int64_t before = deck.PositionFrames();

    deck.SetPlaying(false);
    Render(deck, 100, 1000);

    EXPECT_LE(deck.PositionFrames(), before + 10);
    EXPECT_FALSE(deck.IsPlaying());
}

TEST(Deck, SeekFractionMovesPosition) {
    Deck deck;
    deck.SetTrack(MakeTrack(1000, 1000));
    Render(deck, 1, 1000);

    deck.SeekFraction(0.5f);

    EXPECT_EQ(deck.PositionFrames(), 500);
}

TEST(Deck, StopsAtEndOfTrack) {
    Deck deck;
    deck.SetTrack(MakeTrack(100, 1000));
    deck.SetPlaying(true);

    Render(deck, 200, 1000);

    EXPECT_EQ(deck.PositionFrames(), 99);
    EXPECT_FALSE(deck.IsPlaying());
}

TEST(Deck, RaisesEndedFlagOnce) {
    Deck deck;
    deck.SetTrack(MakeTrack(100, 1000));
    deck.SetPlaying(true);

    Render(deck, 200, 1000);

    EXPECT_TRUE(deck.ConsumeEnded());
    EXPECT_FALSE(deck.ConsumeEnded());
}

TEST(Deck, TempoChangesRawPlaybackRate) {
    Deck deck;
    deck.SetTrack(MakeTrack(100000, 1000));
    deck.SetPlaying(true);
    deck.SetTempo(2.0f);

    Render(deck, 100, 1000);

    EXPECT_GT(deck.PositionFrames(), 180);
    EXPECT_LT(deck.PositionFrames(), 205);
}

TEST(Deck, KeyLockProducesOutputAndAdvances) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 5, 48000));
    deck.PrepareForSampleRate(48000);
    deck.SetKeyLock(true);
    deck.SetPlaying(true);

    double energy = 0.0;
    for (int block = 0; block < 94; ++block) {
        const auto out = Render(deck, 512, 48000);
        for (float sample : out) {
            energy += std::fabs(sample);
        }
    }

    EXPECT_GT(energy, 1000.0);
    EXPECT_GT(deck.PositionFrames(), 40000);
    EXPECT_LT(deck.PositionFrames(), 54000);
}

TEST(Deck, KeyLockResamplesMismatchedSourceRate) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(44100 * 3, 44100));
    deck.PrepareForSampleRate(48000);
    deck.SetKeyLock(true);
    deck.SetPlaying(true);

    double energy = 0.0;
    for (int block = 0; block < 94; ++block) {
        const auto out = Render(deck, 512, 48000);
        for (float sample : out) {
            energy += std::fabs(sample);
        }
    }

    EXPECT_GT(energy, 1000.0);
    EXPECT_GT(deck.PositionFrames(), 40000);
    EXPECT_LT(deck.PositionFrames(), 47200);
}

TEST(Deck, KeyLockDoubleTempoAdvancesTwiceAsFast) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 5, 48000));
    deck.PrepareForSampleRate(48000);
    deck.SetKeyLock(true);
    deck.SetPlaying(true);
    deck.SetTempo(2.0f);

    for (int block = 0; block < 47; ++block) {
        Render(deck, 512, 48000);
    }

    EXPECT_GT(deck.PositionFrames(), 42000);
    EXPECT_LT(deck.PositionFrames(), 54000);
}

TEST(Deck, ScratchBypassesKeyLockStretcher) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 5, 48000));
    deck.PrepareForSampleRate(48000);
    deck.SetKeyLock(true);
    deck.SetPlaying(true);
    Render(deck, 1024, 48000);
    const int64_t before = deck.PositionFrames();

    deck.BeginScratch();
    deck.SetScratchRate(-1.0f);
    Render(deck, 4800, 48000);

    EXPECT_LT(deck.PositionFrames(), before);
}

TEST(Deck, JogWhenPausedMovesPositionWithoutSound) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    Render(deck, 1, 1000);

    deck.BeginScratch();
    deck.SetScratchRate(1.0f);
    const auto out = Render(deck, 200, 1000);

    EXPECT_GT(deck.PositionFrames(), 150);
    EXPECT_LT(deck.PositionFrames(), 205);
    for (float sample : out) {
        EXPECT_NEAR(sample, 0.0f, 1e-6f);
    }
}

TEST(Deck, LowPassFilterAttenuatesHighFrequency) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 3, 48000, 2000.0));
    deck.SetPlaying(true);
    const double dry = RenderEnergy(deck, 47, 512, 48000);

    deck.SetFilterMode(1);
    deck.SetFilterCutoff(0.1f);
    RenderEnergy(deck, 20, 512, 48000);
    const double filtered = RenderEnergy(deck, 47, 512, 48000);

    EXPECT_LT(filtered, dry * 0.05);
}

TEST(Deck, HighPassFilterAttenuatesLowFrequency) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 3, 48000, 100.0));
    deck.SetPlaying(true);
    const double dry = RenderEnergy(deck, 47, 512, 48000);

    deck.SetFilterMode(2);
    deck.SetFilterCutoff(0.9f);
    RenderEnergy(deck, 20, 512, 48000);
    const double filtered = RenderEnergy(deck, 47, 512, 48000);

    EXPECT_LT(filtered, dry * 0.05);
}

TEST(Deck, FilterOffKeepsSignal) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 3, 48000, 440.0));
    deck.SetPlaying(true);
    const double dry = RenderEnergy(deck, 47, 512, 48000);

    deck.SetFilterMode(0);
    RenderEnergy(deck, 20, 512, 48000);
    const double bypass = RenderEnergy(deck, 47, 512, 48000);

    EXPECT_GT(bypass, dry * 0.8);
}

float EqNormForHz(float hz) {
    return std::log(hz / 30.0f) / std::log(18000.0f / 30.0f);
}

TEST(Deck, EqMidCutAttenuatesMidrange) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 3, 48000, 1000.0));
    deck.SetPlaying(true);
    const double dry = RenderEnergy(deck, 47, 512, 48000);

    deck.SetEqNode(3, EqNormForHz(1000.0f), -12.0f, 1.0f);
    RenderEnergy(deck, 20, 512, 48000);
    const double filtered = RenderEnergy(deck, 47, 512, 48000);

    EXPECT_LT(filtered, dry * 0.4);
}

TEST(Deck, EqLowCutAttenuatesBass) {
    Deck dryDeck;
    Deck eqDeck;
    dryDeck.SetTrack(MakeSineTrack(48000 * 3, 48000, 100.0));
    eqDeck.SetTrack(MakeSineTrack(48000 * 3, 48000, 100.0));
    dryDeck.SetPlaying(true);
    eqDeck.SetPlaying(true);
    eqDeck.SetEqNode(0, EqNormForHz(80.0f), -12.0f, 0.707f);

    const double dry = RenderEnergy(dryDeck, 67, 512, 48000);
    const double filtered = RenderEnergy(eqDeck, 67, 512, 48000);

    EXPECT_LT(filtered, dry * 0.75);
}

TEST(Deck, EqHighCutAttenuatesTreble) {
    Deck deck;
    deck.SetTrack(MakeSineTrack(48000 * 3, 48000, 12000.0));
    deck.SetPlaying(true);
    const double dry = RenderEnergy(deck, 47, 512, 48000);

    deck.SetEqNode(7, EqNormForHz(8000.0f), -12.0f, 0.707f);
    RenderEnergy(deck, 20, 512, 48000);
    const double filtered = RenderEnergy(deck, 47, 512, 48000);

    EXPECT_LT(filtered, dry * 0.5);
}

TEST(Deck, EqBypassSkipsProcessing) {
    Deck eqDeck;
    Deck bypassDeck;
    eqDeck.SetTrack(MakeSineTrack(48000 * 3, 48000, 1000.0));
    bypassDeck.SetTrack(MakeSineTrack(48000 * 3, 48000, 1000.0));
    eqDeck.SetPlaying(true);
    bypassDeck.SetPlaying(true);
    eqDeck.SetEqNode(3, EqNormForHz(1000.0f), -12.0f, 1.0f);
    bypassDeck.SetEqNode(3, EqNormForHz(1000.0f), -12.0f, 1.0f);
    bypassDeck.SetEqBypassed(true);

    const double filtered = RenderEnergy(eqDeck, 67, 512, 48000);
    const double bypassed = RenderEnergy(bypassDeck, 67, 512, 48000);

    EXPECT_GT(bypassed, filtered * 1.5);
}

TEST(Deck, EqResponseShowsBoostedNode) {
    Deck deck;
    deck.SetEqNode(3, EqNormForHz(1000.0f), 12.0f, 1.0f);

    float response[64] = {};
    deck.GetEqResponse(response, 64);

    int loudest = 0;
    for (int i = 1; i < 64; ++i) {
        if (response[i] > response[loudest]) loudest = i;
    }
    const float normalized = static_cast<float>(loudest) / 63.0f;
    const float frequency = 30.0f * std::pow(600.0f, normalized);
    EXPECT_GT(frequency, 700.0f);
    EXPECT_LT(frequency, 1500.0f);
    EXPECT_GT(response[loudest], 6.0f);
}

TEST(Deck, LoopWrapsPlaybackAtLoopEnd) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    Render(deck, 1, 1000);
    deck.SeekFraction(0.15f);
    deck.SetLoopPoints(0.1f, 0.2f);
    deck.SetLoopEnabled(true);
    deck.SetPlaying(true);

    Render(deck, 1000, 1000);

    EXPECT_GE(deck.PositionFrames(), 999);
    EXPECT_LE(deck.PositionFrames(), 1999);
    EXPECT_TRUE(deck.IsPlaying());
}

TEST(Deck, ClearLoopRestoresEndOfTrackStop) {
    Deck deck;
    deck.SetTrack(MakeTrack(2000, 1000));
    Render(deck, 1, 1000);
    deck.SeekFraction(0.5f);
    deck.SetLoopPoints(0.4f, 0.6f);
    deck.SetLoopEnabled(true);
    Render(deck, 500, 1000);
    deck.ClearLoop();

    Render(deck, 1500, 1000);

    EXPECT_FALSE(deck.IsPlaying());
}

TEST(Deck, ScratchMovesBackwards) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    Render(deck, 1, 1000);
    deck.SeekFraction(0.5f);

    deck.BeginScratch();
    deck.SetScratchRate(-1.0f);
    Render(deck, 100, 1000);

    EXPECT_LT(deck.PositionFrames(), 5000);
    EXPECT_GT(deck.PositionFrames(), 4800);
}

TEST(Deck, ScratchAtZeroRateIsSilent) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    Render(deck, 1, 1000);

    deck.BeginScratch();
    deck.SetScratchRate(0.0f);
    const auto out = Render(deck, 200, 1000);

    for (float sample : out) {
        EXPECT_NEAR(sample, 0.0f, 1e-6f);
    }
}

TEST(Deck, EndScratchDoesNotAdvanceWhenPaused) {
    Deck deck;
    deck.SetTrack(MakeTrack(10000, 1000));
    Render(deck, 1, 1000);
    deck.SeekFraction(0.5f);
    deck.BeginScratch();
    deck.SetScratchRate(1.0f);
    Render(deck, 50, 1000);
    const int64_t afterScratch = deck.PositionFrames();

    deck.EndScratch();
    Render(deck, 500, 1000);

    EXPECT_LE(deck.PositionFrames(), afterScratch + 10);
}

TEST(EqualPower, EndpointsAndCenter) {
    const auto left = EqualPowerGains(0.0f);
    EXPECT_NEAR(left.a, 1.0f, 1e-6f);
    EXPECT_NEAR(left.b, 0.0f, 1e-6f);

    const auto center = EqualPowerGains(0.5f);
    EXPECT_NEAR(center.a, 0.70710678f, 1e-5f);
    EXPECT_NEAR(center.b, 0.70710678f, 1e-5f);

    const auto right = EqualPowerGains(1.0f);
    EXPECT_NEAR(right.a, 0.0f, 1e-6f);
    EXPECT_NEAR(right.b, 1.0f, 1e-6f);
}

TEST(Svf, LowPassPassesDc) {
    Svf filter;
    filter.Set(100.0f, 0.707f, 48000.0f);
    float out = 0.0f;
    for (int i = 0; i < 48000; ++i) {
        out = filter.ProcessLowPass(1.0f);
    }
    EXPECT_NEAR(out, 1.0f, 0.01f);
}

TEST(Svf, HighPassRejectsDc) {
    Svf filter;
    filter.Set(100.0f, 0.707f, 48000.0f);
    float out = 1.0f;
    for (int i = 0; i < 48000; ++i) {
        out = filter.ProcessHighPass(1.0f);
    }
    EXPECT_NEAR(out, 0.0f, 0.01f);
}

TEST(Svf, LowPassAttenuatesHighFrequency) {
    constexpr float kSampleRate = 48000.0f;
    constexpr float kFrequency = 10000.0f;
    Svf filter;
    filter.Set(1000.0f, 0.707f, kSampleRate);
    double inputEnergy = 0.0;
    double outputEnergy = 0.0;
    for (int i = 0; i < 48000; ++i) {
        const float in = std::sin(2.0f * 3.14159265f * kFrequency * i / kSampleRate);
        const float out = filter.ProcessLowPass(in);
        inputEnergy += in * in;
        outputEnergy += out * out;
    }
    EXPECT_LT(outputEnergy, inputEnergy * 0.01);
}

TEST(Svf, HighPassAttenuatesLowFrequency) {
    constexpr float kSampleRate = 48000.0f;
    constexpr float kFrequency = 100.0f;
    Svf filter;
    filter.Set(1000.0f, 0.707f, kSampleRate);
    double inputEnergy = 0.0;
    double outputEnergy = 0.0;
    for (int i = 0; i < 48000; ++i) {
        const float in = std::sin(2.0f * 3.14159265f * kFrequency * i / kSampleRate);
        const float out = filter.ProcessHighPass(in);
        inputEnergy += in * in;
        outputEnergy += out * out;
    }
    EXPECT_LT(outputEnergy, inputEnergy * 0.01);
}
