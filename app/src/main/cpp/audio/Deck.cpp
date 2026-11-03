#include "Deck.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstring>
#include <vector>

namespace {

constexpr float kEqMinHz = 30.0f;
constexpr float kEqMaxHz = 18000.0f;

float EqNormToHz(float normalized) {
    return kEqMinHz * std::pow(kEqMaxHz / kEqMinHz, std::clamp(normalized, 0.0f, 1.0f));
}

struct EqCoefficients {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
};

EqCoefficients EqNodeCoefficients(int index, double sampleRate, double frequency,
                                  double gainDb, double q) {
    const double w0 = 2.0 * 3.14159265358979323846 * frequency / sampleRate;
    const double cosw0 = std::cos(w0);
    const double sinw0 = std::sin(w0);
    const double alpha = sinw0 / (2.0 * std::max(q, 0.1));
    const double A = std::pow(10.0, gainDb / 40.0);
    const double sqrtA = std::sqrt(A);

    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a0 = 1.0;
    double a1 = 0.0;
    double a2 = 0.0;

    if (index == 0) {
        b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha);
        b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
        b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha);
        a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
        a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
        a2 = (A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha;
    } else if (index == 7) {
        b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha);
        b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0);
        b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha);
        a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
        a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw0);
        a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha;
    } else {
        b0 = 1.0 + alpha * A;
        b1 = -2.0 * cosw0;
        b2 = 1.0 - alpha * A;
        a0 = 1.0 + alpha / A;
        a1 = -2.0 * cosw0;
        a2 = 1.0 - alpha / A;
    }

    const double invA0 = 1.0 / a0;
    return {b0 * invA0, b1 * invA0, b2 * invA0, a1 * invA0, a2 * invA0};
}

double EqMagnitude(const EqCoefficients& coefficients, double frequency, double sampleRate) {
    const double w = 2.0 * 3.14159265358979323846 * frequency / sampleRate;
    const std::complex<double> z1(std::cos(-w), std::sin(-w));
    const std::complex<double> numerator =
        coefficients.b0 + coefficients.b1 * z1 + coefficients.b2 * z1 * z1;
    const std::complex<double> denominator =
        1.0 + coefficients.a1 * z1 + coefficients.a2 * z1 * z1;
    return std::abs(numerator / denominator);
}

float TrackSample(const TrackData& track, int64_t frame, int channel) {
    const size_t index = static_cast<size_t>(frame) * 2 + channel;
    if (!track.samples.empty()) {
        return track.samples[index];
    }
    if (track.mapped) {
        return static_cast<float>(track.mapped->data[index]) / 32768.0f;
    }
    return static_cast<float>(track.pcm16[index]) / 32768.0f;
}

float CubicSample(const TrackData& track, int64_t frames, double position, int channel) {
    int64_t i1 = static_cast<int64_t>(std::floor(position));
    if (i1 < 0) {
        i1 = 0;
    }
    if (i1 >= frames) {
        i1 = frames - 1;
    }
    const double t = position - static_cast<double>(i1);
    const int64_t i0 = i1 > 0 ? i1 - 1 : i1;
    const int64_t i2 = i1 + 1 < frames ? i1 + 1 : i1;
    const int64_t i3 = i1 + 2 < frames ? i1 + 2 : i1;

    const float p0 = TrackSample(track, i0, channel);
    const float p1 = TrackSample(track, i1, channel);
    const float p2 = TrackSample(track, i2, channel);
    const float p3 = TrackSample(track, i3, channel);

    const float a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
    const float b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    const float c = -0.5f * p0 + 0.5f * p2;
    return ((a * static_cast<float>(t) + b) * static_cast<float>(t) + c) *
               static_cast<float>(t) +
           p1;
}

}

Deck::Deck() {
    constexpr float kDefaultHz[Deck::kEqNodeCount] = {
        80.0f, 160.0f, 320.0f, 640.0f, 1250.0f, 2500.0f, 5000.0f, 10000.0f,
    };
    const float range = std::log(kEqMaxHz / kEqMinHz);
    for (int i = 0; i < kEqNodeCount; ++i) {
        eqFrequency_[i].store(std::log(kDefaultHz[i] / kEqMinHz) / range,
                              std::memory_order_relaxed);
        eqGain_[i].store(0.0f, std::memory_order_relaxed);
        eqQ_[i].store((i == 0 || i == 7) ? 0.707f : 1.0f, std::memory_order_relaxed);
    }
}

void Deck::SetTrack(std::shared_ptr<TrackData> track) {
    std::lock_guard<std::mutex> lock(pendingMutex_);
    pending_ = std::move(track);
    uiTrack_ = pending_;
    hasPending_.store(true, std::memory_order_release);
    playing_.store(false, std::memory_order_relaxed);
    scratchActive_.store(false, std::memory_order_relaxed);
    scratchRateTarget_.store(0.0f, std::memory_order_relaxed);
    startPlayback_.store(false, std::memory_order_relaxed);
    ended_.store(false, std::memory_order_relaxed);
    resetStretch_.store(true, std::memory_order_relaxed);
    loopEnabled_.store(false, std::memory_order_relaxed);
    loopStartFrames_.store(0, std::memory_order_relaxed);
    loopEndFrames_.store(0, std::memory_order_relaxed);
    positionFrames_.store(0, std::memory_order_relaxed);
    durationFrames_.store(pending_ ? pending_->frames : 0, std::memory_order_relaxed);
    trackSampleRate_.store(pending_ && pending_->sampleRate > 0 ? pending_->sampleRate : 48000,
                           std::memory_order_relaxed);
}

std::shared_ptr<TrackData> Deck::GetTrackForUi() const {
    std::lock_guard<std::mutex> lock(pendingMutex_);
    return uiTrack_;
}

void Deck::SetPlaying(bool playing) {
    if (playing) {
        const int64_t duration = durationFrames_.load(std::memory_order_relaxed);
        if (duration > 0 && positionFrames_.load(std::memory_order_relaxed) >= duration - 1) {
            positionFrames_.store(0, std::memory_order_relaxed);
            resetFilter_.store(true, std::memory_order_relaxed);
        }
        startPlayback_.store(true, std::memory_order_relaxed);
        ended_.store(false, std::memory_order_relaxed);
    }
    playing_.store(playing, std::memory_order_relaxed);
}

bool Deck::IsPlaying() const {
    return playing_.load(std::memory_order_relaxed);
}

void Deck::TogglePlaying() {
    SetPlaying(!IsPlaying());
}

void Deck::BeginScratch() {
    scratchActive_.store(true, std::memory_order_relaxed);
}

void Deck::SetScratchRate(float rate) {
    scratchRateTarget_.store(std::clamp(rate, -8.0f, 8.0f), std::memory_order_relaxed);
}

void Deck::EndScratch() {
    scratchActive_.store(false, std::memory_order_relaxed);
    scratchRateTarget_.store(0.0f, std::memory_order_relaxed);
}

void Deck::SetGain(float gain) {
    gain_.store(std::clamp(gain, 0.0f, 2.0f), std::memory_order_relaxed);
}

void Deck::SetFilterMode(int mode) {
    filterMode_.store(std::clamp(mode, 0, 2), std::memory_order_relaxed);
}

void Deck::SetFilterCutoff(float normalizedCutoff) {
    filterCutoff_.store(std::clamp(normalizedCutoff, 0.0f, 1.0f), std::memory_order_relaxed);
}

void Deck::SetEqNode(int index, float frequencyNorm, float gainDb, float q) {
    if (index < 0 || index >= kEqNodeCount) {
        return;
    }
    eqFrequency_[index].store(std::clamp(frequencyNorm, 0.0f, 1.0f),
                             std::memory_order_relaxed);
    eqGain_[index].store(std::clamp(gainDb, -18.0f, 18.0f), std::memory_order_relaxed);
    eqQ_[index].store(std::clamp(q, 0.3f, 4.0f), std::memory_order_relaxed);
}

void Deck::SetLoopEnabled(bool enabled) {
    const bool valid = loopEndFrames_.load(std::memory_order_relaxed) >
                       loopStartFrames_.load(std::memory_order_relaxed);
    loopEnabled_.store(enabled && valid, std::memory_order_relaxed);
}

void Deck::SetLoopPoints(float inFraction, float outFraction) {
    const int64_t duration = durationFrames_.load(std::memory_order_relaxed);
    if (duration < 2) {
        return;
    }

    int64_t start = static_cast<int64_t>(
        std::llround(std::clamp(inFraction, 0.0f, 1.0f) * (duration - 1)));
    int64_t end = static_cast<int64_t>(
        std::llround(std::clamp(outFraction, 0.0f, 1.0f) * (duration - 1)));
    start = std::clamp<int64_t>(start, 0, duration - 1);
    end = std::clamp<int64_t>(end, 0, duration - 1);

    if (end <= start) {
        const int rate = trackSampleRate_.load(std::memory_order_relaxed);
        const int64_t fallbackLength = rate > 0 ? static_cast<int64_t>(rate) * 4 : 192000;
        end = std::min<int64_t>(duration - 1, start + fallbackLength);
        if (end <= start) {
            start = std::max<int64_t>(0, end - fallbackLength);
        }
    }
    if (end <= start) {
        return;
    }

    loopStartFrames_.store(start, std::memory_order_relaxed);
    loopEndFrames_.store(end, std::memory_order_relaxed);
}

void Deck::ClearLoop() {
    loopEnabled_.store(false, std::memory_order_relaxed);
    loopStartFrames_.store(0, std::memory_order_relaxed);
    loopEndFrames_.store(0, std::memory_order_relaxed);
}

void Deck::SetTempo(float ratio) {
    tempo_.store(std::clamp(ratio, 0.5f, 2.0f), std::memory_order_relaxed);
}

void Deck::SetKeyLock(bool enabled) {
    keyLock_.store(enabled, std::memory_order_relaxed);
}

void Deck::SeekFraction(float fraction) {
    const int64_t duration = durationFrames_.load(std::memory_order_relaxed);
    const float clamped = std::clamp(fraction, 0.0f, 1.0f);
    const int64_t target =
        duration > 0 ? static_cast<int64_t>(std::llround(clamped * (duration - 1))) : 0;
    positionFrames_.store(target, std::memory_order_relaxed);
    resetFilter_.store(true, std::memory_order_relaxed);
    resetStretch_.store(true, std::memory_order_relaxed);
    ended_.store(false, std::memory_order_relaxed);
}

void Deck::AdoptPendingTrack() {
    if (!hasPending_.load(std::memory_order_acquire)) {
        return;
    }
    std::unique_lock<std::mutex> lock(pendingMutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
        return;
    }
    if (!pending_) {
        hasPending_.store(false, std::memory_order_release);
        return;
    }
    current_ = std::move(pending_);
    pending_.reset();
    hasPending_.store(false, std::memory_order_release);
    positionFrames_.store(0, std::memory_order_relaxed);
    rate_ = 0.0f;
    envelope_ = 0.0f;
    svfLeft_.Reset();
    svfRight_.Reset();
    for (int i = 0; i < kEqNodeCount; ++i) {
        eqLeft_[i].reset();
        eqRight_[i].reset();
    }
    stretchActive_ = false;
    ResetStretch(0);
}

void Deck::PrepareForSampleRate(int outputSampleRate) {
    PrepareStretchers(outputSampleRate);
    eqPreSpectrum_.Init(outputSampleRate);
    eqPostSpectrum_.Init(outputSampleRate);
}

void Deck::PrepareStretchers(int outputSampleRate) {
    if (outputSampleRate <= 0) {
        return;
    }
    if (!stretchLeft_) {
        stretchLeft_ = std::make_unique<yawn::audio::TimeStretcher>();
        stretchRight_ = std::make_unique<yawn::audio::TimeStretcher>();
        stretchInputLeft_.assign(kStretchInputFrames, 0.0f);
        stretchInputRight_.assign(kStretchInputFrames, 0.0f);
        stretchOutputLeft_.assign(kStretchOutputFrames, 0.0f);
        stretchOutputRight_.assign(kStretchOutputFrames, 0.0f);
        stretchFifo_.assign(static_cast<size_t>(kStretchFifoFrames) * 2, 0.0f);
    }
    if (stretchSampleRate_ == outputSampleRate) {
        return;
    }

    stretchLeft_->preallocate(outputSampleRate, kStretchOutputFrames);
    stretchLeft_->init(outputSampleRate, kStretchOutputFrames,
                       yawn::audio::TimeStretcher::Algorithm::WSOLA);
    stretchRight_->preallocate(outputSampleRate, kStretchOutputFrames);
    stretchRight_->init(outputSampleRate, kStretchOutputFrames,
                        yawn::audio::TimeStretcher::Algorithm::WSOLA);
    stretchSampleRate_ = outputSampleRate;
    stretchActive_ = false;
    ResetStretch(positionFrames_.load(std::memory_order_relaxed));
}

void Deck::ResetStretch(int64_t sourceFrame) {
    stretchFifoRead_ = 0;
    stretchFifoWrite_ = 0;
    stretchFifoCount_ = 0;
    stretchSourcePos_ = static_cast<double>(std::clamp<int64_t>(
        sourceFrame, 0, std::max<int64_t>(durationFrames_.load() - 1, 0)));
    if (stretchLeft_) {
        stretchLeft_->reset();
        stretchRight_->reset();
    }
}

void Deck::PushStretchFrames(const float* left, const float* right, int frames) {
    for (int i = 0; i < frames; ++i) {
        if (stretchFifoCount_ >= kStretchFifoFrames) {
            return;
        }
        stretchFifo_[static_cast<size_t>(stretchFifoWrite_) * 2] = left[i];
        stretchFifo_[static_cast<size_t>(stretchFifoWrite_) * 2 + 1] = right[i];
        stretchFifoWrite_ = (stretchFifoWrite_ + 1) % kStretchFifoFrames;
        ++stretchFifoCount_;
    }
}

bool Deck::UpdateFilters(int outputSampleRate) {
    const int mode = filterMode_.load(std::memory_order_relaxed);
    filterCutoffSmoothed_ +=
        (filterCutoff_.load(std::memory_order_relaxed) - filterCutoffSmoothed_) * 0.25f;
    const float normalized = std::clamp(filterCutoffSmoothed_, 0.0f, 1.0f);
    const float cutoff = std::clamp(
        20.0f * std::pow(1000.0f, normalized), 20.0f, outputSampleRate * 0.49f);
    const float q = 0.707f;
    svfLeft_.Set(cutoff, q, static_cast<float>(outputSampleRate));
    svfRight_.Set(cutoff, q, static_cast<float>(outputSampleRate));

    const float targetWet = mode == 0 ? 0.0f : 1.0f;
    filterWet_ += (targetWet - filterWet_) * 0.25f;
    return mode == 2;
}

void Deck::Render(float* interleavedStereoOut, int32_t frames, float mixGain,
                  int32_t outputSampleRate) {
    AdoptPendingTrack();
    if (current_ == nullptr || frames <= 0 || outputSampleRate <= 0) {
        envelope_ = 0.0f;
        return;
    }

    if (resetFilter_.exchange(false, std::memory_order_relaxed)) {
        svfLeft_.Reset();
        svfRight_.Reset();
        for (int i = 0; i < kEqNodeCount; ++i) {
            eqLeft_[i].reset();
            eqRight_[i].reset();
        }
    }
    if (resetStretch_.exchange(false, std::memory_order_relaxed)) {
        stretchActive_ = false;
        ResetStretch(positionFrames_.load(std::memory_order_relaxed));
    }
    if (startPlayback_.exchange(false, std::memory_order_relaxed)) {
        rate_ = 1.0f;
        stretchActive_ = false;
        ResetStretch(positionFrames_.load(std::memory_order_relaxed));
    }

    const bool scratch = scratchActive_.load(std::memory_order_relaxed);
    const bool playing = playing_.load(std::memory_order_relaxed);
    const bool useStretch = keyLock_.load(std::memory_order_relaxed) && playing && !scratch;

    if (useStretch) {
        if (!stretchActive_) {
            stretchActive_ = true;
            PrepareStretchers(outputSampleRate);
            ResetStretch(positionFrames_.load(std::memory_order_relaxed));
        }
        RenderStretched(interleavedStereoOut, frames, mixGain, outputSampleRate);
        return;
    }

    if (stretchActive_) {
        stretchActive_ = false;
        ResetStretch(positionFrames_.load(std::memory_order_relaxed));
    }
    RenderRaw(interleavedStereoOut, frames, mixGain, outputSampleRate);
}

void Deck::UpdateEq(int outputSampleRate) {
    eqSampleRate_.store(outputSampleRate, std::memory_order_relaxed);
    const double sampleRate = static_cast<double>(outputSampleRate);
    for (int i = 0; i < kEqNodeCount; ++i) {
        const float gain = eqGain_[i].load(std::memory_order_relaxed);
        const bool active = std::fabs(gain) > 0.001f;
        eqNodeActive_[i] = active;
        if (!active) {
            eqLeft_[i].reset();
            eqRight_[i].reset();
            continue;
        }

        const float frequency = EqNormToHz(
            eqFrequency_[i].load(std::memory_order_relaxed));
        const float q = eqQ_[i].load(std::memory_order_relaxed);
        const auto type = i == 0
            ? yawn::effects::Biquad::Type::LowShelf
            : (i == 7 ? yawn::effects::Biquad::Type::HighShelf
                      : yawn::effects::Biquad::Type::Peak);
        yawn::effects::Biquad::computeStereo(
            eqLeft_[i], eqRight_[i], type, sampleRate, frequency, gain, q);
    }
}

float Deck::ApplyEqLeft(float sample) {
    for (int i = 0; i < kEqNodeCount; ++i) {
        if (eqNodeActive_[i]) {
            sample = eqLeft_[i].process(sample);
        }
    }
    return sample;
}

float Deck::ApplyEqRight(float sample) {
    for (int i = 0; i < kEqNodeCount; ++i) {
        if (eqNodeActive_[i]) {
            sample = eqRight_[i].process(sample);
        }
    }
    return sample;
}

void Deck::GetEqResponse(float* out, int count) const {
    if (out == nullptr || count <= 0) {
        return;
    }
    const double sampleRate = eqSampleRate_.load(std::memory_order_relaxed) > 0
        ? static_cast<double>(eqSampleRate_.load(std::memory_order_relaxed))
        : 48000.0;

    for (int point = 0; point < count; ++point) {
        const float normalized = count > 1
            ? static_cast<float>(point) / (count - 1)
            : 0.0f;
        const double frequency = EqNormToHz(normalized);
        double magnitude = 1.0;

        for (int node = 0; node < kEqNodeCount; ++node) {
            const float gain = eqGain_[node].load(std::memory_order_relaxed);
            if (std::fabs(gain) < 0.001f) {
                continue;
            }
            const double nodeFrequency = EqNormToHz(
                eqFrequency_[node].load(std::memory_order_relaxed));
            const double q = eqQ_[node].load(std::memory_order_relaxed);
            const auto coefficients = EqNodeCoefficients(
                node, sampleRate, nodeFrequency, gain, q);
            magnitude *= EqMagnitude(coefficients, frequency, sampleRate);
        }

        const double db = 20.0 * std::log10(std::max(magnitude, 1e-6));
        out[point] = static_cast<float>(std::clamp(db, -24.0, 24.0));
    }
}

void Deck::RenderRaw(float* interleavedStereoOut, int32_t frames, float mixGain,
                     int32_t outputSampleRate) {
    const int64_t trackFrames = current_->frames;
    if (trackFrames <= 0 ||
        (current_->samples.empty() && current_->pcm16.empty() && !current_->mapped)) {
        return;
    }

    const int trackRate = current_->sampleRate > 0 ? current_->sampleRate : outputSampleRate;
    const double stepScale = static_cast<double>(trackRate) / outputSampleRate;

    const float envelopeCoefficient = 1.0f - std::exp(-1.0f / (0.004f * outputSampleRate));
    const float rateCoefficient = 1.0f - std::exp(-1.0f / (0.006f * outputSampleRate));
    const bool highPass = UpdateFilters(outputSampleRate);
    UpdateEq(outputSampleRate);

    gainSmoothed_ += (gain_.load(std::memory_order_relaxed) - gainSmoothed_) * 0.2f;

    double position = static_cast<double>(positionFrames_.load(std::memory_order_relaxed));
    const float outputGain = mixGain * gainSmoothed_;

    for (int32_t i = 0; i < frames; ++i) {
        const bool scratch = scratchActive_.load(std::memory_order_relaxed);
        const bool playing = playing_.load(std::memory_order_relaxed);
        const float targetRate = scratch
            ? scratchRateTarget_.load(std::memory_order_relaxed)
            : (playing ? tempo_.load(std::memory_order_relaxed) : 0.0f);
        const float targetEnvelope = playing ? 1.0f : 0.0f;

        rate_ += (targetRate - rate_) * rateCoefficient;
        envelope_ += (targetEnvelope - envelope_) * envelopeCoefficient;

        const bool loopActive = loopEnabled_.load(std::memory_order_relaxed);
        const int64_t loopStart = loopStartFrames_.load(std::memory_order_relaxed);
        const int64_t loopEnd = loopEndFrames_.load(std::memory_order_relaxed);

        if (position < 0.0) {
            position = 0.0;
        }
        if (position > static_cast<double>(trackFrames - 1)) {
            position = static_cast<double>(trackFrames - 1);
        }
        if (!loopActive && !scratch && position >= static_cast<double>(trackFrames - 1) &&
            rate_ > 0.0f) {
            playing_.store(false, std::memory_order_relaxed);
            position = static_cast<double>(trackFrames - 1);
            rate_ = 0.0f;
            ended_.store(true, std::memory_order_relaxed);
        }

        const bool audible = playing || scratch;
        const float sourceLeft = CubicSample(*current_, trackFrames, position, 0);
        const float sourceRight = CubicSample(*current_, trackFrames, position, 1);
        const float eqLeft = ApplyEqLeft(sourceLeft);
        const float eqRight = ApplyEqRight(sourceRight);
        if (audible) {
            eqPreSpectrum_.Push(sourceLeft, sourceRight);
            eqPostSpectrum_.Push(eqLeft, eqRight);
        } else {
            eqPreSpectrum_.Push(0.0f, 0.0f);
            eqPostSpectrum_.Push(0.0f, 0.0f);
        }

        const float wetLeft = highPass ? svfLeft_.ProcessHighPass(eqLeft)
                                       : svfLeft_.ProcessLowPass(eqLeft);
        const float wetRight = highPass ? svfRight_.ProcessHighPass(eqRight)
                                        : svfRight_.ProcessLowPass(eqRight);
        const float left = eqLeft * (1.0f - filterWet_) + wetLeft * filterWet_;
        const float right = eqRight * (1.0f - filterWet_) + wetRight * filterWet_;

        const float scratchGain = scratch ? std::min(1.0f, std::fabs(rate_)) : 1.0f;
        const float gain = envelope_ * outputGain * scratchGain;
        interleavedStereoOut[static_cast<size_t>(i) * 2] += left * gain;
        interleavedStereoOut[static_cast<size_t>(i) * 2 + 1] += right * gain;

        position += static_cast<double>(rate_) * stepScale;
        if (position < 0.0) {
            position = 0.0;
        }
        if (loopActive && loopEnd > loopStart) {
            if (rate_ > 0.0f && position >= static_cast<double>(loopEnd)) {
                position = static_cast<double>(loopStart);
            } else if (rate_ < 0.0f && position < static_cast<double>(loopStart)) {
                position = static_cast<double>(loopEnd - 1);
            }
        }
    }

    positionFrames_.store(static_cast<int64_t>(std::llround(position)),
                          std::memory_order_relaxed);
}

void Deck::RenderStretched(float* interleavedStereoOut, int32_t frames, float mixGain,
                           int32_t outputSampleRate) {
    const int64_t trackFrames = current_->frames;
    if (trackFrames <= 0 ||
        (current_->samples.empty() && current_->pcm16.empty() && !current_->mapped) ||
        !stretchLeft_) {
        return;
    }

    const float tempo = std::clamp(tempo_.load(std::memory_order_relaxed), 0.5f, 2.0f);
    stretchLeft_->setSpeedRatio(tempo);
    stretchRight_->setSpeedRatio(tempo);

    const int trackRate =
        current_->sampleRate > 0 ? current_->sampleRate : outputSampleRate;
    const double sourceStep = static_cast<double>(trackRate) / outputSampleRate;

    while (stretchFifoCount_ < frames &&
           stretchSourcePos_ < static_cast<double>(trackFrames - 1)) {
        const double remainingSource =
            static_cast<double>(trackFrames - 1) - stretchSourcePos_;
        if (remainingSource <= 0.0) {
            break;
        }
        const int want = std::min(
            kStretchInputFrames,
            std::max(1, static_cast<int>(remainingSource / sourceStep) + 1));
        if (want <= 0) {
            break;
        }

        for (int i = 0; i < want; ++i) {
            const double sourcePosition = stretchSourcePos_ + i * sourceStep;
            stretchInputLeft_[i] = CubicSample(*current_, trackFrames, sourcePosition, 0);
            stretchInputRight_[i] = CubicSample(*current_, trackFrames, sourcePosition, 1);
        }

        int consumedLeft = 0;
        int consumedRight = 0;
        stretchLeft_->resetInputPosition();
        const int writtenLeft = stretchLeft_->process(
            stretchInputLeft_.data(), want, stretchOutputLeft_.data(), kStretchOutputFrames,
            consumedLeft);
        stretchRight_->resetInputPosition();
        const int writtenRight = stretchRight_->process(
            stretchInputRight_.data(), want, stretchOutputRight_.data(), kStretchOutputFrames,
            consumedRight);

        const int written = std::min(writtenLeft, writtenRight);
        const int consumed = std::max(consumedLeft, consumedRight);
        if (written > 0) {
            PushStretchFrames(stretchOutputLeft_.data(), stretchOutputRight_.data(), written);
        }
        stretchSourcePos_ += consumed * sourceStep;
        if (written <= 0 && consumed <= 0) {
            break;
        }
    }

    const int available = std::min(frames, stretchFifoCount_);
    const bool highPass = UpdateFilters(outputSampleRate);
    UpdateEq(outputSampleRate);
    gainSmoothed_ += (gain_.load(std::memory_order_relaxed) - gainSmoothed_) * 0.2f;
    const float envelopeCoefficient =
        1.0f - std::exp(-1.0f / (0.004f * outputSampleRate));
    const float outputGain = mixGain * gainSmoothed_;

    for (int32_t i = 0; i < frames; ++i) {
        float left = 0.0f;
        float right = 0.0f;
        if (i < available) {
            left = stretchFifo_[static_cast<size_t>(stretchFifoRead_) * 2];
            right = stretchFifo_[static_cast<size_t>(stretchFifoRead_) * 2 + 1];
            stretchFifoRead_ = (stretchFifoRead_ + 1) % kStretchFifoFrames;
            --stretchFifoCount_;
        }

        envelope_ += (1.0f - envelope_) * envelopeCoefficient;
        eqPreSpectrum_.Push(left, right);
        left = ApplyEqLeft(left);
        right = ApplyEqRight(right);
        eqPostSpectrum_.Push(left, right);
        const float wetLeft = highPass ? svfLeft_.ProcessHighPass(left)
                                       : svfLeft_.ProcessLowPass(left);
        const float wetRight = highPass ? svfRight_.ProcessHighPass(right)
                                        : svfRight_.ProcessLowPass(right);
        left = left * (1.0f - filterWet_) + wetLeft * filterWet_;
        right = right * (1.0f - filterWet_) + wetRight * filterWet_;

        const float gain = envelope_ * outputGain;
        interleavedStereoOut[static_cast<size_t>(i) * 2] += left * gain;
        interleavedStereoOut[static_cast<size_t>(i) * 2 + 1] += right * gain;
    }

    double position = static_cast<double>(positionFrames_.load(std::memory_order_relaxed)) +
                      available * tempo * sourceStep;
    const bool loopActive = loopEnabled_.load(std::memory_order_relaxed);
    const int64_t loopStart = loopStartFrames_.load(std::memory_order_relaxed);
    const int64_t loopEnd = loopEndFrames_.load(std::memory_order_relaxed);
    if (loopActive && loopEnd > loopStart && position >= static_cast<double>(loopEnd)) {
        position = static_cast<double>(loopStart);
        ResetStretch(loopStart);
    } else if (!loopActive && stretchFifoCount_ == 0 &&
               stretchSourcePos_ >= static_cast<double>(trackFrames - 1)) {
        playing_.store(false, std::memory_order_relaxed);
        ended_.store(true, std::memory_order_relaxed);
        position = static_cast<double>(trackFrames - 1);
    }
    position = std::clamp(position, 0.0, static_cast<double>(trackFrames - 1));
    positionFrames_.store(static_cast<int64_t>(std::llround(position)),
                          std::memory_order_relaxed);
}

void Deck::GetEqSpectra(float* pre, float* post, int count) const {
    eqPreSpectrum_.GetBands(pre, count);
    eqPostSpectrum_.GetBands(post, count);
}

int64_t Deck::PositionFrames() const {
    return positionFrames_.load(std::memory_order_relaxed);
}

int64_t Deck::DurationFrames() const {
    return durationFrames_.load(std::memory_order_relaxed);
}

double Deck::PositionSeconds() const {
    const int rate = TrackSampleRate();
    return rate > 0 ? static_cast<double>(PositionFrames()) / rate : 0.0;
}

double Deck::DurationSeconds() const {
    const int rate = TrackSampleRate();
    return rate > 0 ? static_cast<double>(DurationFrames()) / rate : 0.0;
}

int Deck::TrackSampleRate() const {
    return trackSampleRate_.load(std::memory_order_relaxed);
}

bool Deck::ConsumeEnded() {
    return ended_.exchange(false, std::memory_order_relaxed);
}
