#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "SpectrumAnalyzer.h"
#include "Svf.h"
#include "TrackData.h"
#include "effects/Biquad.h"
#include "audio/TimeStretcher.h"

class Deck {
public:
    static constexpr int kEqNodeCount = 8;

    Deck();
    void SetTrack(std::shared_ptr<TrackData> track);
    void PrepareForSampleRate(int outputSampleRate);
    std::shared_ptr<TrackData> GetTrackForUi() const;

    void SetPlaying(bool playing);
    bool IsPlaying() const;
    void TogglePlaying();

    void BeginScratch();
    void SetScratchRate(float rate);
    void EndScratch();

    void SetGain(float gain);
    void SetFilterMode(int mode);
    void SetFilterCutoff(float normalizedCutoff);
    void SetEqNode(int index, float frequencyNorm, float gainDb, float q);
    void SetEqBypassed(bool bypassed);
    void GetEqResponse(float* out, int count) const;
    void GetEqSpectra(float* pre, float* post, int count) const;
    void SetTempo(float ratio);
    void SetKeyLock(bool enabled);
    void SetLoopEnabled(bool enabled);
    void SetLoopPoints(float inFraction, float outFraction);
    void ClearLoop();
    void SeekFraction(float fraction);

    void Render(float* interleavedStereoOut, int32_t frames, float mixGain, int32_t outputSampleRate);

    int64_t PositionFrames() const;
    int64_t DurationFrames() const;
    double PositionSeconds() const;
    double DurationSeconds() const;
    int TrackSampleRate() const;
    bool ConsumeEnded();

private:
    static constexpr int kStretchInputFrames = 8192;
    static constexpr int kStretchOutputFrames = 8192;
    static constexpr int kStretchFifoFrames = 32768;

    void AdoptPendingTrack();
    void RenderRaw(float* interleavedStereoOut, int32_t frames, float mixGain,
                   int32_t outputSampleRate);
    void RenderStretched(float* interleavedStereoOut, int32_t frames, float mixGain,
                         int32_t outputSampleRate);
    void PrepareStretchers(int outputSampleRate);
    void ResetStretch(int64_t sourceFrame);
    void PushStretchFrames(const float* left, const float* right, int frames);
    bool UpdateFilters(int outputSampleRate);
    void UpdateEq(int outputSampleRate);
    float ApplyEqLeft(float sample);
    float ApplyEqRight(float sample);

    mutable std::mutex pendingMutex_;
    std::shared_ptr<TrackData> pending_;
    std::shared_ptr<TrackData> uiTrack_;
    std::atomic<bool> hasPending_{false};

    std::shared_ptr<TrackData> current_;

    std::atomic<int64_t> positionFrames_{0};
    std::atomic<int64_t> durationFrames_{0};
    std::atomic<int> trackSampleRate_{48000};
    std::atomic<bool> playing_{false};
    std::atomic<bool> scratchActive_{false};
    std::atomic<float> scratchRateTarget_{0.0f};
    std::atomic<float> gain_{1.0f};
    std::atomic<int> filterMode_{0};
    std::atomic<float> filterCutoff_{0.5f};
    std::atomic<float> eqFrequency_[kEqNodeCount];
    std::atomic<float> eqGain_[kEqNodeCount];
    std::atomic<float> eqQ_[kEqNodeCount];
    std::atomic<int> eqSampleRate_{48000};
    std::atomic<bool> eqBypassed_{false};
    std::atomic<float> tempo_{1.0f};
    std::atomic<bool> keyLock_{false};
    std::atomic<bool> loopEnabled_{false};
    std::atomic<int64_t> loopStartFrames_{0};
    std::atomic<int64_t> loopEndFrames_{0};
    std::atomic<bool> resetFilter_{false};
    std::atomic<bool> startPlayback_{false};
    std::atomic<bool> ended_{false};
    std::atomic<bool> resetStretch_{false};

    float rate_ = 0.0f;
    float envelope_ = 0.0f;
    float gainSmoothed_ = 1.0f;
    float filterCutoffSmoothed_ = 0.5f;
    float filterWet_ = 0.0f;
    Svf svfLeft_;
    Svf svfRight_;
    yawn::effects::Biquad eqLeft_[kEqNodeCount];
    yawn::effects::Biquad eqRight_[kEqNodeCount];
    bool eqNodeActive_[kEqNodeCount] = {};
    scratch::SpectrumAnalyzer eqPreSpectrum_;
    scratch::SpectrumAnalyzer eqPostSpectrum_;

    std::unique_ptr<yawn::audio::TimeStretcher> stretchLeft_;
    std::unique_ptr<yawn::audio::TimeStretcher> stretchRight_;
    std::vector<float> stretchInputLeft_;
    std::vector<float> stretchInputRight_;
    std::vector<float> stretchOutputLeft_;
    std::vector<float> stretchOutputRight_;
    std::vector<float> stretchFifo_;
    int stretchSampleRate_ = 0;
    double stretchSourcePos_ = 0.0;
    int stretchFifoRead_ = 0;
    int stretchFifoWrite_ = 0;
    int stretchFifoCount_ = 0;
    bool stretchActive_ = false;
    bool stretchLoopActive_ = false;
};
