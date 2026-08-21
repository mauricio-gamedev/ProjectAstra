package io.github.astromg01.launcher.nativebridge

import android.content.res.Resources
import android.view.Surface

/**
 * Android-side bootstrap for the Astra-owned libpojavexec compatibility layer.
 *
 * The same native image is later discovered by the embedded OpenJDK/LWJGL
 * through System.loadLibrary("pojavexec"). Loading it here first gives the
 * bridge a retained ANativeWindow, renderer path, and Android display density
 * before JLI starts.
 */
object AstraGlfwBridge {
    init {
        System.loadLibrary("pojavexec")
        configureAndroidDpi(Resources.getSystem().displayMetrics.densityDpi.toFloat())
    }

    external fun attachSurface(surface: Surface): String
    external fun configureRenderer(rendererLibraryPath: String): String
    external fun configureAndroidDpi(dpi: Float)
    external fun releaseSurface()

    fun attach(surface: Surface): NativeProbeResult = parse { attachSurface(surface) }

    fun configure(rendererLibraryPath: String): NativeProbeResult =
        parse { configureRenderer(rendererLibraryPath) }

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
