#pragma once

// HallReverb — Freeverb-style stereo room/hall reverb.
//
// The algorithm is based on Freeverb by Jezar at Dreampoint (2000), a
// public-domain reverberation design: eight parallel damped comb filters
// per channel with a 23-sample stereo spread, followed by four series
// allpass diffusers. This implementation adds a stereo pre-delay and maps
// the exposed controls onto Freeverb's room-size, damping, width and
// wet/dry parameters. It is intentionally not a plate topology.

#include "effects/AudioEffect.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace yawn {
namespace effects {

class HallReverb : public AudioEffect {
public:
    enum Param {
        kPreDelay = 0,
        kDecay,
        kDamping,
        kDiffusion,
        kWidth,
        kWetDry,
        kParamCount
    };

    const char* name() const override { return "Hall Reverb"; }
    const char* id() const override { return "reverb"; }

    void init(double sampleRate, int maxBlockSize) override;
    void reset() override;
    void process(float* buffer, int numFrames, int numChannels) override;

    int parameterCount() const override { return kParamCount; }
    const ParameterInfo& parameterInfo(int index) const override;

    float getParameter(int index) const override {
        return (index >= 0 && index < kParamCount) ? m_params[index] : 0.0f;
    }

    void setParameter(int index, float value) override {
        if (index >= 0 && index < kParamCount) {
            m_params[index] = std::clamp(value, 0.0f, 1.0f);
            updateParams();
        }
    }

private:
    struct Comb {
        std::vector<float> buffer;
        int index = 0;
        float filterStore = 0.0f;
        float feedback = 0.84f;
        float damp1 = 0.2f;
        float damp2 = 0.8f;

        void init(int size) {
            buffer.assign(static_cast<size_t>(std::max(size, 1)), 0.0f);
            index = 0;
            filterStore = 0.0f;
        }

        void clear() {
            std::fill(buffer.begin(), buffer.end(), 0.0f);
            index = 0;
            filterStore = 0.0f;
        }

        float process(float input) {
            const float output = buffer[static_cast<size_t>(index)];
            filterStore = output * damp2 + filterStore * damp1;
            if (std::fabs(filterStore) < 1e-18f) filterStore = 0.0f;
            buffer[static_cast<size_t>(index)] = input + filterStore * feedback;
            if (++index >= static_cast<int>(buffer.size())) index = 0;
            return output;
        }
    };

    struct Allpass {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;

        void init(int size) {
            buffer.assign(static_cast<size_t>(std::max(size, 1)), 0.0f);
            index = 0;
        }

        void clear() {
            std::fill(buffer.begin(), buffer.end(), 0.0f);
            index = 0;
        }

        float process(float input) {
            const float buffered = buffer[static_cast<size_t>(index)];
            const float output = buffered - input;
            buffer[static_cast<size_t>(index)] = input + buffered * feedback;
            if (++index >= static_cast<int>(buffer.size())) index = 0;
            return output;
        }
    };

    static constexpr int kCombCount = 8;
    static constexpr int kAllpassCount = 4;

    void updateParams();

    std::array<Comb, kCombCount> m_combLeft;
    std::array<Comb, kCombCount> m_combRight;
    std::array<Allpass, kAllpassCount> m_allpassLeft;
    std::array<Allpass, kAllpassCount> m_allpassRight;
    std::vector<float> m_preDelayLeft;
    std::vector<float> m_preDelayRight;
    int m_preDelayPos = 0;
    int m_preDelaySamples = 0;

    float m_params[kParamCount] = {0.04f, 0.677f, 0.5f, 0.7f, 1.0f, 0.3f};
    float m_feedback = 0.84f;
    float m_damp1 = 0.2f;
    float m_damp2 = 0.8f;
    float m_allpassFeedback = 0.5f;
    float m_width = 1.0f;
    float m_wet = 0.3f;
    float m_dry = 0.7f;
    double m_sampleRate = 48000.0;
};

} // namespace effects
} // namespace yawn
