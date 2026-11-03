#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

inline void AppendLE16(std::vector<uint8_t>& out, uint16_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
}

inline void AppendLE32(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 24) & 0xFF));
}

inline void AppendBE16(std::vector<uint8_t>& out, uint16_t value) {
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(value & 0xFF));
}

inline void AppendBE32(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(static_cast<uint8_t>((value >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(value & 0xFF));
}

inline void AppendTag(std::vector<uint8_t>& out, const char* tag) {
    out.insert(out.end(), tag, tag + 4);
}

inline void AppendExtended80(std::vector<uint8_t>& out, double value) {
    if (value <= 0.0) {
        out.insert(out.end(), 10, 0);
        return;
    }
    int exponent = 0;
    const double fraction = std::frexp(value, &exponent);
    const uint16_t exponentField = static_cast<uint16_t>(exponent - 1 + 16383);
    const uint64_t mantissa = static_cast<uint64_t>(std::ldexp(fraction, 64));
    out.push_back(static_cast<uint8_t>((exponentField >> 8) & 0x7F));
    out.push_back(static_cast<uint8_t>(exponentField & 0xFF));
    for (int shift = 56; shift >= 0; shift -= 8) {
        out.push_back(static_cast<uint8_t>((mantissa >> shift) & 0xFF));
    }
}

inline std::vector<uint8_t> MakeWav16(uint16_t channels, uint32_t sampleRate,
                                      const std::vector<int16_t>& samples) {
    const uint32_t dataSize = static_cast<uint32_t>(samples.size() * 2);
    std::vector<uint8_t> out;
    AppendTag(out, "RIFF");
    AppendLE32(out, 36 + dataSize);
    AppendTag(out, "WAVE");
    AppendTag(out, "fmt ");
    AppendLE32(out, 16);
    AppendLE16(out, 1);
    AppendLE16(out, channels);
    AppendLE32(out, sampleRate);
    AppendLE32(out, sampleRate * channels * 2);
    AppendLE16(out, static_cast<uint16_t>(channels * 2));
    AppendLE16(out, 16);
    AppendTag(out, "data");
    AppendLE32(out, dataSize);
    for (int16_t sample : samples) {
        AppendLE16(out, static_cast<uint16_t>(sample));
    }
    return out;
}

inline std::vector<uint8_t> MakeWavFloat32(uint16_t channels, uint32_t sampleRate,
                                           const std::vector<float>& samples) {
    const uint32_t dataSize = static_cast<uint32_t>(samples.size() * 4);
    std::vector<uint8_t> out;
    AppendTag(out, "RIFF");
    AppendLE32(out, 36 + dataSize);
    AppendTag(out, "WAVE");
    AppendTag(out, "fmt ");
    AppendLE32(out, 16);
    AppendLE16(out, 3);
    AppendLE16(out, channels);
    AppendLE32(out, sampleRate);
    AppendLE32(out, sampleRate * channels * 4);
    AppendLE16(out, static_cast<uint16_t>(channels * 4));
    AppendLE16(out, 32);
    AppendTag(out, "data");
    AppendLE32(out, dataSize);
    for (float sample : samples) {
        uint32_t bits = 0;
        std::memcpy(&bits, &sample, sizeof(bits));
        AppendLE32(out, bits);
    }
    return out;
}

inline std::vector<uint8_t> MakeAiff16(uint16_t channels, uint32_t sampleRate,
                                       const std::vector<int16_t>& samples) {
    const uint32_t frames = static_cast<uint32_t>(samples.size() / channels);
    const uint32_t dataSize = static_cast<uint32_t>(samples.size() * 2);
    const uint32_t ssndSize = 8 + dataSize;
    std::vector<uint8_t> out;
    AppendTag(out, "FORM");
    AppendBE32(out, 4 + (8 + 18) + (8 + ssndSize));
    AppendTag(out, "AIFF");
    AppendTag(out, "COMM");
    AppendBE32(out, 18);
    AppendBE16(out, channels);
    AppendBE32(out, frames);
    AppendBE16(out, 16);
    AppendExtended80(out, static_cast<double>(sampleRate));
    AppendTag(out, "SSND");
    AppendBE32(out, ssndSize);
    AppendBE32(out, 0);
    AppendBE32(out, 0);
    for (int16_t sample : samples) {
        AppendBE16(out, static_cast<uint16_t>(sample));
    }
    return out;
}
