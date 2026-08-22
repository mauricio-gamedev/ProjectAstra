package io.github.astromg01.launcher.nativebridge

import android.content.res.Resources
import android.view.Surface

/**
 * Android-side bootstrap for the Astra-owned libpojavexec compatibility layer.
 *
 * The same native image is later discovered by the embedded OpenJDK/LWJGL
 * through System.loadLibrary("pojavexec"). Loading it here first gives the
 * bridge a retained ANativeWindow, renderer path, Android display density,
 * and a clean Android -> GLFW input queue before JLI starts.
 */
object AstraGlfwBridge {
    const val GLFW_KEY_ENTER = 257
    const val GLFW_KEY_TAB = 258
    const val GLFW_PRESS = 1
    const val GLFW_RELEASE = 0

    init {
        System.loadLibrary("pojavexec")
        configureAndroidDpi(Resources.getSystem().displayMetrics.densityDpi.toFloat())
    }

    external fun attachSurface(surface: Surface): String
    external fun configureRenderer(rendererLibraryPath: String): String
    external fun configureAndroidDpi(dpi: Float)
    external fun sendKeyEvent(key: Int, scancode: Int, action: Int, mods: Int): Boolean
    external fun sendKeyTap(key: Int, mods: Int = 0): Boolean
    external fun sendCursorPos(x: Double, y: Double): Boolean
    external fun sendMouseButton(button: Int, action: Int, mods: Int): Boolean
    external fun sendScroll(xOffset: Double, yOffset: Double): Boolean
    external fun releaseSurface()

    fun attach(surface: Surface): NativeProbeResult = parse { attachSurface(surface) }

    fun configure(rendererLibraryPath: String): NativeProbeResult =
        parse { configureRenderer(rendererLibraryPath) }

    fun tapTab(): Boolean = sendKeyTap(GLFW_KEY_TAB)

    fun tapEnter(): Boolean = sendKeyTap(GLFW_KEY_ENTER)

    private inline fun parse(block: () -> String): NativeProbeResult = runCatching {
        val raw = block()
        NativeProbeResult(
            success = raw.startsWith("OK|"),
            detail = raw.substringAfter('|', raw)
        )
    }.getOrElse { error ->
        NativeProbeResult(false, error.message ?: error.javaClass.simpleName)
    }
}
