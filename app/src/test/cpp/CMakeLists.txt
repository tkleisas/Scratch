cmake_minimum_required(VERSION 3.22)

project(scratch_native_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()

set(MAIN_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")

add_executable(scratch_tests
    test_track_loader.cpp
    test_deck.cpp
    test_deck_pair.cpp
    test_yawn_dsp.cpp
    test_bpm.cpp
    test_spectrum.cpp
    test_fx.cpp
    "${MAIN_CPP_DIR}/audio/TrackLoader.cpp"
    "${MAIN_CPP_DIR}/audio/Deck.cpp"
    "${MAIN_CPP_DIR}/audio/BpmAnalyzer.cpp"
    "${MAIN_CPP_DIR}/audio/SpectrumAnalyzer.cpp"
    "${MAIN_CPP_DIR}/audio/FxRack.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/audio/TimeStretcher.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/audio/TransientDetector.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/Filter.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/Delay.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/PlateReverb.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/TapeEmulation.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/Compressor.cpp"
    "${MAIN_CPP_DIR}/third_party/yawn/effects/Limiter.cpp")

target_include_directories(scratch_tests PRIVATE
    "${MAIN_CPP_DIR}"
    "${MAIN_CPP_DIR}/third_party/yawn")
target_link_libraries(scratch_tests PRIVATE GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(scratch_tests)
