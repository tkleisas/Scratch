cmake_minimum_required(VERSION 3.22)

project(scratchdj_desktop LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
pkg_check_modules(ALSA REQUIRED alsa)

if(NOT JAVA_INCLUDE_PATH)
    set(JAVA_INCLUDE_PATH "$ENV{JAVA_HOME}/include")
endif()
if(NOT JAVA_INCLUDE_PATH2)
    set(JAVA_INCLUDE_PATH2 "${JAVA_INCLUDE_PATH}/linux")
endif()
if(NOT EXISTS "${JAVA_INCLUDE_PATH}/jni.h")
    message(FATAL_ERROR "Set JAVA_INCLUDE_PATH to a full JDK include directory (JBR may not ship jni.h)")
endif()

set(APP_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

add_library(scratchdj SHARED
    "${APP_CPP}/jni_bridge.cpp"
    "${APP_CPP}/audio/Deck.cpp"
    "${APP_CPP}/audio/FxRack.cpp"
    "${APP_CPP}/audio/TrackLoader.cpp"
    "${APP_CPP}/audio/BpmAnalyzer.cpp"
    "${APP_CPP}/audio/SpectrumAnalyzer.cpp"
    "${APP_CPP}/third_party/yawn/audio/TimeStretcher.cpp"
    "${APP_CPP}/third_party/yawn/audio/TransientDetector.cpp"
    "${APP_CPP}/third_party/yawn/effects/Filter.cpp"
    "${APP_CPP}/third_party/yawn/effects/Delay.cpp"
    "${APP_CPP}/third_party/yawn/effects/PlateReverb.cpp"
    "${APP_CPP}/third_party/yawn/effects/TapeEmulation.cpp"
    "${APP_CPP}/third_party/yawn/effects/Compressor.cpp"
    "${APP_CPP}/third_party/yawn/effects/Limiter.cpp"
    audio/AudioEngine.cpp)

target_include_directories(scratchdj PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/audio"
    "${APP_CPP}"
    "${APP_CPP}/audio"
    "${APP_CPP}/third_party/yawn"
    ${ALSA_INCLUDE_DIRS}
    ${JAVA_INCLUDE_PATH}
    ${JAVA_INCLUDE_PATH2})

target_link_libraries(scratchdj PRIVATE
    ${ALSA_LIBRARIES}
    pthread)

target_compile_definitions(scratchdj PRIVATE SCRATCH_DESKTOP_AUDIO)

target_compile_options(scratchdj PRIVATE
    -O3
    -ffast-math
    -Wall
    -Wextra
    -Wno-unused-parameter)
