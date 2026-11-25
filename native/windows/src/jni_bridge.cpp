#include "engine.h"
#include "window_frame.h"

#include <jni.h>
#include <nlohmann/json.hpp>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

JavaVM* gJvm = nullptr;
std::mutex gEngineMu;
std::unique_ptr<square::NativeAudioEngine> gEngine;

std::mutex gCbMu;
jobject gCallbackObj = nullptr;
jmethodID gOnEnded = nullptr;
jmethodID gOnDeviceLost = nullptr;
jmethodID gOnSmtcButton = nullptr;
jmethodID gOnError = nullptr;

square::NativeAudioEngine* getEngine() {
    std::lock_guard<std::mutex> lock(gEngineMu);
    if (!gEngine) {
        gEngine = std::make_unique<square::NativeAudioEngine>();
    }
    return gEngine.get();
}

std::string jstringToStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* utf = env->GetStringUTFChars(s, nullptr);
    if (!utf) return {};
    std::string out(utf);
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

class JniEnvScope {
public:
    JniEnvScope() {
        if (!gJvm) return;
        const jint rc = gJvm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            if (gJvm->AttachCurrentThread(reinterpret_cast<void**>(&env_), nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        } else if (rc != JNI_OK) {
            env_ = nullptr;
        }
    }
    ~JniEnvScope() {
        if (attached_ && gJvm) {
            gJvm->DetachCurrentThread();
        }
    }
    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

void installEngineCallbacks(square::NativeAudioEngine* engine) {
    square::EngineCallbacks cb;
    cb.onEnded = [](int deck) {
        JniEnvScope scope;
        JNIEnv* env = scope.get();
        if (!env) return;
        std::lock_guard<std::mutex> lock(gCbMu);
        if (gCallbackObj && gOnEnded) {
            env->CallVoidMethod(gCallbackObj, gOnEnded, static_cast<jint>(deck));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    cb.onDeviceLost = [](const std::string& reason) {
        JniEnvScope scope;
        JNIEnv* env = scope.get();
        if (!env) return;
        std::lock_guard<std::mutex> lock(gCbMu);
        if (gCallbackObj && gOnDeviceLost) {
            jstring jReason = env->NewStringUTF(reason.c_str());
            env->CallVoidMethod(gCallbackObj, gOnDeviceLost, jReason);
            if (jReason) env->DeleteLocalRef(jReason);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    cb.onSmtcButton = [](const std::string& button) {
        JniEnvScope scope;
        JNIEnv* env = scope.get();
        if (!env) return;
        std::lock_guard<std::mutex> lock(gCbMu);
        if (gCallbackObj && gOnSmtcButton) {
            jstring jBtn = env->NewStringUTF(button.c_str());
            env->CallVoidMethod(gCallbackObj, gOnSmtcButton, jBtn);
            if (jBtn) env->DeleteLocalRef(jBtn);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    cb.onError = [](int deck, const std::string& message) {
        JniEnvScope scope;
        JNIEnv* env = scope.get();
        if (!env) return;
        std::lock_guard<std::mutex> lock(gCbMu);
        if (gCallbackObj && gOnError) {
            jstring jMsg = env->NewStringUTF(message.c_str());
            env->CallVoidMethod(gCallbackObj, gOnError, static_cast<jint>(deck), jMsg);
            if (jMsg) env->DeleteLocalRef(jMsg);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    engine->setCallbacks(std::move(cb));
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeInit(JNIEnv* env, jclass) {
    if (!gJvm) env->GetJavaVM(&gJvm);
    auto* eng = getEngine();
    installEngineCallbacks(eng);
    return eng->init(false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeShutdown(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(gEngineMu);
    if (gEngine) {
        gEngine->shutdown();
        gEngine.reset();
    }
    std::lock_guard<std::mutex> cbLock(gCbMu);
    if (gCallbackObj && env) {
        env->DeleteGlobalRef(gCallbackObj);
        gCallbackObj = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeDeckLoad(
    JNIEnv* env, jclass, jint deck, jstring uri, jstring headersJson, jlong startMs) {
    return getEngine()->deckLoad(
        static_cast<int>(deck),
        jstringToStd(env, uri),
        jstringToStd(env, headersJson),
        static_cast<int64_t>(startMs)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeDeckPlay(JNIEnv*, jclass, jint deck) {
    getEngine()->deckPlay(static_cast<int>(deck));
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeDeckPause(JNIEnv*, jclass, jint deck) {
    getEngine()->deckPause(static_cast<int>(deck));
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeDeckSeek(JNIEnv*, jclass, jint deck, jlong ms) {
    getEngine()->deckSeek(static_cast<int>(deck), static_cast<int64_t>(ms));
}

JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSetOutput(
    JNIEnv* env, jclass, jstring mode, jstring deviceId) {
    return getEngine()->setOutput(jstringToStd(env, mode), jstringToStd(env, deviceId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeListDevices(JNIEnv* env, jclass) {
    const std::string json = getEngine()->listDevicesJson();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSetDsp(JNIEnv* env, jclass, jstring json) {
    getEngine()->setDspJson(jstringToStd(env, json));
}

JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeScheduleMix(JNIEnv* env, jclass, jstring planJson) {
    return getEngine()->scheduleMixJson(jstringToStd(env, planJson)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSetSleepTimer(
    JNIEnv*, jclass, jlong ms, jlong fadeMs, jboolean endOfTrack) {
    getEngine()->setSleepTimer(static_cast<int64_t>(ms), static_cast<int64_t>(fadeMs), endOfTrack == JNI_TRUE);
}

JNIEXPORT jlong JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativePosition(JNIEnv*, jclass, jint deck) {
    return static_cast<jlong>(getEngine()->positionMs(static_cast<int>(deck)));
}

JNIEXPORT jstring JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeStatus(JNIEnv* env, jclass) {
    const std::string json = getEngine()->statusJson();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSmtcUpdate(
    JNIEnv* env, jclass, jstring json, jbyteArray artwork) {
    std::vector<uint8_t> bytes;
    if (artwork) {
        const jsize len = env->GetArrayLength(artwork);
        if (len > 0) {
            bytes.resize(static_cast<size_t>(len));
            env->GetByteArrayRegion(artwork, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
        }
    }
    getEngine()->smtcUpdate(jstringToStd(env, json), bytes);
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSetCallback(
    JNIEnv* env, jclass, jobject callback) {
    if (!gJvm) env->GetJavaVM(&gJvm);
    std::lock_guard<std::mutex> lock(gCbMu);
    if (gCallbackObj) {
        env->DeleteGlobalRef(gCallbackObj);
        gCallbackObj = nullptr;
    }
    gOnEnded = nullptr;
    gOnDeviceLost = nullptr;
    gOnSmtcButton = nullptr;
    gOnError = nullptr;

    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        gOnEnded = env->GetMethodID(cls, "onEnded", "(I)V");
        gOnDeviceLost = env->GetMethodID(cls, "onDeviceLost", "(Ljava/lang/String;)V");
        gOnSmtcButton = env->GetMethodID(cls, "onSmtcButton", "(Ljava/lang/String;)V");
        gOnError = env->GetMethodID(cls, "onError", "(ILjava/lang/String;)V");
        gCallbackObj = env->NewGlobalRef(callback);
        env->DeleteLocalRef(cls);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeProbeTrack(
    JNIEnv* env, jclass, jstring uri, jstring headersJson) {
    const std::string json = getEngine()->probeTrackJson(jstringToStd(env, uri), jstringToStd(env, headersJson));
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeDecodeToFloat(
    JNIEnv* env, jclass, jstring uri, jstring headersJson, jlong fromMs, jlong durationMs, jint targetRate, jint targetChannels) {
    const std::vector<float> samples = getEngine()->decodeToFloat(
        jstringToStd(env, uri),
        jstringToStd(env, headersJson),
        static_cast<int64_t>(fromMs),
        static_cast<int64_t>(durationMs),
        static_cast<int>(targetRate),
        static_cast<int>(targetChannels));
    if (samples.empty()) return nullptr;
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(samples.size()));
    if (!arr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(samples.size()), samples.data());
    return arr;
}

JNIEXPORT jdouble JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeMeasureTrackLufs(
    JNIEnv* env, jclass, jstring uri, jstring headersJson) {
    return static_cast<jdouble>(
        getEngine()->measureTrackLufs(jstringToStd(env, uri), jstringToStd(env, headersJson)));
}

JNIEXPORT jstring JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeSelfCheckRender(JNIEnv* env, jclass) {
    square::NativeAudioEngine temp;
    const auto rep = temp.runSelfCheck();
    nlohmann::json j = {
        {"ok", rep.ok},
        {"summary", rep.summary},
        {"durationSec", rep.analyzedDurationSec},
        {"lufs", rep.analyzedLufs},
    };
    const std::string s = j.dump();
    return env->NewStringUTF(s.c_str());
}

namespace {
square::FrameLayout parseFrameLayout(
    JNIEnv* env,
    jint captionHeightPx,
    jint resizeBorderPx,
    jint minWidthPx,
    jint minHeightPx,
    jintArray buttonRects,
    jintArray interactiveRects) {

    square::FrameLayout layout;
    layout.captionHeightPx = static_cast<int32_t>(captionHeightPx);
    layout.resizeBorderPx = static_cast<int32_t>(resizeBorderPx);
    layout.minWidthPx = static_cast<int32_t>(minWidthPx);
    layout.minHeightPx = static_cast<int32_t>(minHeightPx);

    if (buttonRects && env->GetArrayLength(buttonRects) >= 12) {
        jint buf[12]{};
        env->GetIntArrayRegion(buttonRects, 0, 12, buf);
        layout.minButton = {buf[0], buf[1], buf[2], buf[3]};
        layout.maxButton = {buf[4], buf[5], buf[6], buf[7]};
        layout.closeButton = {buf[8], buf[9], buf[10], buf[11]};
    }
    if (interactiveRects) {
        const jsize n = env->GetArrayLength(interactiveRects);
        if (n >= 4) {
            std::vector<jint> buf(static_cast<size_t>(n));
            env->GetIntArrayRegion(interactiveRects, 0, n, buf.data());
            for (jsize i = 0; i + 3 < n; i += 4) {
                layout.interactiveRects.push_back({
                    buf[static_cast<size_t>(i)],
                    buf[static_cast<size_t>(i + 1)],
                    buf[static_cast<size_t>(i + 2)],
                    buf[static_cast<size_t>(i + 3)],
                });
            }
        }
    }
    return layout;
}
} // namespace

JNIEXPORT jlong JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeGetWindowHwnd(
    JNIEnv* env, jclass, jobject awtWindow) {
    return static_cast<jlong>(square::hwndFromAwtWindow(env, awtWindow));
}

JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeInstallFrame(
    JNIEnv* env,
    jclass,
    jlong hwnd,
    jint captionHeightPx,
    jint resizeBorderPx,
    jint minWidthPx,
    jint minHeightPx,
    jintArray buttonRects,
    jintArray interactiveRects) {
    const auto layout = parseFrameLayout(
        env, captionHeightPx, resizeBorderPx, minWidthPx, minHeightPx, buttonRects, interactiveRects);
    return square::installWindowFrame(static_cast<intptr_t>(hwnd), layout) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeUpdateFrameLayout(
    JNIEnv* env,
    jclass,
    jlong hwnd,
    jint captionHeightPx,
    jint resizeBorderPx,
    jint minWidthPx,
    jint minHeightPx,
    jintArray buttonRects,
    jintArray interactiveRects) {
    const auto layout = parseFrameLayout(
        env, captionHeightPx, resizeBorderPx, minWidthPx, minHeightPx, buttonRects, interactiveRects);
    square::updateWindowFrame(static_cast<intptr_t>(hwnd), layout);
}

JNIEXPORT void JNICALL
Java_dev_lelonio_square_desktop_nativeaudio_NativeEngine_nativeUninstallFrame(
    JNIEnv*, jclass, jlong hwnd) {
    square::uninstallWindowFrame(static_cast<intptr_t>(hwnd));
}

} // extern "C"
