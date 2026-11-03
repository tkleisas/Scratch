#pragma once

#include <algorithm>
#include <cmath>

class Svf {
public:
    void Set(float cutoffHz, float q, float sampleRate) {
        const float nyquistLimit = sampleRate * 0.49f;
        cutoffHz = std::clamp(cutoffHz, 10.0f, nyquistLimit);
        q = std::max(q, 0.1f);
        g_ = std::tan(3.14159265358979323846f * cutoffHz / sampleRate);
        k_ = 1.0f / q;
        a1_ = 1.0f / (1.0f + g_ * (g_ + k_));
        a2_ = g_ * a1_;
        a3_ = g_ * a2_;
    }

    float ProcessLowPass(float in) {
        Update(in);
        return v2_;
    }

    float ProcessHighPass(float in) {
        Update(in);
        return in - k_ * v1_ - v2_;
    }

    void Reset() {
        ic1_ = 0.0f;
        ic2_ = 0.0f;
    }

private:
    void Update(float in) {
        const float v3 = in - ic2_;
        v1_ = a1_ * ic1_ + a2_ * v3;
        v2_ = ic2_ + a2_ * ic1_ + a3_ * v3;
        ic1_ = 2.0f * v1_ - ic1_;
        ic2_ = 2.0f * v2_ - ic2_;
    }

    float g_ = 0.0f;
    float k_ = 1.0f;
    float a1_ = 1.0f;
    float a2_ = 0.0f;
    float a3_ = 0.0f;
    float ic1_ = 0.0f;
    float ic2_ = 0.0f;
    float v1_ = 0.0f;
    float v2_ = 0.0f;
};
