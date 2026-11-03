#include "AudioEngine.h"

#include <pa_win_wasapi.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {
constexpr int kFramesPerBuffer = 256;
constexpr int kMaxFrames = 16384;
constexpr double kSampleRate = 48000.0;

bool EnvEnabled(const char* name) {
    const char* value = std::getenv(name);
    if (!value) return false;
    const std::string text(value);
    return text == "1" || text == "true" || text == "TRUE" || text == "yes" ||
           text == "on" || text == "ON";
}

bool EnvEquals(const char* name, const char* expected) {
    const char* value = std::getenv(name);
    return value && std::string(value) == expected;
}
} // namespace

AudioEngine::~AudioEngine() {
    Stop();
}

void AudioEngine::ReleaseStream() {
    if (stream_) {
        Pa_CloseStream(stream_);
        stream_ = nullptr;
    }
    if (paInitialized_) {
        Pa_Terminate();
        paInitialized_ = false;
    }
}

bool AudioEngine::Start(std::string& error) {
    if (stream_) return true;
    error.clear();

    PaError result = Pa_Initialize();
    if (result != paNoError) {
        error = std::string("PortAudio init failed: ") + Pa_GetErrorText(result);
        return false;
    }
    paInitialized_ = true;

    // WASAPI is the default Windows backend. If an ASIO-enabled PortAudio
    // build is present, SCRATCH_AUDIO_HOST=asio selects the default ASIO
    // device. WASAPI exclusive mode is attempted first for low latency.
    PaHostApiTypeId hostType = paWASAPI;
#if defined(SCRATCH_ASIO)
    // The ASIO-enabled build prefers ASIO, then falls back to WASAPI below
    // when no ASIO host/device is available.
    hostType = paASIO;
#endif
    if (EnvEquals("SCRATCH_AUDIO_HOST", "wasapi")) {
        hostType = paWASAPI;
    } else if (EnvEquals("SCRATCH_AUDIO_HOST", "asio")) {
        hostType = paASIO;
    }
    PaHostApiIndex hostIndex = Pa_HostApiTypeIdToHostApiIndex(hostType);
    if (hostIndex < 0 && hostType == paASIO) {
        hostType = paWASAPI;
        hostIndex = Pa_HostApiTypeIdToHostApiIndex(hostType);
    }
    if (hostIndex < 0) {
        error = "PortAudio has no usable Windows audio host API";
        ReleaseStream();
        return false;
    }

    const PaHostApiInfo* hostInfo = Pa_GetHostApiInfo(hostIndex);
    PaDeviceIndex device = hostInfo ? hostInfo->defaultOutputDevice : paNoDevice;
    if (device == paNoDevice) {
        device = Pa_GetDefaultOutputDevice();
    }
    if (device == paNoDevice) {
        error = "No Windows audio output device found";
        ReleaseStream();
        return false;
    }

    const PaDeviceInfo* deviceInfo = Pa_GetDeviceInfo(device);
    PaStreamParameters output{};
    output.device = device;
    output.channelCount = 2;
    output.sampleFormat = paFloat32;
    output.suggestedLatency = deviceInfo && deviceInfo->defaultLowOutputLatency > 0.0
        ? deviceInfo->defaultLowOutputLatency
        : 0.01;
    output.hostApiSpecificStreamInfo = nullptr;

    PaWasapiStreamInfo wasapiInfo{};
    wasapiInfo.size = sizeof(PaWasapiStreamInfo);
    wasapiInfo.hostApiType = paWASAPI;
    wasapiInfo.version = 1;

    bool exclusive = false;
    if (hostType == paWASAPI) {
        wasapiInfo.flags = paWinWasapiExclusive;
        output.hostApiSpecificStreamInfo = &wasapiInfo;
        result = Pa_OpenStream(&stream_, nullptr, &output, kSampleRate,
                               kFramesPerBuffer, paClipOff,
                               &AudioEngine::PaCallback, this);
        if (result == paNoError) {
            exclusive = true;
        } else {
            // Exclusive mode may be unavailable (device in use, unsupported
            // format). Retry in shared mode with automatic conversion.
            if (stream_) {
                Pa_CloseStream(stream_);
                stream_ = nullptr;
            }
            wasapiInfo.flags = paWinWasapiAutoConvert;
            output.hostApiSpecificStreamInfo = &wasapiInfo;
            result = Pa_OpenStream(&stream_, nullptr, &output, kSampleRate,
                                   kFramesPerBuffer, paClipOff,
                                   &AudioEngine::PaCallback, this);
        }
    } else {
        output.hostApiSpecificStreamInfo = nullptr;
        result = Pa_OpenStream(&stream_, nullptr, &output, kSampleRate,
                               kFramesPerBuffer, paClipOff,
                               &AudioEngine::PaCallback, this);
    }

    if (result != paNoError) {
        // Last resort: let PortAudio choose the default output device.
        if (stream_) {
            Pa_CloseStream(stream_);
            stream_ = nullptr;
        }
        PaDeviceIndex fallback = Pa_GetDefaultOutputDevice();
        if (fallback != paNoDevice && fallback != device) {
            device = fallback;
            output.device = fallback;
            output.hostApiSpecificStreamInfo = nullptr;
            result = Pa_OpenStream(&stream_, nullptr, &output, kSampleRate,
                                   kFramesPerBuffer, paClipOff,
                                   &AudioEngine::PaCallback, this);
        }
    }

    if (result != paNoError || !stream_) {
        error = std::string("PortAudio open failed: ") + Pa_GetErrorText(result);
        ReleaseStream();
        return false;
    }

    int actualRate = static_cast<int>(std::llround(kSampleRate));
    if (const PaStreamInfo* streamInfo = Pa_GetStreamInfo(stream_)) {
        if (streamInfo->sampleRate > 0.0) {
            actualRate = static_cast<int>(std::llround(streamInfo->sampleRate));
        }
    }
    if (actualRate < 8000 || actualRate > 192000) {
        error = "Unsupported Windows audio sample rate";
        ReleaseStream();
        return false;
    }

    sampleRate_.store(actualRate, std::memory_order_relaxed);
    bufferFrames_.store(kFramesPerBuffer, std::memory_order_relaxed);
    for (auto& scratchBuffer : deckScratch_) {
        scratchBuffer.assign(kMaxFrames * 2, 0.0f);
    }
    decks_[0].PrepareForSampleRate(actualRate);
    decks_[1].PrepareForSampleRate(actualRate);
    spectrum_.Init(actualRate);
    for (auto& rack : fxRacks_) {
        rack.Init(actualRate, kMaxFrames);
    }

    result = Pa_StartStream(stream_);
    if (result != paNoError) {
        error = std::string("PortAudio start failed: ") + Pa_GetErrorText(result);
        ReleaseStream();
        return false;
    }

    if (const PaStreamInfo* streamInfo = Pa_GetStreamInfo(stream_)) {
        const int latency = static_cast<int>(
            std::llround(streamInfo->outputLatency * 1000.0));
        latencyMs_.store(std::max(0, latency), std::memory_order_relaxed);
    }
    if (!exclusive && hostType == paWASAPI) {
        // Keep this visible in the error string only when asked for debug.
        if (EnvEnabled("SCRATCH_AUDIO_DEBUG")) {
            error = "WASAPI shared mode (exclusive unavailable)";
        }
    }
    return true;
}

void AudioEngine::Stop() {
    if (stream_) {
        Pa_StopStream(stream_);
    }
    ReleaseStream();
}

int AudioEngine::PaCallback(const void* input, void* output, unsigned long frameCount,
                            const PaStreamCallbackTimeInfo* timeInfo,
                            PaStreamCallbackFlags statusFlags, void* userData) {
    (void)input;
    (void)statusFlags;
    auto* engine = static_cast<AudioEngine*>(userData);
    if (!engine || !output) return paContinue;

    auto* out = static_cast<float*>(output);
    const int frames = static_cast<int>(
        std::min<unsigned long>(frameCount, static_cast<unsigned long>(kMaxFrames)));
    engine->Render(out, frames);
    if (static_cast<unsigned long>(frames) < frameCount) {
        std::memset(out + frames * 2, 0,
                    sizeof(float) * (frameCount - frames) * 2);
    }

    if (timeInfo) {
        const double latency = timeInfo->outputBufferDacTime - timeInfo->currentTime;
        if (latency >= 0.0 && latency < 10.0) {
            engine->latencyMs_.store(
                static_cast<int>(std::llround(latency * 1000.0)),
                std::memory_order_relaxed);
        }
    }
    return paContinue;
}

void AudioEngine::Render(float* output, int frames) {
    if (frames <= 0) return;
    std::memset(output, 0, sizeof(float) * static_cast<size_t>(frames) * 2);

    const float targetCrossfader = crossfader_.load(std::memory_order_relaxed);
    crossfaderSmoothed_ += (targetCrossfader - crossfaderSmoothed_) * 0.25f;
    const StereoGains gains = EqualPowerGains(crossfaderSmoothed_);

    for (int deck = 0; deck < 2; ++deck) {
        std::fill(deckScratch_[deck].begin(),
                  deckScratch_[deck].begin() + frames * 2, 0.0f);
    }
    decks_[0].Render(deckScratch_[0].data(), frames, gains.a,
                     sampleRate_.load(std::memory_order_relaxed));
    decks_[1].Render(deckScratch_[1].data(), frames, gains.b,
                     sampleRate_.load(std::memory_order_relaxed));
    fxRacks_[0].Process(deckScratch_[0].data(), frames);
    fxRacks_[1].Process(deckScratch_[1].data(), frames);

    for (int i = 0; i < frames * 2; ++i) {
        output[i] = deckScratch_[0][i] + deckScratch_[1][i];
    }
    fxRacks_[2].Process(output, frames);

    const float targetMaster = masterGain_.load(std::memory_order_relaxed);
    masterSmoothed_ += (targetMaster - masterSmoothed_) * 0.25f;
    for (int i = 0; i < frames * 2; ++i) {
        output[i] = std::clamp(output[i] * masterSmoothed_, -1.0f, 1.0f);
    }
    spectrum_.Process(output, frames);
}

Deck& AudioEngine::GetDeck(int index) {
    return decks_[index == 0 ? 0 : 1];
}

FxRack& AudioEngine::GetFxRack(int index) {
    return fxRacks_[index < 0 ? 0 : (index > 2 ? 2 : index)];
}

void AudioEngine::SetCrossfader(float value) {
    crossfader_.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AudioEngine::SetMasterGain(float value) {
    masterGain_.store(std::clamp(value, 0.0f, 2.0f), std::memory_order_relaxed);
}

int AudioEngine::SampleRate() const {
    return sampleRate_.load(std::memory_order_relaxed);
}

int AudioEngine::LatencyMs() const {
    return latencyMs_.load(std::memory_order_relaxed);
}

int AudioEngine::BufferFrames() const {
    return bufferFrames_.load(std::memory_order_relaxed);
}

void AudioEngine::GetSpectrum(float* out, int count) const {
    spectrum_.GetBands(out, count);
}
