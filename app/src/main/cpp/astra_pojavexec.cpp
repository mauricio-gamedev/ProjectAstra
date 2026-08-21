#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>

#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#endif
#ifndef EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR 0x00000001
#endif

namespace {

using EglGetDisplayFn = EGLDisplay (*)(EGLNativeDisplayType);
using EglInitializeFn = EGLBoolean (*)(EGLDisplay, EGLint*, EGLint*);
using EglTerminateFn = EGLBoolean (*)(EGLDisplay);
using EglBindApiFn = EGLBoolean (*)(EGLenum);
using EglChooseConfigFn = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
using EglGetConfigAttribFn = EGLBoolean (*)(EGLDisplay, EGLConfig, EGLint, EGLint*);
using EglCreateWindowSurfaceFn = EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
using EglDestroySurfaceFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using EglCreateContextFn = EGLContext (*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
using EglDestroyContextFn = EGLBoolean (*)(EGLDisplay, EGLContext);
using EglMakeCurrentFn = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
using EglSwapIntervalFn = EGLBoolean (*)(EGLDisplay, EGLint);
using EglSwapBuffersFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using EglGetErrorFn = EGLint (*)();

struct EglApi {
    EglGetDisplayFn getDisplay = nullptr;
    EglInitializeFn initialize = nullptr;
    EglTerminateFn terminate = nullptr;
    EglBindApiFn bindApi = nullptr;
    EglChooseConfigFn chooseConfig = nullptr;
    EglGetConfigAttribFn getConfigAttrib = nullptr;
    EglCreateWindowSurfaceFn createWindowSurface = nullptr;
    EglDestroySurfaceFn destroySurface = nullptr;
    EglCreateContextFn createContext = nullptr;
    EglDestroyContextFn destroyContext = nullptr;
    EglMakeCurrentFn makeCurrent = nullptr;
    EglSwapIntervalFn swapInterval = nullptr;
    EglSwapBuffersFn swapBuffers = nullptr;
    EglGetErrorFn getError = nullptr;
};

std::mutex gMutex;
ANativeWindow* gWindow = nullptr;
std::string gRendererPath;
void* gRendererHandle = nullptr;
EglApi gEgl;
EGLDisplay gDisplay = EGL_NO_DISPLAY;
EGLSurface gSurface = EGL_NO_SURFACE;
EGLContext gContext = EGL_NO_CONTEXT;
bool gInitialized = false;
int gClientApi = 0x00030001; // GLFW_OPENGL_API

double gCursorX = 0.0;
double gCursorY = 0.0;
std::array<jbyte, 32> gGamepadButtons{};
std::array<jfloat, 16> gGamepadAxes{};

struct CallbackState {
    jlong character = 0;
    jlong characterMods = 0;
    jlong cursorEnter = 0;
    jlong cursorPos = 0;
    jlong framebufferSize = 0;
    jlong key = 0;
    jlong mouseButton = 0;
    jlong scroll = 0;
    jlong windowSize = 0;
} gCallbacks;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring resultString(JNIEnv* env, bool ok, const std::string& detail) {
    return env->NewStringUTF(((ok ? "OK|" : "ERROR|") + detail).c_str());
}

template <typename T>
T resolve(const char* name) {
    if (gRendererHandle == nullptr) return nullptr;
    return reinterpret_cast<T>(dlsym(gRendererHandle, name));
}

void destroyContextLocked() {
    if (gDisplay != EGL_NO_DISPLAY && gEgl.makeCurrent != nullptr) {
        gEgl.makeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (gDisplay != EGL_NO_DISPLAY && gContext != EGL_NO_CONTEXT && gEgl.destroyContext != nullptr) {
        gEgl.destroyContext(gDisplay, gContext);
    }
    if (gDisplay != EGL_NO_DISPLAY && gSurface != EGL_NO_SURFACE && gEgl.destroySurface != nullptr) {
        gEgl.destroySurface(gDisplay, gSurface);
    }
    if (gDisplay != EGL_NO_DISPLAY && gInitialized && gEgl.terminate != nullptr) {
        gEgl.terminate(gDisplay);
    }
    gDisplay = EGL_NO_DISPLAY;
    gSurface = EGL_NO_SURFACE;
    gContext = EGL_NO_CONTEXT;
    gInitialized = false;
    gEgl = EglApi{};
}

bool loadRendererLocked(std::string& error) {
    if (gRendererPath.empty()) {
        error = "MobileGlues não configurado antes do GLFW";
        return false;
    }
    if (gRendererHandle == nullptr) {
        dlerror();
        gRendererHandle = dlopen(gRendererPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (gRendererHandle == nullptr) {
            const char* detail = dlerror();
            error = "dlopen MobileGlues: " + std::string(detail != nullptr ? detail : "falhou");
            return false;
        }
    }

#define EGL_SYM(field, type, name) \
    do { \
        gEgl.field = resolve<type>(name); \
        if (gEgl.field == nullptr) { error = std::string("MobileGlues sem ") + name; return false; } \
    } while (0)
    EGL_SYM(getDisplay, EglGetDisplayFn, "eglGetDisplay");
    EGL_SYM(initialize, EglInitializeFn, "eglInitialize");
    EGL_SYM(terminate, EglTerminateFn, "eglTerminate");
    EGL_SYM(bindApi, EglBindApiFn, "eglBindAPI");
    EGL_SYM(chooseConfig, EglChooseConfigFn, "eglChooseConfig");
    EGL_SYM(getConfigAttrib, EglGetConfigAttribFn, "eglGetConfigAttrib");
    EGL_SYM(createWindowSurface, EglCreateWindowSurfaceFn, "eglCreateWindowSurface");
    EGL_SYM(destroySurface, EglDestroySurfaceFn, "eglDestroySurface");
    EGL_SYM(createContext, EglCreateContextFn, "eglCreateContext");
    EGL_SYM(destroyContext, EglDestroyContextFn, "eglDestroyContext");
    EGL_SYM(makeCurrent, EglMakeCurrentFn, "eglMakeCurrent");
    EGL_SYM(swapInterval, EglSwapIntervalFn, "eglSwapInterval");
    EGL_SYM(swapBuffers, EglSwapBuffersFn, "eglSwapBuffers");
    EGL_SYM(getError, EglGetErrorFn, "eglGetError");
#undef EGL_SYM
    return true;
}

bool createContextLocked(std::string& error) {
    if (gContext != EGL_NO_CONTEXT) return true;
    if (gWindow == nullptr) {
        error = "ANativeWindow do Astra não foi anexada ao pojavexec";
        return false;
    }
    if (gClientApi == 0) {
        error = "GLFW_NO_API/Vulkan ainda não está implementado na bridge alpha17";
        return false;
    }
    if (!loadRendererLocked(error)) return false;

    EGLint major = 0;
    EGLint minor = 0;
    gDisplay = gEgl.getDisplay(EGL_DEFAULT_DISPLAY);
    if (gDisplay == EGL_NO_DISPLAY || gEgl.initialize(gDisplay, &major, &minor) != EGL_TRUE) {
        error = "eglInitialize falhou";
        return false;
    }
    gInitialized = true;
    if (gEgl.bindApi(EGL_OPENGL_API) != EGL_TRUE) {
        error = "eglBindAPI(OpenGL) falhou";
        destroyContextLocked();
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (gEgl.chooseConfig(gDisplay, configAttribs, &config, 1, &configCount) != EGL_TRUE || configCount < 1) {
        error = "nenhuma EGLConfig OpenGL/window aceita";
        destroyContextLocked();
        return false;
    }

    EGLint visual = 0;
    if (gEgl.getConfigAttrib(gDisplay, config, EGL_NATIVE_VISUAL_ID, &visual) == EGL_TRUE && visual != 0) {
        ANativeWindow_setBuffersGeometry(gWindow, 0, 0, visual);
    }
    gSurface = gEgl.createWindowSurface(gDisplay, config, gWindow, nullptr);
    if (gSurface == EGL_NO_SURFACE) {
        error = "eglCreateWindowSurface falhou";
        destroyContextLocked();
        return false;
    }

    const EGLint core32[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION_KHR, 2,
        EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
        EGL_NONE
    };
    const EGLint fallback30[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    gContext = gEgl.createContext(gDisplay, config, EGL_NO_CONTEXT, core32);
    if (gContext == EGL_NO_CONTEXT) {
        if (gEgl.getError != nullptr) gEgl.getError();
        gContext = gEgl.createContext(gDisplay, config, EGL_NO_CONTEXT, fallback30);
    }
    if (gContext == EGL_NO_CONTEXT) {
        error = "eglCreateContext 3.2/3.0 falhou";
        destroyContextLocked();
        return false;
    }
    if (gEgl.makeCurrent(gDisplay, gSurface, gSurface, gContext) != EGL_TRUE) {
        error = "eglMakeCurrent falhou";
        destroyContextLocked();
        return false;
    }

    std::fprintf(stderr,
        "[Project Astra alpha17] GLFW bridge context ready: %dx%d EGL %d.%d context=%p\n",
        ANativeWindow_getWidth(gWindow), ANativeWindow_getHeight(gWindow), major, minor, gContext);
    std::fflush(stderr);
    return true;
}

jlong setCallback(jlong& slot, jlong next) {
    const jlong old = slot;
    slot = next;
    return old;
}

} // namespace

extern "C" jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

// ---- Android/Dalvik-side setup used by Project Astra before JLI ----
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraGlfwBridge_attachSurface(
        JNIEnv* env, jobject, jobject surface) {
    if (surface == nullptr) return resultString(env, false, "Surface nula");
    ANativeWindow* next = ANativeWindow_fromSurface(env, surface);
    if (next == nullptr) return resultString(env, false, "ANativeWindow_fromSurface falhou");

    std::lock_guard<std::mutex> lock(gMutex);
    destroyContextLocked();
    if (gWindow != nullptr) ANativeWindow_release(gWindow);
    gWindow = next;
    return resultString(env, true,
        "pojavexec recebeu ANativeWindow " + std::to_string(ANativeWindow_getWidth(gWindow)) + "x" +
        std::to_string(ANativeWindow_getHeight(gWindow)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraGlfwBridge_configureRenderer(
        JNIEnv* env, jobject, jstring rendererPath) {
    const std::string path = toString(env, rendererPath);
    if (path.empty()) return resultString(env, false, "caminho do renderer vazio");
    std::lock_guard<std::mutex> lock(gMutex);
    gRendererPath = path;
    return resultString(env, true, "MobileGlues preparado para GLFW");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraGlfwBridge_releaseSurface(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    destroyContextLocked();
    if (gWindow != nullptr) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
}

// ---- Function-provider ABI expected by the Android LWJGL GLFW shim ----
extern "C" __attribute__((visibility("default"))) int pojavInit() {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gWindow == nullptr || gRendererPath.empty()) {
        std::fprintf(stderr, "[Project Astra alpha17] pojavInit: Surface/renderer ainda não configurados\n");
        return 0;
    }
    std::fprintf(stderr, "[Project Astra alpha17] pojavInit OK\n");
    return 1;
}

extern "C" __attribute__((visibility("default"))) void pojavTerminate() {
    std::lock_guard<std::mutex> lock(gMutex);
    destroyContextLocked();
}

extern "C" __attribute__((visibility("default"))) void* pojavGetCurrentContext() {
    std::lock_guard<std::mutex> lock(gMutex);
    return reinterpret_cast<void*>(gContext);
}

extern "C" __attribute__((visibility("default"))) void pojavSetWindowHint(int hint, int value) {
    constexpr int GLFW_CLIENT_API = 0x00022001;
    if (hint == GLFW_CLIENT_API) gClientApi = value;
}

extern "C" __attribute__((visibility("default"))) void* pojavCreateContext(void*) {
    std::lock_guard<std::mutex> lock(gMutex);
    std::string error;
    if (!createContextLocked(error)) {
        std::fprintf(stderr, "[Project Astra alpha17] pojavCreateContext FAILED: %s\n", error.c_str());
        std::fflush(stderr);
        return nullptr;
    }
    return reinterpret_cast<void*>(gContext);
}

extern "C" __attribute__((visibility("default"))) void pojavMakeCurrent(void* window) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gEgl.makeCurrent == nullptr || gDisplay == EGL_NO_DISPLAY) return;
    if (window == nullptr) {
        gEgl.makeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    } else if (gContext != EGL_NO_CONTEXT && gSurface != EGL_NO_SURFACE) {
        gEgl.makeCurrent(gDisplay, gSurface, gSurface, gContext);
    }
}

extern "C" __attribute__((visibility("default"))) void pojavSwapBuffers(void*) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gEgl.swapBuffers != nullptr && gDisplay != EGL_NO_DISPLAY && gSurface != EGL_NO_SURFACE) {
        gEgl.swapBuffers(gDisplay, gSurface);
    }
}

extern "C" __attribute__((visibility("default"))) void pojavSwapInterval(int interval) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gEgl.swapInterval != nullptr && gDisplay != EGL_NO_DISPLAY) gEgl.swapInterval(gDisplay, interval);
}

extern "C" __attribute__((visibility("default"))) void pojavStartPumping() {}
extern "C" __attribute__((visibility("default"))) void pojavStopPumping() {}
extern "C" __attribute__((visibility("default"))) void pojavPumpEvents(void*) {}

// ---- JNI compatibility surface used by the patched GLFW classes ----
extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nativeInitializeGLFWNativeBridge(JNIEnv*, jclass) {
    std::fprintf(stderr, "[Project Astra alpha17] GLFW JNI bridge initialized\n");
}

#define CALLBACK_SETTER(NAME, FIELD) \
extern "C" JNIEXPORT jlong JNICALL \
Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv*, jclass, jlong, jlong callback) { \
    return setCallback(gCallbacks.FIELD, callback); \
}
CALLBACK_SETTER(Char, character)
CALLBACK_SETTER(CharMods, characterMods)
CALLBACK_SETTER(CursorEnter, cursorEnter)
CALLBACK_SETTER(CursorPos, cursorPos)
CALLBACK_SETTER(FramebufferSize, framebufferSize)
CALLBACK_SETTER(Key, key)
CALLBACK_SETTER(MouseButton, mouseButton)
CALLBACK_SETTER(Scroll, scroll)
CALLBACK_SETTER(WindowSize, windowSize)
#undef CALLBACK_SETTER

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv*, jclass, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv* env, jclass, jlong, jobject xpos, jobject ypos) {
    if (xpos != nullptr) {
        auto* ptr = static_cast<jdouble*>(env->GetDirectBufferAddress(xpos));
        if (ptr != nullptr) *ptr = gCursorX;
    }
    if (ypos != nullptr) {
        auto* ptr = static_cast<jdouble*>(env->GetDirectBufferAddress(ypos));
        if (ptr != nullptr) *ptr = gCursorY;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv* env, jclass, jlong, jdoubleArray xpos, jdoubleArray ypos) {
    if (xpos != nullptr) env->SetDoubleArrayRegion(xpos, 0, 1, &gCursorX);
    if (ypos != nullptr) env->SetDoubleArrayRegion(ypos, 0, 1, &gCursorY);
}

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(JNIEnv*, jclass, jlong, jdouble xpos, jdouble ypos) {
    gCursorX = xpos;
    gCursorY = ypos;
}

extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(jlong, jdouble xpos, jdouble ypos) {
    gCursorX = xpos;
    gCursorY = ypos;
}

extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(jlong, jint, jdouble* xpos, jint, jdouble* ypos) {
    if (xpos != nullptr) *xpos = gCursorX;
    if (ypos != nullptr) *ypos = gCursorY;
}

// Minimal CallbackBridge implementation for the current boot milestone.
extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendData(JNIEnv*, jclass, jboolean, jint, jstring) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(JNIEnv*, jclass, jboolean) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, jclass, jint, jbyteArray) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv* env, jclass) {
    return env->NewDirectByteBuffer(gGamepadButtons.data(), static_cast<jlong>(gGamepadButtons.size()));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv* env, jclass) {
    return env->NewDirectByteBuffer(gGamepadAxes.data(), static_cast<jlong>(gGamepadAxes.size() * sizeof(jfloat)));
}

extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetUseInputStackQueue(JNIEnv*, jclass, jboolean) {}
extern "C" JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendChar(jchar) { return JNI_TRUE; }
extern "C" JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendCharMods(jchar, jint) { return JNI_TRUE; }
extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendKey(jint, jint, jint, jint) {}
extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos(jfloat x, jfloat y) { gCursorX = x; gCursorY = y; }
extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendMouseButton(jint, jint, jint) {}
extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendScroll(jdouble, jdouble) {}
extern "C" JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendScreenSize(jint, jint) {}
extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(JNIEnv*, jclass, jint, jint) {}
