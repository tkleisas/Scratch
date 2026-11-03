#include "TrackLoader.h"

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <fcntl.h>
#include <io.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace scratch {
namespace {

constexpr size_t kMaxFileBytes = 1536ull * 1024ull * 1024ull;
constexpr int kPeakBuckets = 2048;

#ifdef _WIN32
std::wstring Utf8ToWide(const std::string& value) {
    if (value.empty()) return {};
    const int length = MultiByteToWideChar(
        CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (length <= 0) return {};
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(
        CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
        result.data(), length);
    return result;
}
#endif

enum class Encoding {
    S8Unsigned,
    S8Signed,
    S16Le,
    S16Be,
    S24Le,
    S24Be,
    S32Le,
    S32Be,
    F32Le,
};

uint16_t ReadLe16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t ReadLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

uint16_t ReadBe16(const uint8_t* p) {
    return static_cast<uint16_t>((p[0] << 8) | p[1]);
}

uint32_t ReadBe32(const uint8_t* p) {
    return static_cast<uint32_t>((p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]);
}

double ReadExtended80(const uint8_t* p) {
    const uint16_t exponent = static_cast<uint16_t>(((p[0] & 0x7F) << 8) | p[1]);
    uint64_t mantissa = 0;
    for (int i = 0; i < 8; ++i) {
        mantissa = (mantissa << 8) | p[2 + i];
    }
    if (exponent == 0 && mantissa == 0) {
        return 0.0;
    }
    const double value = std::ldexp(static_cast<double>(mantissa), exponent - 16383 - 63);
    return (p[0] & 0x80) ? -value : value;
}

float DecodeSample(const uint8_t* p, Encoding encoding) {
    switch (encoding) {
        case Encoding::S8Unsigned:
            return (static_cast<int>(p[0]) - 128) / 128.0f;
        case Encoding::S8Signed:
            return static_cast<int8_t>(p[0]) / 128.0f;
        case Encoding::S16Le:
            return static_cast<int16_t>(ReadLe16(p)) / 32768.0f;
        case Encoding::S16Be:
            return static_cast<int16_t>(ReadBe16(p)) / 32768.0f;
        case Encoding::S24Le: {
            int32_t value = p[0] | (p[1] << 8) | (p[2] << 16);
            if (value & 0x800000) {
                value |= static_cast<int32_t>(0xFF000000);
            }
            return value / 8388608.0f;
        }
        case Encoding::S24Be: {
            int32_t value = (p[0] << 16) | (p[1] << 8) | p[2];
            if (value & 0x800000) {
                value |= static_cast<int32_t>(0xFF000000);
            }
            return value / 8388608.0f;
        }
        case Encoding::S32Le:
            return static_cast<int32_t>(ReadLe32(p)) / 2147483648.0f;
        case Encoding::S32Be:
            return static_cast<int32_t>(ReadBe32(p)) / 2147483648.0f;
        case Encoding::F32Le: {
            const uint32_t bits = ReadLe32(p);
            float value = 0.0f;
            std::memcpy(&value, &bits, sizeof(value));
            return value;
        }
    }
    return 0.0f;
}

int BytesPerSample(Encoding encoding) {
    switch (encoding) {
        case Encoding::S8Unsigned:
        case Encoding::S8Signed:
            return 1;
        case Encoding::S16Le:
        case Encoding::S16Be:
            return 2;
        case Encoding::S24Le:
        case Encoding::S24Be:
            return 3;
        case Encoding::S32Le:
        case Encoding::S32Be:
        case Encoding::F32Le:
            return 4;
    }
    return 0;
}

std::shared_ptr<TrackData> BuildTrack(const uint8_t* audio, int64_t frames, int channels,
                                      int sampleRate, Encoding encoding, std::string& error);

std::shared_ptr<TrackData> ResampleTrack(std::shared_ptr<TrackData> input, int targetSampleRate) {
    if (!input || targetSampleRate <= 0 || targetSampleRate == input->sampleRate) {
        return input;
    }

    const double ratio = static_cast<double>(targetSampleRate) / input->sampleRate;
    const int64_t outputFrames =
        std::max<int64_t>(1, std::llround(input->frames * ratio));
    const int64_t inputFrames = input->frames;

    auto output = std::make_shared<TrackData>();
    output->sampleRate = targetSampleRate;
    output->frames = outputFrames;
    output->channels = 2;
    output->samples.resize(static_cast<size_t>(outputFrames) * 2);
    output->peaks = input->peaks;

    auto sampleAt = [&](int64_t frame, int channel) {
        frame = std::clamp<int64_t>(frame, 0, inputFrames - 1);
        return input->samples[static_cast<size_t>(frame) * 2 + channel];
    };

    for (int64_t frame = 0; frame < outputFrames; ++frame) {
        const double sourcePosition = frame / ratio;
        const int64_t i1 = std::clamp<int64_t>(
            static_cast<int64_t>(std::floor(sourcePosition)), 0, inputFrames - 1);
        const double t = sourcePosition - static_cast<double>(i1);
        const int64_t i0 = i1 > 0 ? i1 - 1 : i1;
        const int64_t i2 = i1 + 1 < inputFrames ? i1 + 1 : i1;
        const int64_t i3 = i1 + 2 < inputFrames ? i1 + 2 : i1;

        for (int channel = 0; channel < 2; ++channel) {
            const float p0 = sampleAt(i0, channel);
            const float p1 = sampleAt(i1, channel);
            const float p2 = sampleAt(i2, channel);
            const float p3 = sampleAt(i3, channel);
            const float a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
            const float b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
            const float c = -0.5f * p0 + 0.5f * p2;
            output->samples[static_cast<size_t>(frame) * 2 + channel] =
                ((a * static_cast<float>(t) + b) * static_cast<float>(t) + c) *
                    static_cast<float>(t) +
                p1;
        }
    }

    return output;
}

std::shared_ptr<TrackData> BuildTrack(const uint8_t* audio, int64_t frames, int channels,
                                      int sampleRate, Encoding encoding, std::string& error) {
    if (frames <= 0) {
        error = "Audio track contains no frames";
        return nullptr;
    }
    if (channels <= 0 || channels > 8) {
        error = "Unsupported channel count";
        return nullptr;
    }
    if (sampleRate < 8000 || sampleRate > 384000) {
        error = "Unsupported sample rate";
        return nullptr;
    }

    const int bytesPerSample = BytesPerSample(encoding);
    const int64_t frameBytes = static_cast<int64_t>(bytesPerSample) * channels;
    if (frames > (static_cast<int64_t>(1) << 40) / frameBytes) {
        error = "Track is too large";
        return nullptr;
    }

    auto track = std::make_shared<TrackData>();
    track->sampleRate = sampleRate;
    track->frames = frames;
    track->channels = 2;
    track->samples.resize(static_cast<size_t>(frames) * 2);

    for (int64_t frame = 0; frame < frames; ++frame) {
        const uint8_t* row = audio + frame * frameBytes;
        const float left = DecodeSample(row, encoding);
        const float right = channels > 1 ? DecodeSample(row + bytesPerSample, encoding) : left;
        track->samples[static_cast<size_t>(frame) * 2] = left;
        track->samples[static_cast<size_t>(frame) * 2 + 1] = right;
    }

    track->peaks.resize(kPeakBuckets, 0.0f);
    for (int bucket = 0; bucket < kPeakBuckets; ++bucket) {
        const int64_t begin = frames * bucket / kPeakBuckets;
        const int64_t end = std::max(begin + 1, frames * (bucket + 1) / kPeakBuckets);
        float peak = 0.0f;
        for (int64_t frame = begin; frame < end && frame < frames; ++frame) {
            peak = std::max(peak, std::fabs(track->samples[static_cast<size_t>(frame) * 2]));
            peak = std::max(peak, std::fabs(track->samples[static_cast<size_t>(frame) * 2 + 1]));
        }
        track->peaks[bucket] = std::min(peak, 1.0f);
    }

    return track;
}

std::shared_ptr<TrackData> ParseWav(const uint8_t* data, size_t size, std::string& error) {
    if (size < 12 || std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WAVE", 4) != 0) {
        error = "Not a RIFF/WAVE file";
        return nullptr;
    }

    bool haveFormat = false;
    bool haveData = false;
    uint16_t format = 0;
    uint16_t channels = 0;
    uint16_t bits = 0;
    uint32_t sampleRate = 0;
    const uint8_t* audio = nullptr;
    size_t audioBytes = 0;

    size_t offset = 12;
    while (offset + 8 <= size) {
        const uint8_t* header = data + offset;
        const uint32_t chunkSize = ReadLe32(header + 4);
        const size_t payload = offset + 8;
        if (chunkSize > size - payload) {
            error = "Truncated WAV chunk";
            return nullptr;
        }

        if (std::memcmp(header, "fmt ", 4) == 0) {
            if (chunkSize < 16) {
                error = "Invalid WAV format chunk";
                return nullptr;
            }
            format = ReadLe16(data + payload);
            channels = ReadLe16(data + payload + 2);
            sampleRate = ReadLe32(data + payload + 4);
            bits = ReadLe16(data + payload + 14);
            if (format == 0xFFFE) {
                if (chunkSize < 40) {
                    error = "Invalid WAV extensible format chunk";
                    return nullptr;
                }
                format = ReadLe16(data + payload + 24);
            }
            haveFormat = true;
        } else if (std::memcmp(header, "data", 4) == 0) {
            audio = data + payload;
            audioBytes = chunkSize;
            haveData = true;
        }

        const size_t advance = 8u + chunkSize + (chunkSize & 1u);
        offset += advance;
    }

    if (!haveFormat || !haveData) {
        error = "WAV file is missing format or data";
        return nullptr;
    }
    if (format != 1 && format != 3) {
        error = "Unsupported WAV encoding";
        return nullptr;
    }
    if (channels == 0 || sampleRate == 0) {
        error = "Invalid WAV format values";
        return nullptr;
    }

    Encoding encoding = Encoding::S16Le;
    if (format == 3) {
        if (bits != 32) {
            error = "Unsupported WAV float size";
            return nullptr;
        }
        encoding = Encoding::F32Le;
    } else {
        switch (bits) {
            case 8:
                encoding = Encoding::S8Unsigned;
                break;
            case 16:
                encoding = Encoding::S16Le;
                break;
            case 24:
                encoding = Encoding::S24Le;
                break;
            case 32:
                encoding = Encoding::S32Le;
                break;
            default:
                error = "Unsupported WAV bit depth";
                return nullptr;
        }
    }

    const int64_t frameBytes = static_cast<int64_t>(BytesPerSample(encoding)) * channels;
    if (frameBytes <= 0 || audioBytes < static_cast<size_t>(frameBytes)) {
        error = "WAV data chunk is too small";
        return nullptr;
    }
    const int64_t frames = static_cast<int64_t>(audioBytes / frameBytes);
    return BuildTrack(audio, frames, channels, static_cast<int>(sampleRate), encoding, error);
}

std::shared_ptr<TrackData> ParseAiff(const uint8_t* data, size_t size, std::string& error) {
    if (size < 12 || std::memcmp(data, "FORM", 4) != 0) {
        error = "Not an AIFF file";
        return nullptr;
    }
    const bool isAifc = std::memcmp(data + 8, "AIFC", 4) == 0;
    if (!isAifc && std::memcmp(data + 8, "AIFF", 4) != 0) {
        error = "Not an AIFF file";
        return nullptr;
    }

    bool haveCommon = false;
    bool haveSound = false;
    uint16_t channels = 0;
    uint32_t declaredFrames = 0;
    uint16_t bits = 0;
    uint32_t sampleRate = 0;
    bool littleEndian = false;
    const uint8_t* audio = nullptr;
    size_t audioBytes = 0;

    size_t offset = 12;
    while (offset + 8 <= size) {
        const uint8_t* header = data + offset;
        const uint32_t chunkSize = ReadBe32(header + 4);
        const size_t payload = offset + 8;
        if (chunkSize > size - payload) {
            error = "Truncated AIFF chunk";
            return nullptr;
        }

        if (std::memcmp(header, "COMM", 4) == 0) {
            if (chunkSize < 18) {
                error = "Invalid AIFF common chunk";
                return nullptr;
            }
            channels = ReadBe16(data + payload);
            declaredFrames = ReadBe32(data + payload + 2);
            bits = ReadBe16(data + payload + 6);
            sampleRate = static_cast<uint32_t>(std::llround(ReadExtended80(data + payload + 8)));
            if (isAifc && chunkSize >= 22) {
                const char* compression = reinterpret_cast<const char*>(data + payload + 18);
                if (std::memcmp(compression, "NONE", 4) == 0) {
                    littleEndian = false;
                } else if (std::memcmp(compression, "sowt", 4) == 0) {
                    littleEndian = true;
                } else {
                    error = "Compressed AIFC is not supported";
                    return nullptr;
                }
            }
            haveCommon = true;
        } else if (std::memcmp(header, "SSND", 4) == 0) {
            if (chunkSize < 8) {
                error = "Invalid AIFF sound chunk";
                return nullptr;
            }
            const uint32_t soundOffset = ReadBe32(data + payload);
            if (soundOffset > chunkSize - 8) {
                error = "Invalid AIFF sound offset";
                return nullptr;
            }
            audio = data + payload + 8 + soundOffset;
            audioBytes = chunkSize - 8 - soundOffset;
            haveSound = true;
        }

        offset += 8u + chunkSize + (chunkSize & 1u);
    }

    if (!haveCommon || !haveSound) {
        error = "AIFF file is missing common or sound data";
        return nullptr;
    }
    if (channels == 0 || sampleRate == 0 || bits == 0) {
        error = "Invalid AIFF format values";
        return nullptr;
    }

    Encoding encoding = Encoding::S16Be;
    switch (bits) {
        case 8:
            encoding = Encoding::S8Signed;
            break;
        case 16:
            encoding = littleEndian ? Encoding::S16Le : Encoding::S16Be;
            break;
        case 24:
            encoding = littleEndian ? Encoding::S24Le : Encoding::S24Be;
            break;
        case 32:
            encoding = littleEndian ? Encoding::S32Le : Encoding::S32Be;
            break;
        default:
            error = "Unsupported AIFF bit depth";
            return nullptr;
    }

    const int64_t frameBytes = static_cast<int64_t>(BytesPerSample(encoding)) * channels;
    if (frameBytes <= 0 || audioBytes < static_cast<size_t>(frameBytes)) {
        error = "AIFF sound data is too small";
        return nullptr;
    }
    int64_t frames = static_cast<int64_t>(audioBytes / frameBytes);
    if (declaredFrames > 0 && declaredFrames < static_cast<uint64_t>(frames)) {
        frames = declaredFrames;
    }
    return BuildTrack(audio, frames, channels, static_cast<int>(sampleRate), encoding, error);
}

}

std::shared_ptr<TrackData> LoadTrackFromMemory(const uint8_t* data, size_t size, std::string& error,
                                               int targetSampleRate) {
    error.clear();
    if (data == nullptr || size < 12) {
        error = "File is too small to be audio";
        return nullptr;
    }

    std::shared_ptr<TrackData> track;
    if (std::memcmp(data, "RIFF", 4) == 0) {
        track = ParseWav(data, size, error);
    } else if (std::memcmp(data, "FORM", 4) == 0) {
        track = ParseAiff(data, size, error);
    } else {
        error = "Unsupported audio container (WAV and AIFF only)";
        return nullptr;
    }

    return track ? ResampleTrack(std::move(track), targetSampleRate) : nullptr;
}

std::shared_ptr<TrackData> LoadTrackFromFd(int fd, std::string& error, int targetSampleRate) {
    error.clear();
#ifdef _WIN32
    struct __stat64 info {};
    if (_fstat64(fd, &info) != 0) {
        error = "Cannot stat audio file";
        return nullptr;
    }
    if (info.st_size <= 0) {
        error = "Audio file is empty";
        return nullptr;
    }
    if (static_cast<uint64_t>(info.st_size) > kMaxFileBytes) {
        error = "Audio file is too large";
        return nullptr;
    }
    _lseeki64(fd, 0, SEEK_SET);

    std::vector<uint8_t> bytes(static_cast<size_t>(info.st_size));
    size_t total = 0;
    while (total < bytes.size()) {
        const size_t remaining = bytes.size() - total;
        const unsigned int chunk = static_cast<unsigned int>(
            std::min<size_t>(remaining, 1u << 30));
        const int read = _read(fd, bytes.data() + total, chunk);
        if (read < 0) {
            if (errno == EINTR) {
                continue;
            }
            error = "Failed to read audio file";
            return nullptr;
        }
        if (read == 0) {
            error = "Unexpected end of audio file";
            return nullptr;
        }
        total += static_cast<size_t>(read);
    }
#else
    struct stat info {};
    if (fstat(fd, &info) != 0) {
        error = "Cannot stat audio file";
        return nullptr;
    }
    if (info.st_size <= 0) {
        error = "Audio file is empty";
        return nullptr;
    }
    if (static_cast<uint64_t>(info.st_size) > kMaxFileBytes) {
        error = "Audio file is too large";
        return nullptr;
    }

    std::vector<uint8_t> bytes(static_cast<size_t>(info.st_size));
    size_t total = 0;
    while (total < bytes.size()) {
        const ssize_t read = pread(fd, bytes.data() + total, bytes.size() - total,
                                   static_cast<off_t>(total));
        if (read < 0) {
            if (errno == EINTR) {
                continue;
            }
            error = "Failed to read audio file";
            return nullptr;
        }
        if (read == 0) {
            error = "Unexpected end of audio file";
            return nullptr;
        }
        total += static_cast<size_t>(read);
    }
#endif

    return LoadTrackFromMemory(bytes.data(), bytes.size(), error, targetSampleRate);
}

std::shared_ptr<TrackData> LoadTrackFromFile(const std::string& path, std::string& error,
                                             int targetSampleRate) {
#ifdef _WIN32
    HANDLE handle = CreateFileW(
        Utf8ToWide(path).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        error = "Cannot open audio file";
        return nullptr;
    }
    const int fd = _open_osfhandle(
        reinterpret_cast<intptr_t>(handle), _O_RDONLY | _O_BINARY);
    if (fd < 0) {
        CloseHandle(handle);
        error = "Cannot open audio file";
        return nullptr;
    }
    auto track = LoadTrackFromFd(fd, error, targetSampleRate);
    _close(fd);
    return track;
#else
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        error = "Cannot open audio file";
        return nullptr;
    }
    auto track = LoadTrackFromFd(fd, error, targetSampleRate);
    close(fd);
    return track;
#endif
}

namespace {

std::vector<float> ComputePcm16Peaks(const int16_t* samples, int64_t frames) {
    std::vector<float> peaks(kPeakBuckets, 0.0f);
    for (int bucket = 0; bucket < kPeakBuckets; ++bucket) {
        const int64_t begin = frames * bucket / kPeakBuckets;
        const int64_t end = std::max(begin + 1, frames * (bucket + 1) / kPeakBuckets);
        float peak = 0.0f;
        for (int64_t frame = begin; frame < end && frame < frames; ++frame) {
            const float left = std::fabs(samples[static_cast<size_t>(frame) * 2] / 32768.0f);
            const float right =
                std::fabs(samples[static_cast<size_t>(frame) * 2 + 1] / 32768.0f);
            peak = std::max(peak, std::max(left, right));
        }
        peaks[bucket] = std::min(peak, 1.0f);
    }
    return peaks;
}

}

std::shared_ptr<TrackData> BuildTrackFromPcm16(std::vector<int16_t> pcm, int sampleRate,
                                               std::string& error) {
    error.clear();
    if (pcm.empty() || pcm.size() % 2 != 0) {
        error = "Decoded PCM data is empty or not stereo";
        return nullptr;
    }
    if (sampleRate < 8000 || sampleRate > 192000) {
        error = "Unsupported decoded sample rate";
        return nullptr;
    }

    const int64_t frames = static_cast<int64_t>(pcm.size() / 2);
    auto track = std::make_shared<TrackData>();
    track->sampleRate = sampleRate;
    track->frames = frames;
    track->channels = 2;
    track->peaks = ComputePcm16Peaks(pcm.data(), frames);
    track->pcm16 = std::move(pcm);
    return track;
}

std::shared_ptr<TrackData> LoadMappedPcmFromFile(const std::string& path, int sampleRate,
                                                 std::string& error) {
    error.clear();
    if (sampleRate < 8000 || sampleRate > 192000) {
        error = "Unsupported decoded sample rate";
        return nullptr;
    }

#ifdef _WIN32
    HANDLE file = CreateFileW(
        Utf8ToWide(path).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        error = "Cannot open cached PCM file";
        return nullptr;
    }
    LARGE_INTEGER fileSize{};
    if (!GetFileSizeEx(file, &fileSize) || fileSize.QuadPart < 4) {
        CloseHandle(file);
        error = "Cached PCM file is empty";
        return nullptr;
    }
    const uint64_t bytes64 = static_cast<uint64_t>(fileSize.QuadPart);
    if (bytes64 > kMaxFileBytes || bytes64 % 4 != 0) {
        CloseHandle(file);
        error = bytes64 % 4 != 0
            ? "Cached PCM file is not stereo 16-bit"
            : "Cached PCM file is too large";
        return nullptr;
    }
    const size_t bytes = static_cast<size_t>(bytes64);
    HANDLE mappingHandle = CreateFileMappingW(
        file, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!mappingHandle) {
        CloseHandle(file);
        error = "Cannot map cached PCM file";
        return nullptr;
    }
    void* mapping = MapViewOfFile(mappingHandle, FILE_MAP_READ, 0, 0, 0);
    CloseHandle(file);
    if (!mapping) {
        CloseHandle(mappingHandle);
        error = "Cannot map cached PCM file";
        return nullptr;
    }

    auto mapped = std::make_shared<MappedPcm>();
    mapped->mapping = mapping;
    mapped->fileMapping = mappingHandle;
    mapped->bytes = bytes;
    mapped->data = static_cast<const int16_t*>(mapping);
    mapped->samples = bytes / sizeof(int16_t);
#else
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        error = "Cannot open cached PCM file";
        return nullptr;
    }

    struct stat info {};
    if (fstat(fd, &info) != 0 || info.st_size < 4) {
        close(fd);
        error = "Cached PCM file is empty";
        return nullptr;
    }
    const size_t bytes = static_cast<size_t>(info.st_size);
    if (bytes % 4 != 0) {
        close(fd);
        error = "Cached PCM file is not stereo 16-bit";
        return nullptr;
    }

    void* mapping = mmap(nullptr, bytes, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapping == MAP_FAILED) {
        error = "Cannot map cached PCM file";
        return nullptr;
    }

    auto mapped = std::make_shared<MappedPcm>();
    mapped->mapping = mapping;
    mapped->bytes = bytes;
    mapped->data = static_cast<const int16_t*>(mapping);
    mapped->samples = bytes / sizeof(int16_t);
#endif

    const int64_t frames = static_cast<int64_t>(bytes / 4);
    auto track = std::make_shared<TrackData>();
    track->sampleRate = sampleRate;
    track->frames = frames;
    track->channels = 2;
    track->mapped = std::move(mapped);
    track->peaks = ComputePcm16Peaks(track->mapped->data, frames);
    return track;
}

}

MappedPcm::~MappedPcm() {
#ifdef _WIN32
    if (mapping != nullptr && bytes > 0) {
        UnmapViewOfFile(mapping);
    }
    if (fileMapping != nullptr) {
        CloseHandle(fileMapping);
    }
#else
    if (mapping != nullptr && bytes > 0) {
        munmap(mapping, bytes);
    }
#endif
}
