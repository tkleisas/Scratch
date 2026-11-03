#include <jni.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#ifdef SCRATCH_DESKTOP_AUDIO
#include "AudioEngine.h"
#else
#include "audio/AudioEngine.h"
#endif
#include "audio/BpmAnalyzer.h"
#include "audio/TrackLoader.h"

namespace {

std::mutex gEngineMutex;
std::unique_ptr<AudioEngine> gEngine;
std::string gLastError;


bool ValidDeck(jint deck) {
    return deck == 0 || deck == 1;
}

jboolean Fail(JNIEnv*, const std::string& error) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gLastError = error;
    return JNI_FALSE;
}

jstring ToJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        return JNI_TRUE;
    }
    auto engine = std::make_unique<AudioEngine>();
    std::string error;
    if (!engine->Start(error)) {
        gLastError = error;
        return JNI_FALSE;
    }
    gEngine = std::move(engine);
    gLastError.clear();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeShutdown(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gEngine.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeIsRunning(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeLoadTrack(JNIEnv*, jobject, jint deck, jint fd) {
    if (!ValidDeck(deck)) {
        return Fail(nullptr, "Invalid deck");
    }

    int sampleRate = 48000;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gEngine) {
            sampleRate = gEngine->SampleRate();
        }
    }

    std::string error;
    auto track = scratch::LoadTrackFromFd(fd, error, sampleRate);
    if (!track) {
        return Fail(nullptr, error);
    }

    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        gLastError = "Audio engine is not running";
        return JNI_FALSE;
    }
    gEngine->GetDeck(deck).SetTrack(std::move(track));
    gLastError.clear();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeLoadPcmFile(JNIEnv* env, jobject, jint deck,
                                                            jstring path, jint sampleRate) {
    if (!ValidDeck(deck) || path == nullptr) {
        return Fail(nullptr, "Invalid cached PCM track");
    }

    const char* utf8 = env->GetStringUTFChars(path, nullptr);
    if (utf8 == nullptr) {
        return JNI_FALSE;
    }
    std::string filePath(utf8);
    env->ReleaseStringUTFChars(path, utf8);

    std::string error;
    auto track = scratch::LoadMappedPcmFromFile(filePath, sampleRate, error);
    if (!track) {
        return Fail(nullptr, error);
    }

    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        gLastError = "Audio engine is not running";
        return JNI_FALSE;
    }
    gEngine->GetDeck(deck).SetTrack(std::move(track));
    gLastError.clear();
    return JNI_TRUE;
}

JNIEXPORT jfloat JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeAnalyzeBpm(JNIEnv*, jobject, jint deck,
                                                           jlong expectedDurationMs) {
    if (!ValidDeck(deck)) {
        return 0.0f;
    }
    std::shared_ptr<TrackData> track;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (!gEngine) {
            return 0.0f;
        }
        track = gEngine->GetDeck(deck).GetTrackForUi();
    }
    if (!track || track->sampleRate <= 0) {
        return 0.0f;
    }

    const double durationMs =
        static_cast<double>(track->frames) * 1000.0 / track->sampleRate;
    if (expectedDurationMs > 0 &&
        std::abs(durationMs - static_cast<double>(expectedDurationMs)) > 150.0) {
        return 0.0f;
    }
    return scratch::EstimateBpm(*track);
}

JNIEXPORT jfloatArray JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetSpectrum(JNIEnv* env, jobject) {
    constexpr int kBandCount = 32;
    std::vector<float> values(kBandCount, 0.0f);
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gEngine) {
            gEngine->GetSpectrum(values.data(), kBandCount);
        }
    }
    jfloatArray result = env->NewFloatArray(kBandCount);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, kBandCount, values.data());
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return ToJavaString(env, gLastError);
}

JNIEXPORT jlong JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetPositionMs(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        return 0;
    }
    return static_cast<jlong>(std::llround(gEngine->GetDeck(deck).PositionSeconds() * 1000.0));
}

JNIEXPORT jlong JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetDurationMs(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        return 0;
    }
    return static_cast<jlong>(std::llround(gEngine->GetDeck(deck).DurationSeconds() * 1000.0));
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeIsPlaying(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        return JNI_FALSE;
    }
    return gEngine->GetDeck(deck).IsPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeTogglePlaying(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).TogglePlaying();
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetPlaying(JNIEnv*, jobject, jint deck,
                                                           jboolean playing) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetPlaying(playing == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeScratchBegin(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).BeginScratch();
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetScratchRate(JNIEnv*, jobject, jint deck,
                                                               jfloat rate) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetScratchRate(rate);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeScratchEnd(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).EndScratch();
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSeekFraction(JNIEnv*, jobject, jint deck,
                                                             jfloat fraction) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SeekFraction(fraction);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetCrossfader(JNIEnv*, jobject, jfloat value) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->SetCrossfader(value);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetMasterGain(JNIEnv*, jobject, jfloat value) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->SetMasterGain(value);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetDeckGain(JNIEnv*, jobject, jint deck,
                                                            jfloat value) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetGain(value);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetFilterMode(JNIEnv*, jobject, jint deck,
                                                              jint mode) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetFilterMode(mode);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetFilterCutoff(JNIEnv*, jobject, jint deck,
                                                                jfloat value) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetFilterCutoff(value);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetEqNode(JNIEnv*, jobject, jint deck,
                                                           jint index, jfloat frequency,
                                                           jfloat gainDb, jfloat q) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetEqNode(index, frequency, gainDb, q);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetEqBypassed(JNIEnv*, jobject, jint deck,
                                                              jboolean bypassed) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetEqBypassed(bypassed == JNI_TRUE);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetEqResponse(JNIEnv* env, jobject, jint deck,
                                                              jint count) {
    if (!ValidDeck(deck) || count <= 0) {
        return env->NewFloatArray(0);
    }
    count = std::min(count, 1024);
    std::vector<float> values(static_cast<size_t>(count), 0.0f);
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gEngine) {
            gEngine->GetDeck(deck).GetEqResponse(values.data(), count);
        }
    }
    jfloatArray result = env->NewFloatArray(count);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, count, values.data());
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetEqSpectra(JNIEnv* env, jobject, jint deck,
                                                             jint count) {
    if (!ValidDeck(deck) || count <= 0) {
        return env->NewFloatArray(0);
    }
    count = std::min(count, 256);
    std::vector<float> values(static_cast<size_t>(count) * 2, 0.0f);
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gEngine) {
            gEngine->GetDeck(deck).GetEqSpectra(
                values.data(), values.data() + count, count);
        }
    }
    jfloatArray result = env->NewFloatArray(count * 2);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, count * 2, values.data());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetLoopEnabled(JNIEnv*, jobject, jint deck,
                                                               jboolean enabled) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetLoopEnabled(enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetLoopPoints(JNIEnv*, jobject, jint deck,
                                                              jfloat inFraction,
                                                              jfloat outFraction) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetLoopPoints(inFraction, outFraction);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeClearLoop(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).ClearLoop();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeLoadPcm(JNIEnv* env, jobject, jint deck,
                                                        jshortArray pcmArray) {
    if (!ValidDeck(deck) || pcmArray == nullptr) {
        return Fail(nullptr, "Invalid PCM track");
    }
    const jsize length = env->GetArrayLength(pcmArray);
    if (length < 2) {
        return Fail(nullptr, "Decoded PCM track is empty");
    }

    std::vector<int16_t> pcm(static_cast<size_t>(length));
    env->GetShortArrayRegion(pcmArray, 0, length, pcm.data());

    int sampleRate = 48000;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gEngine) {
            sampleRate = gEngine->SampleRate();
        }
    }

    std::string error;
    auto track = scratch::BuildTrackFromPcm16(std::move(pcm), sampleRate, error);
    if (!track) {
        return Fail(nullptr, error);
    }

    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        gLastError = "Audio engine is not running";
        return JNI_FALSE;
    }
    gEngine->GetDeck(deck).SetTrack(std::move(track));
    gLastError.clear();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetTempo(JNIEnv*, jobject, jint deck,
                                                         jfloat value) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetTempo(value);
    }
}

JNIEXPORT void JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeSetKeyLock(JNIEnv*, jobject, jint deck,
                                                           jboolean enabled) {
    if (!ValidDeck(deck)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->GetDeck(deck).SetKeyLock(enabled == JNI_TRUE);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeConsumeEnded(JNIEnv*, jobject, jint deck) {
    if (!ValidDeck(deck)) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        return JNI_FALSE;
    }
    return gEngine->GetDeck(deck).ConsumeEnded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetWaveform(JNIEnv* env, jobject, jint deck,
                                                            jint buckets) {
    if (!ValidDeck(deck) || buckets <= 0) {
        return env->NewFloatArray(0);
    }
    buckets = std::min(buckets, 8192);

    std::shared_ptr<TrackData> track;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (!gEngine) {
            return env->NewFloatArray(0);
        }
        track = gEngine->GetDeck(deck).GetTrackForUi();
    }
    if (!track || track->peaks.empty()) {
        return env->NewFloatArray(0);
    }

    std::vector<float> values(static_cast<size_t>(buckets), 0.0f);
    const int64_t peakCount = static_cast<int64_t>(track->peaks.size());
    for (int i = 0; i < buckets; ++i) {
        const int64_t begin = peakCount * i / buckets;
        const int64_t end = std::max(begin + 1, peakCount * (i + 1) / buckets);
        float peak = 0.0f;
        for (int64_t p = begin; p < end && p < peakCount; ++p) {
            peak = std::max(peak, track->peaks[static_cast<size_t>(p)]);
        }
        values[static_cast<size_t>(i)] = peak;
    }

    jfloatArray result = env->NewFloatArray(buckets);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, buckets, values.data());
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->SampleRate() : 48000;
}

JNIEXPORT jint JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetLatencyMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->LatencyMs() : 0;
}

JNIEXPORT jint JNICALL
Java_com_tkleisas_scratchdj_audio_DjEngine_nativeGetBufferFrames(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->BufferFrames() : 0;
}

}
