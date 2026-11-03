#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

#include "TrackData.h"

namespace scratch {

std::shared_ptr<TrackData> LoadTrackFromFd(int fd, std::string& error, int targetSampleRate = 0);
std::shared_ptr<TrackData> LoadTrackFromMemory(const uint8_t* data, size_t size, std::string& error,
                                               int targetSampleRate = 0);
std::shared_ptr<TrackData> LoadTrackFromFile(const std::string& path, std::string& error,
                                             int targetSampleRate = 0);
std::shared_ptr<TrackData> BuildTrackFromPcm16(std::vector<int16_t> pcm, int sampleRate,
                                               std::string& error);
std::shared_ptr<TrackData> LoadMappedPcmFromFile(const std::string& path, int sampleRate,
                                                 std::string& error);

}
