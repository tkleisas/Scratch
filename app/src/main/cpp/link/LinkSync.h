#pragma once

#include <ableton/Link.hpp>

#include <atomic>
#include <cstddef>

// Desktop-only Ableton Link session wrapper. Link is dual-licensed under
// GPLv2+ and a proprietary license; Scratch uses the GPLv2+ option.
class LinkSync {
public:
    LinkSync();
    ~LinkSync();

    void SetEnabled(bool enabled);
    bool IsEnabled() const;
    std::size_t PeerCount() const;

    double Tempo() const;
    double Phase() const;
    void SetTempo(double bpm);

private:
    ableton::Link link_;
    std::atomic<double> lastTempo_{120.0};
};
