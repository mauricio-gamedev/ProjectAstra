#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <cerrno>
#include <cstdlib>
#include <mutex>
#include <string>

namespace {

std::mutex gSurfaceMutex;
ANativeWindow* gSurfaceWindow = nullptr;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring fromString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void releaseSurfaceLocked() {
    if (gSurfaceWindow != nullptr) {
        ANativeWindow_release(gSurfaceWindow);
        gSurfaceWindow = nullptr;
    }
}

std::string surfaceDescription(ANativeWindow* window) {
    if (window == nullptr) return "nenhuma Surface anexada";
    const int width = ANativeWindow_getWidth(window);
    const int height = ANativeWindow_getHeight(window);
    const int format = ANativeWindow_getFormat(window);
    return "ANativeWindow " + std::to_string(width) + "x" + std::to_string(height) +
           " • format " + std::to_string(format);
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_probeLibrary(
        JNIEnv* env,
        jobject,
        jstring libraryPath,
        jstring requiredSymbol) {
    const std::string path = toString(env, libraryPath);
    const std::string symbol = toString(env, requiredSymbol);

    if (path.empty()) {
        return fromString(env, "ERROR|caminho da biblioteca vazio");
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        return fromString(env, std::string("ERROR|") + (error != nullptr ? error : "dlopen falhou"));
    }

    if (!symbol.empty()) {
        dlerror();
        void* address = dlsym(handle, symbol.c_str());
        const char* error = dlerror();
        if (address == nullptr || error != nullptr) {
            const std::string detail = error != nullptr ? error : "símbolo não encontrado";
            dlclose(handle);
            return fromString(env, "ERROR|" + detail);
        }
    }

    dlclose(handle);
    return fromString(env, "OK|biblioteca carregada");
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_setEnvironment(
        JNIEnv* env,
        jobject,
        jstring key,
        jstring value) {
    const std::string name = toString(env, key);
    const std::string data = toString(env, value);
    if (name.empty()) return EINVAL;
    return setenv(name.c_str(), data.c_str(), 1);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_attachSurface(
        JNIEnv* env,
        jobject,
        jobject surface) {
    if (surface == nullptr) {
        return fromString(env, "ERROR|Surface Android nula");
    }

    ANativeWindow* nextWindow = ANativeWindow_fromSurface(env, surface);
    if (nextWindow == nullptr) {
        return fromString(env, "ERROR|ANativeWindow_fromSurface falhou");
    }

    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    releaseSurfaceLocked();
    gSurfaceWindow = nextWindow;
    return fromString(env, "OK|" + surfaceDescription(gSurfaceWindow));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_surfaceStatus(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    return fromString(env, (gSurfaceWindow != nullptr ? "OK|" : "ERROR|") + surfaceDescription(gSurfaceWindow));
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_releaseSurface(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    releaseSurfaceLocked();
}
