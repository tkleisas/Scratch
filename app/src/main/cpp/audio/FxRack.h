#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <vector>

#include "effects/AudioEffect.h"

class FxRack {
public:
    enum Effect : int {
        kFilter = 0,
        kCrush = 1,
        kEcho = 2,
        kReverb = 3,
        kTape = 4,
        kCompressor = 5,
        kLimiter = 6,
        kEffectCount = 7,
    };

    enum Chain : int {
        kInsert = 0,
        kPunch = 1,
        kChainCount = 2,
    };

    static constexpr int kMaxParams = 8;
    static constexpr int kMaxRacks = 3;

    void Init(double sampleRate, int maxBlockSize);
    void Process(float* interleavedStereo, int frames);

    void SetEnabled(Chain chain, int effect, bool enabled);
    void SetOrder(Chain chain, const int* order);
    void SetParam(Chain chain, int effect, int index, float value);
    float GetParam(Chain chain, int effect, int index) const;
    int ParamCount(Chain chain, int effect) const;

    void SetPunchActive(int effect, bool active);
    void SetPunchSend(int effect, float send);
    float GetPunchSend(int effect) const;
    bool PunchActive(int effect) const;

    bool IsReady() const { return ready_; }

private:
    struct Node {
        std::unique_ptr<yawn::effects::AudioEffect> effect;
        int type = -1;
        int paramCount = 0;
        int mixParam = -1;
        int tailSamples = 0;
        std::atomic<bool> enabled{false};
        std::atomic<bool> padActive{false};
        std::atomic<float> send{0.35f};
        std::array<std::atomic<float>, kMaxParams> params{};
        std::array<float, kMaxParams> lastParams{};
        float returnGain = 0.0f;
        int tailRemaining = 0;
        bool wasActive = false;
    };

    struct ChainState {
        std::array<Node, kEffectCount> nodes;
        std::atomic<uint64_t> order{0};
        std::atomic<uint32_t> enabledMask{0};
    };

    static uint64_t PackOrder(const int* order);
    static void DefaultOrder(int* order);
    static int MixParamFor(int type);
    static int TailSamplesFor(int type, double sampleRate);

    void ProcessInsert(float* buffer, int frames);
    void ProcessPunch(const float* dry, float* buffer, int frames);
    void ApplyParams(Node& node, bool punch);

    ChainState chains_[kChainCount];
    std::vector<float> dryInput_;
    std::vector<float> punchScratch_;
    double sampleRate_ = 48000.0;
    int maxBlockSize_ = 4096;
    bool ready_ = false;
};
