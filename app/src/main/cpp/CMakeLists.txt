cmake_minimum_required(VERSION 3.22.1)

project(scratchdj LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(scratchdj SHARED
    jni_bridge.cpp
    audio/AudioEngine.cpp
    audio/Deck.cpp
    audio/FxRack.cpp
    audio/BpmAnalyzer.cpp
    audio/SpectrumAnalyzer.cpp
    audio/TrackLoader.cpp
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
    log)

# Android 15+ devices can use 16 KB memory pages. Keep every LOAD segment
# aligned so the native engine loads on both 4 KB and 16 KB devices.
target_link_options(scratchdj PRIVATE "-Wl,-z,max-page-size=16384")

target_compile_options(scratchdj PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter)
