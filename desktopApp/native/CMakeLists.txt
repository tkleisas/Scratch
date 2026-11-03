cmake_minimum_required(VERSION 3.22)

project(scratchdj_desktop LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT JAVA_INCLUDE_PATH)
    set(JAVA_INCLUDE_PATH "$ENV{JAVA_HOME}/include")
endif()
if(NOT JAVA_INCLUDE_PATH2)
    if(WIN32)
        set(JAVA_INCLUDE_PATH2 "${JAVA_INCLUDE_PATH}/win32")
    else()
        set(JAVA_INCLUDE_PATH2 "${JAVA_INCLUDE_PATH}/linux")
    endif()
endif()
if(NOT EXISTS "${JAVA_INCLUDE_PATH}/jni.h")
    message(FATAL_ERROR "Set JAVA_INCLUDE_PATH to a full JDK include directory (JBR may not ship jni.h)")
endif()

set(APP_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

set(DESKTOP_AUDIO_SOURCE audio/AudioEngine.cpp)
if(WIN32)
    set(DESKTOP_AUDIO_SOURCE audio/windows/AudioEngine.cpp)
endif()

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
    ${DESKTOP_AUDIO_SOURCE})

if(WIN32)
    target_include_directories(scratchdj PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/audio/windows"
        "${CMAKE_CURRENT_SOURCE_DIR}/audio"
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${APP_CPP}"
        "${APP_CPP}/audio"
        "${APP_CPP}/third_party/yawn"
        ${JAVA_INCLUDE_PATH}
        ${JAVA_INCLUDE_PATH2})
else()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(ALSA REQUIRED alsa)
    target_include_directories(scratchdj PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/audio"
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${APP_CPP}"
        "${APP_CPP}/audio"
        "${APP_CPP}/third_party/yawn"
        ${ALSA_INCLUDE_DIRS}
        ${JAVA_INCLUDE_PATH}
        ${JAVA_INCLUDE_PATH2})
endif()

target_compile_definitions(scratchdj PRIVATE SCRATCH_DESKTOP_AUDIO)
if(WIN32)
    target_compile_definitions(scratchdj PRIVATE NOMINMAX)
    target_compile_options(scratchdj PRIVATE /O2 /fp:fast /W3 /EHsc /utf-8)
else()
    target_compile_options(scratchdj PRIVATE
        -O3
        -ffast-math
        -Wall
        -Wextra
        -Wno-unused-parameter)
endif()

if(WIN32)
    include(FetchContent)
    option(SCRATCH_ENABLE_ASIO "Build PortAudio ASIO support (GPLv3 ASIO SDK)" OFF)
    FetchContent_Declare(
        portaudio
        GIT_REPOSITORY https://github.com/PortAudio/portaudio.git
        GIT_TAG        375345a752822e795bf6daeaa8e9ba981389c0ca
        GIT_SHALLOW    FALSE)
    set(PA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(PA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(PA_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    if(SCRATCH_ENABLE_ASIO)
        set(PA_USE_ASIO ON CACHE BOOL "" FORCE)
    else()
        set(PA_USE_ASIO OFF CACHE BOOL "" FORCE)
    endif()
    set(PA_USE_WASAPI ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(portaudio)

    target_include_directories(scratchdj PRIVATE "${portaudio_SOURCE_DIR}/include")
    if(TARGET portaudio_static)
        target_link_libraries(scratchdj PRIVATE portaudio_static)
    elseif(TARGET portaudio)
        target_link_libraries(scratchdj PRIVATE portaudio)
    else()
        message(FATAL_ERROR "PortAudio target not found")
    endif()
    target_link_libraries(scratchdj PRIVATE ole32 uuid winmm avrt ksuser)
else()
    target_link_libraries(scratchdj PRIVATE
        ${ALSA_LIBRARIES}
        pthread)
endif()
