#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Deck.h"
#include "DeckPair.h"
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
    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

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
    std::string TakeError();

private:
    void ReportError(const std::string& error);
    bool OpenStream(std::string& error, oboe::SharingMode sharingMode);

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;
    std::mutex errorMutex_;
    std::string pendingError_;

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
