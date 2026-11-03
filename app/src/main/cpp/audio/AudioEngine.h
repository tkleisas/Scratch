#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "Deck.h"
#include "FxRack.h"
#include "EqualPower.h"
#include "SpectrumAnalyzer.h"

class AudioEngine : public oboe::AudioStreamCallback {
public:
    ~AudioEngine() override;

    bool Start(std::string& error);
    void Stop();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

    Deck& GetDeck(int index);
    FxRack& GetFxRack(int index);
    void SetCrossfader(float value);
    void SetMasterGain(float value);

    int SampleRate() const;
    int LatencyMs() const;
    int BufferFrames() const;
    void GetSpectrum(float* out, int count) const;

private:
    bool OpenStream(std::string& error, oboe::SharingMode sharingMode);

    std::shared_ptr<oboe::AudioStream> stream_;

    Deck decks_[2];
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
