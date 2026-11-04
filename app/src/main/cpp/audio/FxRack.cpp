#include "FxRack.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "effects/Bitcrusher.h"
#include "effects/Compressor.h"
#include "effects/Delay.h"
#include "effects/Filter.h"
#include "effects/Limiter.h"
#include "effects/PlateReverb.h"
#include "effects/TapeEmulation.h"

namespace {
constexpr int kOrderShift = 8;
constexpr uint64_t kOrderMask = 0xFF;
}

uint64_t FxRack::PackOrder(const int* order) {
    uint64_t packed = 0;
    for (int position = 0; position < kEffectCount; ++position) {
        const int effect = order[position];
        if (effect >= 0 && effect < kEffectCount) {
            packed |= static_cast<uint64_t>(effect + 1) << (position * kOrderShift);
        }
    }
    return packed;
}

void FxRack::DefaultOrder(int* order) {
    for (int i = 0; i < kEffectCount; ++i) {
        order[i] = i;
    }
}

int FxRack::MixParamFor(int type) {
    switch (type) {
        case kCrush: return 4;
        case kEcho: return 2;
        case kReverb: return 5;
        case kTape: return 5;
        default: return -1;
    }
}

int FxRack::TailSamplesFor(int type, double sampleRate) {
    switch (type) {
        case kEcho: return static_cast<int>(sampleRate * 4.0);
        case kReverb: return static_cast<int>(sampleRate * 5.0);
        case kTape: return static_cast<int>(sampleRate * 0.35);
        default: return static_cast<int>(sampleRate * 0.15);
    }
}

void FxRack::Init(double sampleRate, int maxBlockSize) {
    sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
    maxBlockSize_ = std::max(maxBlockSize, 512);
    dryInput_.assign(static_cast<size_t>(maxBlockSize_) * 2, 0.0f);
    punchScratch_.assign(static_cast<size_t>(maxBlockSize_) * 2, 0.0f);

    int order[kEffectCount];
    DefaultOrder(order);
    const uint64_t packedOrder = PackOrder(order);

    for (int chainIndex = 0; chainIndex < kChainCount; ++chainIndex) {
        ChainState& chain = chains_[chainIndex];
        chain.order.store(packedOrder, std::memory_order_relaxed);
        chain.enabledMask.store(chainIndex == kPunch ? 0x7Fu : 0u,
                                std::memory_order_relaxed);

        for (int type = 0; type < kEffectCount; ++type) {
            Node& node = chain.nodes[type];
            node.type = type;
            node.mixParam = MixParamFor(type);
            node.tailSamples = TailSamplesFor(type, sampleRate_);
            node.returnGain = 0.0f;
            node.tailRemaining = 0;
            node.wasActive = false;
            node.enabled.store(false, std::memory_order_relaxed);
            node.padActive.store(false, std::memory_order_relaxed);
            node.send.store(0.35f, std::memory_order_relaxed);

            std::unique_ptr<yawn::effects::AudioEffect> effect;
            switch (type) {
                case kFilter: effect = std::make_unique<yawn::effects::Filter>(); break;
                case kCrush: effect = std::make_unique<yawn::effects::Bitcrusher>(); break;
                case kEcho: effect = std::make_unique<yawn::effects::Delay>(); break;
                case kReverb: effect = std::make_unique<yawn::effects::PlateReverb>(); break;
                case kTape: effect = std::make_unique<yawn::effects::TapeEmulation>(); break;
                case kCompressor: effect = std::make_unique<yawn::effects::Compressor>(); break;
                case kLimiter: effect = std::make_unique<yawn::effects::Limiter>(); break;
                default: break;
            }
            if (!effect) {
                continue;
            }
            effect->init(sampleRate_, maxBlockSize_);
            effect->reset();
            effect->setBypassed(false);
            node.paramCount = std::min(effect->parameterCount(), kMaxParams);
            for (int param = 0; param < node.paramCount; ++param) {
                const auto& info = effect->parameterInfo(param);
                const float value = info.defaultValue;
                node.params[param].store(value, std::memory_order_relaxed);
                node.lastParams[param] = std::numeric_limits<float>::quiet_NaN();
            }
            if (chainIndex == kPunch && node.mixParam >= 0 &&
                node.mixParam < node.paramCount) {
                node.params[node.mixParam].store(1.0f, std::memory_order_relaxed);
            }
            node.effect = std::move(effect);
        }
    }
    ready_ = true;
}

void FxRack::SetEnabled(Chain chain, int effect, bool enabled) {
    if (chain < 0 || chain >= kChainCount || effect < 0 || effect >= kEffectCount) {
        return;
    }
    ChainState& state = chains_[chain];
    const uint32_t bit = 1u << effect;
    uint32_t mask = state.enabledMask.load(std::memory_order_relaxed);
    if (enabled) {
        mask |= bit;
    } else {
        mask &= ~bit;
    }
    state.enabledMask.store(mask, std::memory_order_relaxed);
}

void FxRack::SetOrder(Chain chain, const int* order) {
    if (chain < 0 || chain >= kChainCount || order == nullptr) {
        return;
    }
    chains_[chain].order.store(PackOrder(order), std::memory_order_relaxed);
}

void FxRack::SetParam(Chain chain, int effect, int index, float value) {
    if (chain < 0 || chain >= kChainCount || effect < 0 || effect >= kEffectCount ||
        index < 0 || index >= kMaxParams) {
        return;
    }
    Node& node = chains_[chain].nodes[effect];
    if (!node.effect || index >= node.paramCount) {
        return;
    }
    const auto& info = node.effect->parameterInfo(index);
    const float clamped = std::clamp(value, info.minValue, info.maxValue);
    node.params[index].store(clamped, std::memory_order_relaxed);
}

float FxRack::GetParam(Chain chain, int effect, int index) const {
    if (chain < 0 || chain >= kChainCount || effect < 0 || effect >= kEffectCount ||
        index < 0 || index >= kMaxParams) {
        return 0.0f;
    }
    const Node& node = chains_[chain].nodes[effect];
    if (!node.effect || index >= node.paramCount) {
        return 0.0f;
    }
    return node.params[index].load(std::memory_order_relaxed);
}

int FxRack::ParamCount(Chain chain, int effect) const {
    if (chain < 0 || chain >= kChainCount || effect < 0 || effect >= kEffectCount) {
        return 0;
    }
    return chains_[chain].nodes[effect].paramCount;
}

void FxRack::SetPunchActive(int effect, bool active) {
    if (effect < 0 || effect >= kEffectCount) {
        return;
    }
    chains_[kPunch].nodes[effect].padActive.store(active, std::memory_order_relaxed);
}

void FxRack::SetPunchSend(int effect, float send) {
    if (effect < 0 || effect >= kEffectCount) {
        return;
    }
    chains_[kPunch].nodes[effect].send.store(
        std::clamp(send, 0.0f, 1.0f), std::memory_order_relaxed);
}

float FxRack::GetPunchSend(int effect) const {
    if (effect < 0 || effect >= kEffectCount) {
        return 0.0f;
    }
    return chains_[kPunch].nodes[effect].send.load(std::memory_order_relaxed);
}

bool FxRack::PunchActive(int effect) const {
    if (effect < 0 || effect >= kEffectCount) {
        return false;
    }
    return chains_[kPunch].nodes[effect].padActive.load(std::memory_order_relaxed);
}

void FxRack::ApplyParams(Node& node, bool punch) {
    if (!node.effect) {
        return;
    }
    for (int index = 0; index < node.paramCount; ++index) {
        if (punch && index == node.mixParam) {
            continue;
        }
        const float value = node.params[index].load(std::memory_order_relaxed);
        if (value != node.lastParams[index]) {
            node.effect->setParameter(index, value);
            node.lastParams[index] = value;
        }
    }
    if (punch && node.mixParam >= 0 && node.mixParam < node.paramCount) {
        node.effect->setParameter(node.mixParam, 1.0f);
    }
    node.effect->setBypassed(false);
}

void FxRack::Process(float* interleavedStereo, int frames) {
    if (!ready_ || interleavedStereo == nullptr || frames <= 0) {
        return;
    }
    if (frames > maxBlockSize_) {
        frames = maxBlockSize_;
    }

    ProcessInsert(interleavedStereo, frames);
    std::memcpy(dryInput_.data(), interleavedStereo,
                sizeof(float) * static_cast<size_t>(frames) * 2);
    ProcessPunch(dryInput_.data(), interleavedStereo, frames);
}

void FxRack::ProcessInsert(float* buffer, int frames) {
    ChainState& chain = chains_[kInsert];
    const uint64_t order = chain.order.load(std::memory_order_relaxed);
    const uint32_t enabled = chain.enabledMask.load(std::memory_order_relaxed);

    for (int position = 0; position < kEffectCount; ++position) {
        const int effect = static_cast<int>(
            (order >> (position * kOrderShift)) & kOrderMask) - 1;
        if (effect < 0 || effect >= kEffectCount) {
            continue;
        }
        if ((enabled & (1u << effect)) == 0) {
            continue;
        }
        Node& node = chain.nodes[effect];
        if (!node.effect) {
            continue;
        }
        ApplyParams(node, false);
        node.effect->process(buffer, frames, 2);
    }
}

void FxRack::ProcessPunch(const float* dry, float* buffer, int frames) {
    ChainState& chain = chains_[kPunch];
    const uint32_t enabled = chain.enabledMask.load(std::memory_order_relaxed);

    for (int effect = 0; effect < kEffectCount; ++effect) {
        Node& node = chain.nodes[effect];
        if (!node.effect || (enabled & (1u << effect)) == 0) {
            node.returnGain = 0.0f;
            node.tailRemaining = 0;
            node.wasActive = false;
            continue;
        }

        const bool active = node.padActive.load(std::memory_order_relaxed);
        if (active && !node.wasActive) {
            node.tailRemaining = node.tailSamples;
        }
        node.wasActive = active;
        if (!active && node.tailRemaining > 0) {
            node.tailRemaining = std::max(0, node.tailRemaining - frames);
        }

        if (!active && node.tailRemaining <= 0) {
            node.returnGain += (0.0f - node.returnGain) * 0.25f;
            if (node.returnGain < 0.0001f) {
                node.returnGain = 0.0f;
            }
            continue;
        }

        const float sendLevel = node.send.load(std::memory_order_relaxed);
        if (sendLevel < 0.001f && node.returnGain < 0.001f) {
            node.returnGain = 0.0f;
            node.tailRemaining = 0;
            continue;
        }

        ApplyParams(node, true);

        if (active) {
            std::memcpy(punchScratch_.data(), dry,
                        sizeof(float) * static_cast<size_t>(frames) * 2);
        } else {
            std::fill(punchScratch_.begin(), punchScratch_.begin() + frames * 2, 0.0f);
        }

        node.effect->process(punchScratch_.data(), frames, 2);

        const float target = sendLevel;
        const float step = (target - node.returnGain) / static_cast<float>(frames);
        float gain = node.returnGain;
        for (int frame = 0; frame < frames; ++frame) {
            gain += step;
            buffer[frame * 2] += punchScratch_[frame * 2] * gain;
            buffer[frame * 2 + 1] += punchScratch_[frame * 2 + 1] * gain;
        }
        node.returnGain = target;
    }
}
