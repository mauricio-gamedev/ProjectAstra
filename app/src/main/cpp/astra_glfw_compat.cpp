#include <jni.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <mutex>

namespace {

std::mutex gCompatMutex;
jfloat gAndroidDpi = 160.0f;
std::array<unsigned char, 128> gDirectGamepadState{};

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraGlfwBridge_configureAndroidDpi(
        JNIEnv*, jobject, jfloat dpi) {
    std::lock_guard<std::mutex> lock(gCompatMutex);
    if (dpi > 0.0f) gAndroidDpi = dpi;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(JNIEnv*, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeGetAndroidDPI(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gCompatMutex);
    std::fprintf(stderr, "[Project Astra alpha18] Android DPI -> %.1f\n", gAndroidDpi);
    std::fflush(stderr);
    return gAndroidDpi;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher(
        JNIEnv*, jclass, jint action, jintArray) {
    std::fprintf(stderr, "[Project Astra alpha18] CallbackBridge notify action=%d\n", action);
    std::fflush(stderr);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(gDirectGamepadState.data()));
}

extern "C" JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput() {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeGetAndroidDPI() {
    std::lock_guard<std::mutex> lock(gCompatMutex);
    return gAndroidDpi;
}
