#pragma once

#include "core/ParameterInfo.h"

#include <string>

namespace yawn {
namespace effects {

using ParameterInfo = ::yawn::ParameterInfo;

class AudioEffect {
public:
    virtual ~AudioEffect() = default;

    virtual void init(double sampleRate, int maxBlockSize) = 0;
    virtual void reset() = 0;
    virtual void process(float* buffer, int numFrames, int numChannels) = 0;

    virtual int parameterCount() const = 0;
    virtual const ParameterInfo& parameterInfo(int index) const = 0;
    virtual float getParameter(int index) const = 0;
    virtual void setParameter(int index, float value) = 0;

    virtual const char* name() const = 0;
    virtual const char* id() const = 0;

    bool bypassed() const { return m_bypassed; }
    void setBypassed(bool bypassed) { m_bypassed = bypassed; }

    float mix() const { return m_mix; }
    void setMix(float mix) {
        m_mix = mix < 0.0f ? 0.0f : (mix > 1.0f ? 1.0f : mix);
    }

    virtual int latencySamples() const { return 0; }
    virtual void setTempo(double bpm, double beatPosition, bool playing) {
        (void)bpm;
        (void)beatPosition;
        (void)playing;
    }

protected:
    double m_sampleRate = 48000.0;
    int m_maxBlockSize = 4096;
    bool m_bypassed = false;
    float m_mix = 1.0f;
};

} // namespace effects
} // namespace yawn
