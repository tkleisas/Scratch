#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

struct MappedPcm {
    const int16_t* data = nullptr;
    size_t samples = 0;
    void* mapping = nullptr;
    size_t bytes = 0;
#ifdef _WIN32
    void* fileMapping = nullptr;
#endif

    ~MappedPcm();
};

struct TrackData {
    std::vector<float> samples;
    std::vector<int16_t> pcm16;
    std::shared_ptr<MappedPcm> mapped;
    std::vector<float> peaks;
    int sampleRate = 0;
    int64_t frames = 0;
    int channels = 2;
};
