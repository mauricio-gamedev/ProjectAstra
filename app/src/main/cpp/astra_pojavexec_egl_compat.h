#pragma once

// Project Astra alpha38 parity shim for the Android LWJGL/GLFW bridge.
// MobileGlues exposes its desktop-OpenGL facade through the Android EGL
// OpenGL-ES API binding. The historical source used EGL_OPENGL_API here,
// while the device-proven alpha38 binary uses EGL_OPENGL_ES_API.
#include <EGL/egl.h>

#ifdef EGL_OPENGL_API
#undef EGL_OPENGL_API
#endif
#define EGL_OPENGL_API EGL_OPENGL_ES_API
