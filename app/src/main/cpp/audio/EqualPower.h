#pragma once

#include <algorithm>
#include <cmath>

struct StereoGains {
    float a;
    float b;
};

inline StereoGains EqualPowerGains(float crossfader) {
    const float x = std::clamp(crossfader, 0.0f, 1.0f);
    constexpr float kHalfPi = 1.5707963267948966f;
    return {std::cos(x * kHalfPi), std::sin(x * kHalfPi)};
}
