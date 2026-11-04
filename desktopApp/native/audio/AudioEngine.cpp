#include "AudioEngine.h"
#include "SoftClip.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

AudioEngine::~AudioEngine() {
    Stop();
}

bool AudioEngine::Start(std::string& error) {
    if (pcm_ != nullptr) {
        return true;
    }

    int result = snd_pcm_open(&pcm_, "default", SND_PCM_STREAM_PLAYBACK, 0);
    if (result < 0) {
        error = std::string("ALSA open failed: ") + snd_strerror(result);
        pcm_ = nullptr;
        return false;
    }

    result = snd_pcm_set_params(
        pcm_,
        SND_PCM_FORMAT_S16_LE,
        SND_PCM_ACCESS_RW_INTERLEAVED,
        2,
        48000,
        1,
        20000);
    if (result < 0) {
        error = std::string("ALSA params failed: ") + snd_strerror(result);
        snd_pcm_close(pcm_);
        pcm_ = nullptr;
        return false;
    }

    snd_pcm_uframes_t bufferSize = 0;
    snd_pcm_uframes_t periodSize = 0;
    snd_pcm_get_params(pcm_, &bufferSize, &periodSize);
    if (periodSize == 0) {
        periodSize = 512;
    }
    bufferFrames_.store(static_cast<int>(periodSize), std::memory_order_relaxed);
    latencyMs_.store(
        static_cast<int>(bufferSize * 1000 / 48000),
        std::memory_order_relaxed);
    sampleRate_.store(48000, std::memory_order_relaxed);

    decks_[0].PrepareForSampleRate(48000);
    decks_[1].PrepareForSampleRate(48000);
    spectrum_.Init(48000);
    for (auto& scratchBuffer : deckScratch_) {
        scratchBuffer.assign(16384 * 2, 0.0f);
    }
    for (auto& rack : fxRacks_) {
        rack.Init(48000, 16384);
    }

    result = snd_pcm_prepare(pcm_);
    if (result < 0) {
        error = std::string("ALSA prepare failed: ") + snd_strerror(result);
        snd_pcm_close(pcm_);
        pcm_ = nullptr;
        return false;
    }

    running_.store(true, std::memory_order_relaxed);
    thread_ = std::thread(&AudioEngine::RenderLoop, this);
    return true;
}

void AudioEngine::Stop() {
    if (pcm_ == nullptr) {
        return;
    }
    running_.store(false, std::memory_order_relaxed);
    if (thread_.joinable()) {
        thread_.join();
    }
    snd_pcm_drain(pcm_);
    snd_pcm_close(pcm_);
    pcm_ = nullptr;
}

void AudioEngine::RenderLoop() {
    const int frames = std::min(bufferFrames_.load(std::memory_order_relaxed), 16384);
    std::vector<float> mix(static_cast<size_t>(frames) * 2);
    std::vector<int16_t> output(static_cast<size_t>(frames) * 2);

    while (running_.load(std::memory_order_relaxed)) {
        const float targetCrossfader = crossfader_.load(std::memory_order_relaxed);
        crossfaderSmoothed_ += (targetCrossfader - crossfaderSmoothed_) * 0.25f;
        const StereoGains gains = EqualPowerGains(crossfaderSmoothed_);

        for (int deck = 0; deck < 2; ++deck) {
            std::fill(deckScratch_[deck].begin(), deckScratch_[deck].begin() + frames * 2, 0.0f);
        }
        decks_[0].Render(deckScratch_[0].data(), frames, gains.a, 48000);
        decks_[1].Render(deckScratch_[1].data(), frames, gains.b, 48000);
        fxRacks_[0].Process(deckScratch_[0].data(), frames);
        fxRacks_[1].Process(deckScratch_[1].data(), frames);

        std::fill(mix.begin(), mix.end(), 0.0f);
        for (int i = 0; i < frames * 2; ++i) {
            mix[static_cast<size_t>(i)] = deckScratch_[0][i] + deckScratch_[1][i];
        }
        fxRacks_[2].Process(mix.data(), frames);

        const float targetMaster = masterGain_.load(std::memory_order_relaxed);
        masterSmoothed_ += (targetMaster - masterSmoothed_) * 0.25f;

        for (int i = 0; i < frames * 2; ++i) {
            const float sample = scratch::SoftClip(mix[i] * masterSmoothed_);
            output[static_cast<size_t>(i)] =
                static_cast<int16_t>(sample * 32767.0f);
        }

        snd_pcm_sframes_t written = snd_pcm_writei(pcm_, output.data(), frames);
        if (written < 0) {
            written = snd_pcm_recover(pcm_, static_cast<int>(written), 0);
            if (written < 0) {
                break;
            }
        }

        spectrum_.Process(mix.data(), frames);

        if ((++callbackCount_ & 0x7F) == 0) {
            snd_pcm_sframes_t delay = 0;
            if (snd_pcm_delay(pcm_, &delay) == 0 && delay > 0) {
                latencyMs_.store(
                    static_cast<int>(delay * 1000 / 48000),
                    std::memory_order_relaxed);
            }
        }
    }
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
