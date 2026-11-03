#include <gtest/gtest.h>
#include <unistd.h>

#include <cmath>
#include <cstdlib>
#include <string>
#include <vector>

#include "TestAudio.h"
#include "audio/Deck.h"
#include "audio/TrackLoader.h"

namespace {

std::shared_ptr<TrackData> Load(const std::vector<uint8_t>& bytes, std::string& error) {
    return scratch::LoadTrackFromMemory(bytes.data(), bytes.size(), error);
}

}

TEST(WavLoader, Loads16BitStereo) {
    const auto wav = MakeWav16(2, 44100, {0, 16384, -32768, 32767});
    std::string error;
    const auto track = Load(wav, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->channels, 2);
    EXPECT_EQ(track->sampleRate, 44100);
    EXPECT_EQ(track->frames, 2);
    ASSERT_EQ(track->samples.size(), 4u);
    EXPECT_FLOAT_EQ(track->samples[0], 0.0f);
    EXPECT_FLOAT_EQ(track->samples[1], 0.5f);
    EXPECT_FLOAT_EQ(track->samples[2], -1.0f);
    EXPECT_NEAR(track->samples[3], 32767.0f / 32768.0f, 1e-6f);
    EXPECT_FALSE(track->peaks.empty());
}

TEST(WavLoader, DuplicatesMonoToStereo) {
    const auto wav = MakeWav16(1, 48000, {1000, -1000});
    std::string error;
    const auto track = Load(wav, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->channels, 2);
    EXPECT_EQ(track->frames, 2);
    ASSERT_EQ(track->samples.size(), 4u);
    EXPECT_NEAR(track->samples[0], 1000.0f / 32768.0f, 1e-6f);
    EXPECT_FLOAT_EQ(track->samples[0], track->samples[1]);
    EXPECT_FLOAT_EQ(track->samples[2], track->samples[3]);
}

TEST(WavLoader, LoadsFloat32) {
    const auto wav = MakeWavFloat32(2, 48000, {0.25f, -0.5f});
    std::string error;
    const auto track = Load(wav, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->frames, 1);
    ASSERT_EQ(track->samples.size(), 2u);
    EXPECT_FLOAT_EQ(track->samples[0], 0.25f);
    EXPECT_FLOAT_EQ(track->samples[1], -0.5f);
}

TEST(TrackLoader, ResamplesToTargetRate) {
    const auto wav = MakeWav16(2, 44100, std::vector<int16_t>(1000, 16384));
    std::string error;
    const auto track =
        scratch::LoadTrackFromMemory(wav.data(), wav.size(), error, 48000);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->sampleRate, 48000);
    EXPECT_EQ(track->frames, 544);
    EXPECT_NEAR(track->samples.front(), 0.5f, 1e-4f);
    EXPECT_NEAR(track->samples.back(), 0.5f, 1e-4f);
}

TEST(AiffLoader, Loads16BitStereo) {
    const auto aiff = MakeAiff16(2, 44100, {0, 16384, -32768, 32767});
    std::string error;
    const auto track = Load(aiff, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->channels, 2);
    EXPECT_EQ(track->sampleRate, 44100);
    EXPECT_EQ(track->frames, 2);
    ASSERT_EQ(track->samples.size(), 4u);
    EXPECT_FLOAT_EQ(track->samples[0], 0.0f);
    EXPECT_FLOAT_EQ(track->samples[1], 0.5f);
    EXPECT_FLOAT_EQ(track->samples[2], -1.0f);
    EXPECT_NEAR(track->samples[3], 32767.0f / 32768.0f, 1e-6f);
}

TEST(AiffLoader, LoadsMono) {
    const auto aiff = MakeAiff16(1, 48000, {16384, -16384});
    std::string error;
    const auto track = Load(aiff, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->channels, 2);
    ASSERT_EQ(track->samples.size(), 4u);
    EXPECT_FLOAT_EQ(track->samples[0], 0.5f);
    EXPECT_FLOAT_EQ(track->samples[0], track->samples[1]);
    EXPECT_FLOAT_EQ(track->samples[2], -0.5f);
    EXPECT_FLOAT_EQ(track->samples[2], track->samples[3]);
}

TEST(TrackLoader, BuildsTrackFromDecodedPcm16) {
    std::string error;
    const auto track = scratch::BuildTrackFromPcm16(
        {0, 16384, -32768, 32767}, 44100, error);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->sampleRate, 44100);
    EXPECT_EQ(track->frames, 2);
    EXPECT_TRUE(track->samples.empty());
    ASSERT_EQ(track->pcm16.size(), 4u);
    EXPECT_EQ(track->pcm16[1], 16384);
    EXPECT_FALSE(track->peaks.empty());
    EXPECT_GT(track->peaks[0], 0.4f);
}

TEST(TrackLoader, MapsPcmFileForPlayback) {
    char path[] = "/tmp/scratch_pcm_XXXXXX";
    const int fd = mkstemp(path);
    ASSERT_GE(fd, 0);

    constexpr int kFrames = 4800;
    std::vector<int16_t> pcm(static_cast<size_t>(kFrames) * 2);
    for (int frame = 0; frame < kFrames; ++frame) {
        const auto value = static_cast<int16_t>(
            16000.0 * std::sin(2.0 * 3.14159265358979323846 * 440.0 * frame / 48000.0));
        pcm[static_cast<size_t>(frame) * 2] = value;
        pcm[static_cast<size_t>(frame) * 2 + 1] = value;
    }
    const size_t bytes = pcm.size() * sizeof(int16_t);
    ASSERT_EQ(write(fd, pcm.data(), bytes), static_cast<ssize_t>(bytes));
    close(fd);

    std::string error;
    const auto track = scratch::LoadMappedPcmFromFile(path, 48000, error);
    unlink(path);

    ASSERT_NE(track, nullptr) << error;
    EXPECT_EQ(track->frames, kFrames);
    EXPECT_EQ(track->sampleRate, 48000);
    ASSERT_NE(track->mapped, nullptr);
    EXPECT_FALSE(track->peaks.empty());
    EXPECT_TRUE(track->samples.empty());
    EXPECT_TRUE(track->pcm16.empty());

    Deck deck;
    deck.SetTrack(track);
    deck.SetPlaying(true);
    std::vector<float> output(512 * 2, 0.0f);
    deck.Render(output.data(), 512, 1.0f, 48000);

    double energy = 0.0;
    for (float sample : output) {
        energy += std::fabs(sample);
    }
    EXPECT_GT(energy, 100.0);
    EXPECT_EQ(deck.PositionFrames(), 512);
}

TEST(TrackLoader, RejectsUnknownData) {
    const std::vector<uint8_t> garbage = {'n', 'o', 'p', 'e', 1, 2, 3, 4};
    std::string error;
    const auto track = scratch::LoadTrackFromMemory(garbage.data(), garbage.size(), error);

    EXPECT_EQ(track, nullptr);
    EXPECT_FALSE(error.empty());
}

TEST(TrackLoader, RejectsTruncatedDataChunk) {
    auto wav = MakeWav16(2, 44100, {0, 1000, 2000, 3000});
    wav.resize(wav.size() - 4);
    std::string error;
    const auto track = scratch::LoadTrackFromMemory(wav.data(), wav.size(), error);

    EXPECT_EQ(track, nullptr);
    EXPECT_FALSE(error.empty());
}

TEST(TrackLoader, RejectsTruncatedHeader) {
    const std::vector<uint8_t> partial = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    std::string error;
    const auto track = scratch::LoadTrackFromMemory(partial.data(), partial.size(), error);

    EXPECT_EQ(track, nullptr);
    EXPECT_FALSE(error.empty());
}
