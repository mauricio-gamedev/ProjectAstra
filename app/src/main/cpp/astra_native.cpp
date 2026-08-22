#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <sstream>
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
using EglQueryStringFn = const char* (*)(EGLDisplay, EGLint);
using EglGetErrorFn = EGLint (*)();
using EglGetProcAddressFn = void* (*)(const char*);

using GlClearColorFn = void (*)(GLfloat, GLfloat, GLfloat, GLfloat);
using GlClearFn = void (*)(GLbitfield);
using GlViewportFn = void (*)(GLint, GLint, GLsizei, GLsizei);
using GlGetStringFn = const GLubyte* (*)(GLenum);
using GlGetErrorFn = GLenum (*)();

struct GraphicsApi {
    EglGetDisplayFn eglGetDisplay = nullptr;
    EglInitializeFn eglInitialize = nullptr;
    EglTerminateFn eglTerminate = nullptr;
    EglBindApiFn eglBindAPI = nullptr;
    EglChooseConfigFn eglChooseConfig = nullptr;
    EglGetConfigAttribFn eglGetConfigAttrib = nullptr;
    EglCreateWindowSurfaceFn eglCreateWindowSurface = nullptr;
    EglDestroySurfaceFn eglDestroySurface = nullptr;
    EglCreateContextFn eglCreateContext = nullptr;
    EglDestroyContextFn eglDestroyContext = nullptr;
    EglMakeCurrentFn eglMakeCurrent = nullptr;
    EglSwapIntervalFn eglSwapInterval = nullptr;
    EglSwapBuffersFn eglSwapBuffers = nullptr;
    EglQueryStringFn eglQueryString = nullptr;
    EglGetErrorFn eglGetError = nullptr;
    EglGetProcAddressFn eglGetProcAddress = nullptr;

    GlClearColorFn glClearColor = nullptr;
    GlClearFn glClear = nullptr;
    GlViewportFn glViewport = nullptr;
    GlGetStringFn glGetString = nullptr;
    GlGetErrorFn glGetError = nullptr;
};

std::mutex gSurfaceMutex;
ANativeWindow* gSurfaceWindow = nullptr;
void* gRendererHandle = nullptr;
std::string gRendererPath;
GraphicsApi gGraphicsApi;
EGLDisplay gEglDisplay = EGL_NO_DISPLAY;
EGLSurface gEglSurface = EGL_NO_SURFACE;
EGLContext gEglContext = EGL_NO_CONTEXT;
bool gEglInitialized = false;
std::string gGraphicsDescription;

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

std::string pointerString(const void* value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%p", value);
    return buffer;
}

std::string hexValue(unsigned int value) {
    char buffer[24];
    std::snprintf(buffer, sizeof(buffer), "0x%04X", value);
    return buffer;
}

std::string safeString(const char* value, const char* fallback = "desconhecido") {
    return value != nullptr && *value != '\0' ? value : fallback;
}

template <typename T>
T rendererSymbol(const char* name) {
    if (gRendererHandle == nullptr) return nullptr;
    dlerror();
    void* symbol = dlsym(gRendererHandle, name);
    return reinterpret_cast<T>(symbol);
}

template <typename T>
T glSymbol(const char* name) {
    if (T direct = rendererSymbol<T>(name); direct != nullptr) return direct;
    if (gGraphicsApi.eglGetProcAddress == nullptr) return nullptr;
    return reinterpret_cast<T>(gGraphicsApi.eglGetProcAddress(name));
}

EGLint currentEglError() {
    return gGraphicsApi.eglGetError != nullptr ? gGraphicsApi.eglGetError() : EGL_SUCCESS;
}

void resetGraphicsHandles() {
    gGraphicsApi = GraphicsApi{};
    gEglDisplay = EGL_NO_DISPLAY;
    gEglSurface = EGL_NO_SURFACE;
    gEglContext = EGL_NO_CONTEXT;
    gEglInitialized = false;
    gGraphicsDescription.clear();
}

void shutdownGraphicsLocked() {
    if (gEglDisplay != EGL_NO_DISPLAY && gGraphicsApi.eglMakeCurrent != nullptr) {
        gGraphicsApi.eglMakeCurrent(gEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (gEglDisplay != EGL_NO_DISPLAY && gEglContext != EGL_NO_CONTEXT &&
        gGraphicsApi.eglDestroyContext != nullptr) {
        gGraphicsApi.eglDestroyContext(gEglDisplay, gEglContext);
    }
    if (gEglDisplay != EGL_NO_DISPLAY && gEglSurface != EGL_NO_SURFACE &&
        gGraphicsApi.eglDestroySurface != nullptr) {
        gGraphicsApi.eglDestroySurface(gEglDisplay, gEglSurface);
    }
    if (gEglDisplay != EGL_NO_DISPLAY && gEglInitialized && gGraphicsApi.eglTerminate != nullptr) {
        gGraphicsApi.eglTerminate(gEglDisplay);
    }

    // MobileGlues performs one-time process initialisation when dlopen() first loads it.
    // Keep that image resident and only destroy EGL objects between Surface lifecycles.
    // Repeated dlclose/dlopen cycles are deliberately avoided.
    resetGraphicsHandles();
}

void releaseSurfaceLocked() {
    shutdownGraphicsLocked();
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

bool resolveEglApi(std::string* missing) {
#define RESOLVE_EGL(field, type, symbolName)                                      \
    do {                                                                           \
        gGraphicsApi.field = rendererSymbol<type>(symbolName);                     \
        if (gGraphicsApi.field == nullptr) {                                       \
            *missing = symbolName;                                                 \
            return false;                                                          \
        }                                                                          \
    } while (0)

    RESOLVE_EGL(eglGetDisplay, EglGetDisplayFn, "eglGetDisplay");
    RESOLVE_EGL(eglInitialize, EglInitializeFn, "eglInitialize");
    RESOLVE_EGL(eglTerminate, EglTerminateFn, "eglTerminate");
    RESOLVE_EGL(eglBindAPI, EglBindApiFn, "eglBindAPI");
    RESOLVE_EGL(eglChooseConfig, EglChooseConfigFn, "eglChooseConfig");
    RESOLVE_EGL(eglGetConfigAttrib, EglGetConfigAttribFn, "eglGetConfigAttrib");
    RESOLVE_EGL(eglCreateWindowSurface, EglCreateWindowSurfaceFn, "eglCreateWindowSurface");
    RESOLVE_EGL(eglDestroySurface, EglDestroySurfaceFn, "eglDestroySurface");
    RESOLVE_EGL(eglCreateContext, EglCreateContextFn, "eglCreateContext");
    RESOLVE_EGL(eglDestroyContext, EglDestroyContextFn, "eglDestroyContext");
    RESOLVE_EGL(eglMakeCurrent, EglMakeCurrentFn, "eglMakeCurrent");
    RESOLVE_EGL(eglSwapInterval, EglSwapIntervalFn, "eglSwapInterval");
    RESOLVE_EGL(eglSwapBuffers, EglSwapBuffersFn, "eglSwapBuffers");
    RESOLVE_EGL(eglQueryString, EglQueryStringFn, "eglQueryString");
    RESOLVE_EGL(eglGetError, EglGetErrorFn, "eglGetError");
    RESOLVE_EGL(eglGetProcAddress, EglGetProcAddressFn, "eglGetProcAddress");
#undef RESOLVE_EGL
    return true;
}

bool resolveGlApi(std::string* missing) {
#define RESOLVE_GL(field, type, symbolName)                                        \
    do {                                                                           \
        gGraphicsApi.field = glSymbol<type>(symbolName);                           \
        if (gGraphicsApi.field == nullptr) {                                       \
            *missing = symbolName;                                                 \
            return false;                                                          \
        }                                                                          \
    } while (0)

    RESOLVE_GL(glClearColor, GlClearColorFn, "glClearColor");
    RESOLVE_GL(glClear, GlClearFn, "glClear");
    RESOLVE_GL(glViewport, GlViewportFn, "glViewport");
    RESOLVE_GL(glGetString, GlGetStringFn, "glGetString");
    RESOLVE_GL(glGetError, GlGetErrorFn, "glGetError");
#undef RESOLVE_GL
    return true;
}

std::string failGraphicsLocked(const std::string& stage, bool includeEglError = true) {
    const EGLint error = includeEglError ? currentEglError() : EGL_SUCCESS;
    const std::string detail = includeEglError && error != EGL_SUCCESS
        ? stage + " • EGL " + hexValue(static_cast<unsigned int>(error))
        : stage;
    shutdownGraphicsLocked();
    return "ERROR|" + detail;
}

std::string startGraphicsLocked(const std::string& rendererPath) {
    if (gSurfaceWindow == nullptr) return "ERROR|ANativeWindow ainda não está anexada";
    if (rendererPath.empty()) return "ERROR|caminho do MobileGlues vazio";

    shutdownGraphicsLocked();

    if (gRendererHandle == nullptr) {
        dlerror();
        gRendererHandle = dlopen(rendererPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (gRendererHandle == nullptr) {
            const char* error = dlerror();
            return "ERROR|dlopen MobileGlues falhou: " +
                   std::string(error != nullptr ? error : "erro desconhecido");
        }
        gRendererPath = rendererPath;
    } else if (gRendererPath != rendererPath) {
        return "ERROR|outro renderer já está residente neste processo";
    }

    std::string missing;
    if (!resolveEglApi(&missing)) {
        return failGraphicsLocked("MobileGlues não exportou " + missing, false);
    }

    EGLint eglMajor = 0;
    EGLint eglMinor = 0;
    gEglDisplay = gGraphicsApi.eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gEglDisplay == EGL_NO_DISPLAY) return failGraphicsLocked("eglGetDisplay falhou");
    if (gGraphicsApi.eglInitialize(gEglDisplay, &eglMajor, &eglMinor) != EGL_TRUE) {
        return failGraphicsLocked("eglInitialize falhou");
    }
    gEglInitialized = true;

    // Device-proven alpha38 behavior. MobileGlues exposes the desktop-GL facade
    // through an EGL/OpenGL-ES binding on Android; using EGL_OPENGL_API regresses
    // to the pre-alpha29 path.
    if (gGraphicsApi.eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        return failGraphicsLocked("eglBindAPI(OpenGL ES) falhou");
    }

    const EGLint primaryConfig[] = {
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
    const EGLint fallbackConfig[] = {
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE
    };

    EGLConfig config = nullptr;
    EGLint configCount = 0;
    bool fallbackConfigUsed = false;
    if (gGraphicsApi.eglChooseConfig(gEglDisplay, primaryConfig, &config, 1, &configCount) != EGL_TRUE ||
        configCount <= 0 || config == nullptr) {
        config = nullptr;
        configCount = 0;
        fallbackConfigUsed = true;
        if (gGraphicsApi.eglChooseConfig(gEglDisplay, fallbackConfig, &config, 1, &configCount) != EGL_TRUE ||
            configCount <= 0 || config == nullptr) {
            return failGraphicsLocked("nenhuma EGLConfig de janela/OpenGL foi aceita");
        }
    }

    EGLint nativeVisualId = 0;
    if (gGraphicsApi.eglGetConfigAttrib(gEglDisplay, config, EGL_NATIVE_VISUAL_ID, &nativeVisualId) == EGL_TRUE &&
        nativeVisualId != 0) {
        ANativeWindow_setBuffersGeometry(gSurfaceWindow, 0, 0, nativeVisualId);
    }

    gEglSurface = gGraphicsApi.eglCreateWindowSurface(gEglDisplay, config, gSurfaceWindow, nullptr);
    if (gEglSurface == EGL_NO_SURFACE) {
        return failGraphicsLocked("eglCreateWindowSurface falhou");
    }

    const EGLint core32Context[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION_KHR, 2,
        EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
        EGL_NONE
    };
    const EGLint fallback30Context[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    bool fallbackContextUsed = false;
    gEglContext = gGraphicsApi.eglCreateContext(gEglDisplay, config, EGL_NO_CONTEXT, core32Context);
    if (gEglContext == EGL_NO_CONTEXT) {
        // Consume the first failure before retrying so diagnostics from the fallback are meaningful.
        currentEglError();
        fallbackContextUsed = true;
        gEglContext = gGraphicsApi.eglCreateContext(gEglDisplay, config, EGL_NO_CONTEXT, fallback30Context);
        if (gEglContext == EGL_NO_CONTEXT) {
            return failGraphicsLocked("eglCreateContext 3.2 Core/3.0 fallback falhou");
        }
    }

    if (gGraphicsApi.eglMakeCurrent(gEglDisplay, gEglSurface, gEglSurface, gEglContext) != EGL_TRUE) {
        return failGraphicsLocked("eglMakeCurrent falhou");
    }

    if (!resolveGlApi(&missing)) {
        return failGraphicsLocked("MobileGlues não resolveu " + missing, false);
    }

    const int width = ANativeWindow_getWidth(gSurfaceWindow);
    const int height = ANativeWindow_getHeight(gSurfaceWindow);
    gGraphicsApi.glViewport(0, 0, width, height);
    gGraphicsApi.glClearColor(0.08f, 0.48f, 0.28f, 1.0f);
    gGraphicsApi.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    const GLenum glError = gGraphicsApi.glGetError();
    if (glError != GL_NO_ERROR) {
        return failGraphicsLocked("glClear retornou GL " + hexValue(glError), false);
    }

    gGraphicsApi.eglSwapInterval(gEglDisplay, 1);
    if (gGraphicsApi.eglSwapBuffers(gEglDisplay, gEglSurface) != EGL_TRUE) {
        return failGraphicsLocked("eglSwapBuffers falhou");
    }

    const char* eglVendor = gGraphicsApi.eglQueryString(gEglDisplay, EGL_VENDOR);
    const char* eglVersion = gGraphicsApi.eglQueryString(gEglDisplay, EGL_VERSION);
    const char* glVendor = reinterpret_cast<const char*>(gGraphicsApi.glGetString(GL_VENDOR));
    const char* glRenderer = reinterpret_cast<const char*>(gGraphicsApi.glGetString(GL_RENDERER));
    const char* glVersion = reinterpret_cast<const char*>(gGraphicsApi.glGetString(GL_VERSION));
    // alpha38 deliberately disabled the GLSL glGetString path with an invalid enum
    // in its binary patch. In clean source, skip the diagnostic call altogether.
    const char* glslVersion = nullptr;

    std::ostringstream description;
    description << "MobileGlues EGL ativo ✓\n";
    description << "EGL " << eglMajor << '.' << eglMinor << " • " << safeString(eglVendor) << " • "
                << safeString(eglVersion) << "\n";
    description << "Contexto: " << (fallbackContextUsed ? "OpenGL 3.0 fallback" : "OpenGL 3.2 Core")
                << (fallbackConfigUsed ? " • EGLConfig fallback" : "") << "\n";
    description << "GL_VENDOR: " << safeString(glVendor) << "\n";
    description << "GL_RENDERER: " << safeString(glRenderer) << "\n";
    description << "GL_VERSION: " << safeString(glVersion) << "\n";
    description << "GLSL: " << safeString(glslVersion, "não consultado") << "\n";
    description << "SwapBuffers ✓ • visual " << nativeVisualId << " • " << width << 'x' << height;
    gGraphicsDescription = description.str();
    return "OK|" + gGraphicsDescription;
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
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_startGraphicsTest(
        JNIEnv* env,
        jobject,
        jstring rendererLibraryPath) {
    const std::string rendererPath = toString(env, rendererLibraryPath);
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    return fromString(env, startGraphicsLocked(rendererPath));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_graphicsStatus(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    if (gEglContext == EGL_NO_CONTEXT || gEglSurface == EGL_NO_SURFACE || gGraphicsDescription.empty()) {
        return fromString(env, "ERROR|contexto EGL/MobileGlues não está ativo");
    }
    return fromString(env, "OK|" + gGraphicsDescription);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_shutdownGraphics(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    shutdownGraphicsLocked();
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_releaseSurface(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gSurfaceMutex);
    releaseSurfaceLocked();
}
