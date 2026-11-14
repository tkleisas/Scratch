#include "LinkSync.h"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace {
constexpr double kLinkQuantum = 4.0;
}

LinkSync::LinkSync() : link_(120.0) {}

LinkSync::~LinkSync() {
    link_.enable(false);
}

void LinkSync::SetEnabled(bool enabled) {
    if (enabled == link_.isEnabled()) return;
    if (enabled) {
        auto state = link_.captureAppSessionState();
        state.setTempo(lastTempo_.load(std::memory_order_relaxed), link_.clock().micros());
        link_.commitAppSessionState(state);
    }
    link_.enable(enabled);
}

bool LinkSync::IsEnabled() const {
    return link_.isEnabled();
}

std::size_t LinkSync::PeerCount() const {
    return link_.numPeers();
}

double LinkSync::Tempo() const {
    return link_.captureAppSessionState().tempo();
}

double LinkSync::Phase() const {
    const auto state = link_.captureAppSessionState();
    const double phase = state.phaseAtTime(link_.clock().micros(), kLinkQuantum);
    const double normalized = phase / kLinkQuantum;
    return normalized - std::floor(normalized);
}

void LinkSync::SetTempo(double bpm) {
    if (bpm <= 0.0) return;
    lastTempo_.store(bpm, std::memory_order_relaxed);
    auto state = link_.captureAppSessionState();
    state.setTempo(bpm, link_.clock().micros());
    link_.commitAppSessionState(state);
}
