#pragma once

#include <atomic>
#include <vector>

namespace scratch {

class SpectrumAnalyzer {
public:
    static constexpr int kFftSize = 1024;
    static constexpr int kBandCount = 32;

    void Init(int sampleRate);
    void Reset();
    void Process(const float* interleavedStereo, int frames);
    void Push(float left, float right);
    void GetBands(float* out, int count) const;
    static float BandFrequency(int index);

private:
    void Analyze();

    int sampleRate_ = 48000;
    std::vector<float> window_;
    std::vector<float> real_;
    std::vector<float> imag_;
    std::vector<float> ring_;
    int writeIndex_ = 0;
    std::atomic<float> bands_[kBandCount];
};

}
