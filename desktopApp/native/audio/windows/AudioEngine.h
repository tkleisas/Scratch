#pragma once

#include <portaudio.h>

#include <atomic>
#include <string>
#include <vector>

#include "Deck.h"
#include "DeckPair.h"
#include "EqualPower.h"
#include "FxRack.h"
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
    static int PaCallback(const void* input, void* output, unsigned long frameCount,
                          const PaStreamCallbackTimeInfo* timeInfo,
                          PaStreamCallbackFlags statusFlags, void* userData);
    void Render(float* output, int frames);
    void ReleaseStream();

    PaStream* stream_ = nullptr;
    bool paInitialized_ = false;

    DeckPair deckPairs_[2];
    FxRack fxRacks_[3];
    std::vector<float> deckScratch_[2];
    scratch::SpectrumAnalyzer spectrum_;

    std::atomic<float> crossfader_{0.5f};
    std::atomic<float> masterGain_{1.0f};
    std::atomic<int> sampleRate_{48000};
    std::atomic<int> latencyMs_{0};
    std::atomic<int> bufferFrames_{256};

    float crossfaderSmoothed_ = 0.5f;
    float masterSmoothed_ = 1.0f;
};
