#include "effects/HallReverb.h"

#include <cstdio>

namespace yawn::effects {
namespace {

constexpr int kCombTuning[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
constexpr double kFreeverbRate = 44100.0;
constexpr float kInputGain = 0.015f;

void fmtMs(float v, char* b, int n) {
    std::snprintf(b, size_t(n), "%.0f ms", double(v) * 250.0);
}
void fmtPct(float v, char* b, int n) {
    std::snprintf(b, size_t(n), "%.0f%%", double(v) * 100.0);
}

} // namespace

void HallReverb::init(double sampleRate, int maxBlockSize) {
    (void)maxBlockSize;
    m_sampleRate = sampleRate > 0.0 ? sampleRate : 48000.0;
    const double scale = m_sampleRate / kFreeverbRate;
    const int spread = std::max(1, static_cast<int>(std::llround(kStereoSpread * scale)));

    for (int i = 0; i < kCombCount; ++i) {
        m_combLeft[i].init(std::max(1, static_cast<int>(std::llround(kCombTuning[i] * scale))));
        m_combRight[i].init(
            std::max(1, static_cast<int>(std::llround((kCombTuning[i] + spread) * scale))));
    }
    for (int i = 0; i < kAllpassCount; ++i) {
        const int delay = std::max(1, static_cast<int>(std::llround(kAllpassTuning[i] * scale)));
        m_allpassLeft[i].init(delay);
        m_allpassRight[i].init(delay);
    }

    const int predelaySize = static_cast<int>(m_sampleRate * 0.25) + 2;
    m_preDelayLeft.assign(static_cast<size_t>(predelaySize), 0.0f);
    m_preDelayRight.assign(static_cast<size_t>(predelaySize), 0.0f);
    m_preDelayPos = 0;

    reset();
    updateParams();
}

void HallReverb::reset() {
    for (auto& comb : m_combLeft) comb.clear();
    for (auto& comb : m_combRight) comb.clear();
    for (auto& allpass : m_allpassLeft) allpass.clear();
    for (auto& allpass : m_allpassRight) allpass.clear();
    std::fill(m_preDelayLeft.begin(), m_preDelayLeft.end(), 0.0f);
    std::fill(m_preDelayRight.begin(), m_preDelayRight.end(), 0.0f);
    m_preDelayPos = 0;
}

void HallReverb::updateParams() {
    m_preDelaySamples = static_cast<int>(m_params[kPreDelay] * 0.25f * m_sampleRate);
    m_preDelaySamples = std::clamp(
        m_preDelaySamples,
        0,
        static_cast<int>(m_preDelayLeft.size()) - 2);

    // Freeverb's room size maps to comb feedback. Keep enough headroom for
    // dense hall tails without allowing runaway feedback.
    m_feedback = 0.70f + 0.28f * m_params[kDecay];
    const float damp = m_params[kDamping] * 0.4f;
    m_damp1 = damp;
    m_damp2 = 1.0f - damp;
    m_allpassFeedback = 0.30f + 0.40f * m_params[kDiffusion];
    m_width = m_params[kWidth];
    m_wet = m_params[kWetDry] * 0.6f;
    m_dry = 1.0f - m_params[kWetDry];

    for (auto& comb : m_combLeft) {
        comb.feedback = m_feedback;
        comb.damp1 = m_damp1;
        comb.damp2 = m_damp2;
    }
    for (auto& comb : m_combRight) {
        comb.feedback = m_feedback;
        comb.damp1 = m_damp1;
        comb.damp2 = m_damp2;
    }
    for (auto& allpass : m_allpassLeft) allpass.feedback = m_allpassFeedback;
    for (auto& allpass : m_allpassRight) allpass.feedback = m_allpassFeedback;
}

const ParameterInfo& HallReverb::parameterInfo(int index) const {
    static const ParameterInfo infos[kParamCount] = {
        {"Pre-Delay", 0.0f, 1.0f, 0.04f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtMs},
        {"Decay", 0.0f, 1.0f, 0.677f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtPct},
        {"Damping", 0.0f, 1.0f, 0.5f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtPct},
        {"Diffusion", 0.0f, 1.0f, 0.7f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtPct},
        {"Width", 0.0f, 1.0f, 1.0f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtPct},
        {"Wet/Dry", 0.0f, 1.0f, 0.3f, "", false, false,
            WidgetHint::DentedKnob, nullptr, 0, fmtPct},
    };
    return infos[std::clamp(index, 0, kParamCount - 1)];
}

void HallReverb::process(float* buffer, int numFrames, int numChannels) {
    if (m_bypassed || numChannels <= 0) return;

    const int predelaySize = static_cast<int>(m_preDelayLeft.size());
    const float wet1 = m_wet * (m_width * 0.5f + 0.5f);
    const float wet2 = m_wet * ((1.0f - m_width) * 0.5f);

    for (int i = 0; i < numFrames; ++i) {
        const float inLeft = buffer[static_cast<size_t>(i) * numChannels];
        const float inRight = numChannels > 1
            ? buffer[static_cast<size_t>(i) * numChannels + 1]
            : inLeft;

        m_preDelayLeft[static_cast<size_t>(m_preDelayPos)] = inLeft;
        m_preDelayRight[static_cast<size_t>(m_preDelayPos)] = inRight;
        int readPos = m_preDelayPos - m_preDelaySamples;
        if (readPos < 0) readPos += predelaySize;
        const float delayedLeft = m_preDelayLeft[static_cast<size_t>(readPos)];
        const float delayedRight = m_preDelayRight[static_cast<size_t>(readPos)];
        const float input = (delayedLeft + delayedRight) * 0.5f * kInputGain;

        float outLeft = 0.0f;
        float outRight = 0.0f;
        for (int c = 0; c < kCombCount; ++c) {
            outLeft += m_combLeft[c].process(input);
            outRight += m_combRight[c].process(input);
        }
        for (int a = 0; a < kAllpassCount; ++a) {
            outLeft = m_allpassLeft[a].process(outLeft);
            outRight = m_allpassRight[a].process(outRight);
        }

        const float wetLeft = outLeft * wet1 + outRight * wet2;
        const float wetRight = outRight * wet1 + outLeft * wet2;
        buffer[static_cast<size_t>(i) * numChannels] = inLeft * m_dry + wetLeft;
        if (numChannels > 1) {
            buffer[static_cast<size_t>(i) * numChannels + 1] = inRight * m_dry + wetRight;
        }

        if (++m_preDelayPos >= predelaySize) m_preDelayPos = 0;
    }
}

} // namespace yawn::effects
