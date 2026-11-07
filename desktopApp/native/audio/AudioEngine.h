#pragma once

#include <alsa/asoundlib.h>

#include <atomic>
#include <string>
#include <thread>
#include <vector>

#include "Deck.h"
#include "DeckPair.h"
#include "FxRack.h"
#include "EqualPower.h"
#include "SpectrumAnalyzer.h"

class AudioEngine {
public:
    ~AudioEngine();

    bool Start(std::string& error);
    void Stop();

    Deck& GetDeck(int index);
    Deck& GetIncomingDeck(int index);
    FxRack& GetFxRack(int index);
    bool StartCrossfade(int index, float seconds);
    bool IsCrossfading(int index) const;
    float CrossfadeProgress(int index) const;
    int64_t CrossfadeDurationMs(int index) const;
    int64_t CrossfadePositionMs(int index) const;
    bool ConsumeCrossfadeComplete(int index);
    void CancelCrossfade(int index);
    bool ConsumeEnded(int index);
    void SetCrossfader(float value);
    void SetMasterGain(float value);
    int SampleRate() const;
    int LatencyMs() const;
    int BufferFrames() const;
    void GetSpectrum(float* out, int count) const;
    std::string TakeError() { return {}; }

private:
    void RenderLoop();

    snd_pcm_t* pcm_ = nullptr;
    std::thread thread_;
    std::atomic<bool> running_{false};
    DeckPair deckPairs_[2];
    FxRack fxRacks_[3];
    std::vector<float> deckScratch_[2];
    scratch::SpectrumAnalyzer spectrum_;
    std::atomic<float> crossfader_{0.5f};
    std::atomic<float> masterGain_{1.0f};
    std::atomic<int> sampleRate_{48000};
    std::atomic<int> latencyMs_{0};
    std::atomic<int> bufferFrames_{0};

    float crossfaderSmoothed_ = 0.5f;
    float masterSmoothed_ = 1.0f;
    uint32_t callbackCount_ = 0;
};
