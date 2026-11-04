#pragma once

#include <cmath>

namespace scratch {

// Soft-knee ceiling for the master output. The first 0.95 is linear, then
// tanh shapes overs into an asymptote at 1.0 so hot mixes saturate instead
// of producing hard digital clipping.
inline float SoftClip(float sample) {
    constexpr float kThreshold = 0.95f;
    constexpr float kRange = 1.0f - kThreshold;
    const float magnitude = std::fabs(sample);
    if (magnitude <= kThreshold) {
        return sample;
    }
    const float shaped = kThreshold + kRange * std::tanh((magnitude - kThreshold) / kRange);
    return std::copysign(shaped, sample);
}

} // namespace scratch
