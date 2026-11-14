cmake_minimum_required(VERSION 3.22.1)

project(scratchdj LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)
FetchContent_Declare(
    ableton_link
    GIT_REPOSITORY https://github.com/Ableton/link.git
    GIT_TAG        5ba907ae027a8c2ec8824463e37296dcefc5e9be
    GIT_SHALLOW    FALSE
    GIT_SUBMODULES "modules/asio-standalone"
    GIT_SUBMODULES_RECURSE FALSE)
FetchContent_GetProperties(ableton_link)
if(NOT ableton_link_POPULATED)
    FetchContent_Populate(ableton_link)
endif()
include(${ableton_link_SOURCE_DIR}/AbletonLinkConfig.cmake)

add_library(scratchdj SHARED
    jni_bridge.cpp
    audio/AudioEngine.cpp
    audio/Deck.cpp
    audio/FxRack.cpp
    audio/BpmAnalyzer.cpp
    audio/SpectrumAnalyzer.cpp
    audio/TrackLoader.cpp
    link/LinkSync.cpp
    third_party/yawn/audio/TimeStretcher.cpp
    third_party/yawn/audio/TransientDetector.cpp
    third_party/yawn/effects/Filter.cpp
    third_party/yawn/effects/Delay.cpp
    third_party/yawn/effects/HallReverb.cpp
    third_party/yawn/effects/TapeEmulation.cpp
    third_party/yawn/effects/Compressor.cpp
    third_party/yawn/effects/Limiter.cpp)

target_include_directories(scratchdj PRIVATE
    .
    third_party/yawn)

target_link_libraries(scratchdj PRIVATE
    oboe::oboe
    android
    log
    Ableton::Link)

# Android 15+ devices can use 16 KB memory pages. Keep every LOAD segment
# aligned so the native engine loads on both 4 KB and 16 KB devices.
target_link_options(scratchdj PRIVATE "-Wl,-z,max-page-size=16384")

target_compile_options(scratchdj PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter)
