#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

#include "Deck.h"
#include "EqualPower.h"

class DeckPair {
public:
    Deck& Active() {
        return decks_[activeVoice_.load(std::memory_order_acquire)];
    }

    const Deck& Active() const {
        return decks_[activeVoice_.load(std::memory_order_acquire)];
    }

    Deck& Incoming() {
        return decks_[1 - activeVoice_.load(std::memory_order_acquire)];
    }

    const Deck& Incoming() const {
        return decks_[1 - activeVoice_.load(std::memory_order_acquire)];
    }

    void PrepareForSampleRate(int outputSampleRate) {
        decks_[0].PrepareForSampleRate(outputSampleRate);
        decks_[1].PrepareForSampleRate(outputSampleRate);
    }

    void Render(float* interleavedStereoOut, int32_t frames, float crossGainStart,
                float crossGainEnd, int32_t outputSampleRate) {
        if (!active_.load(std::memory_order_acquire)) {
            Active().Render(interleavedStereoOut, frames, crossGainStart, outputSampleRate,
                            crossGainEnd);
            return;
        }

        const int fromVoice = fromVoice_.load(std::memory_order_relaxed);
        const int toVoice = toVoice_.load(std::memory_order_relaxed);
        const int64_t total = totalFrames_.load(std::memory_order_relaxed);
        const int64_t remaining = remainingFrames_.load(std::memory_order_relaxed);
        const int64_t after = std::max<int64_t>(0, remaining - frames);
        const float progressStart = total > 0
            ? 1.0f - static_cast<float>(remaining) / static_cast<float>(total)
            : 1.0f;
        const float progressEnd = total > 0
            ? 1.0f - static_cast<float>(after) / static_cast<float>(total)
            : 1.0f;
        const StereoGains fadeStart = EqualPowerGains(progressStart);
        const StereoGains fadeEnd = EqualPowerGains(progressEnd);

        decks_[fromVoice].Render(
            interleavedStereoOut,
            frames,
            crossGainStart * fadeStart.a,
            outputSampleRate,
            crossGainEnd * fadeEnd.a);
        decks_[toVoice].Render(
            interleavedStereoOut,
            frames,
            crossGainStart * fadeStart.b,
            outputSampleRate,
            crossGainEnd * fadeEnd.b);

        remainingFrames_.store(after, std::memory_order_relaxed);
        if (after <= 0) {
            decks_[fromVoice].SetPlaying(false);
            activeVoice_.store(toVoice, std::memory_order_release);
            active_.store(false, std::memory_order_release);
            completed_.store(true, std::memory_order_release);
        }
    }

    bool StartCrossfade(float seconds, int outputSampleRate) {
        if (active_.load(std::memory_order_acquire) || outputSampleRate <= 0) {
            return false;
        }
        const int fromVoice = activeVoice_.load(std::memory_order_acquire);
        const int toVoice = 1 - fromVoice;
        Deck& outgoing = decks_[fromVoice];
        Deck& incoming = decks_[toVoice];
        if (!outgoing.IsPlaying() || outgoing.DurationFrames() <= 0 ||
            incoming.DurationFrames() <= 0) {
            return false;
        }

        incoming.CopyMixSettingsFrom(outgoing);
        incoming.SetPlaying(true);

        const float duration = std::max(0.05f, seconds);
        const int64_t requested = static_cast<int64_t>(
            std::llround(static_cast<double>(duration) * outputSampleRate));
        const int64_t remaining = std::max<int64_t>(
            1, outgoing.DurationFrames() - outgoing.PositionFrames());
        const int64_t total = std::min(requested, remaining);
        fromVoice_.store(fromVoice, std::memory_order_relaxed);
        toVoice_.store(toVoice, std::memory_order_relaxed);
        totalFrames_.store(total, std::memory_order_relaxed);
        remainingFrames_.store(total, std::memory_order_relaxed);
        completed_.store(false, std::memory_order_relaxed);
        active_.store(true, std::memory_order_release);
        return true;
    }

    bool IsCrossfading() const {
        return active_.load(std::memory_order_acquire);
    }

    float CrossfadeProgress() const {
        if (!active_.load(std::memory_order_acquire)) return 0.0f;
        const int64_t total = totalFrames_.load(std::memory_order_relaxed);
        if (total <= 0) return 1.0f;
        const int64_t remaining = remainingFrames_.load(std::memory_order_relaxed);
        return std::clamp(
            1.0f - static_cast<float>(remaining) / static_cast<float>(total),
            0.0f,
            1.0f);
    }

    int64_t CrossfadeTotalFrames() const {
        return totalFrames_.load(std::memory_order_relaxed);
    }

    bool ConsumeCrossfadeComplete() {
        return completed_.exchange(false, std::memory_order_acq_rel);
    }

    void CancelCrossfade() {
        const bool wasActive = active_.exchange(false, std::memory_order_acq_rel);
        if (wasActive) {
            const int toVoice = toVoice_.load(std::memory_order_relaxed);
            decks_[toVoice].SetPlaying(false);
        }
        completed_.store(false, std::memory_order_release);
    }

    bool ConsumeEnded() {
        if (active_.load(std::memory_order_acquire)) {
            Active().ConsumeEnded();
            return false;
        }
        return Active().ConsumeEnded();
    }

private:
    Deck decks_[2];
    std::atomic<int> activeVoice_{0};
    std::atomic<bool> active_{false};
    std::atomic<bool> completed_{false};
    std::atomic<int> fromVoice_{0};
    std::atomic<int> toVoice_{1};
    std::atomic<int64_t> totalFrames_{0};
    std::atomic<int64_t> remainingFrames_{0};
};
