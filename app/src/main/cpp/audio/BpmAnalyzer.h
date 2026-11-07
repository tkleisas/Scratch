#pragma once

#include <cstdint>

#include "TrackData.h"

namespace scratch {

float EstimateBpm(const TrackData& track);
float EstimateBeatOffset(const TrackData& track, float bpm);
float EstimateBpm(const int16_t* interleaved, int64_t frames, int sampleRate);
float EstimateBpm(const float* interleaved, int64_t frames, int sampleRate);

}
