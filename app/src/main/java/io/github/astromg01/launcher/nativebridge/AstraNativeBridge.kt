package io.github.astromg01.launcher.nativebridge

import android.view.Surface

object AstraNativeBridge {
    init {
        System.loadLibrary("astra_native")
    }

    external fun probeLibrary(libraryPath: String, requiredSymbol: String): String

    external fun setEnvironment(key: String, value: String): Int

    external fun attachSurface(surface: Surface): String

    external fun surfaceStatus(): String

    external fun startGraphicsTest(rendererLibraryPath: String): String

    external fun graphicsStatus(): String

    external fun shutdownGraphics()

    external fun releaseSurface()

    fun probe(libraryPath: String, requiredSymbol: String = ""): NativeProbeResult {
        return runCatching {
            val raw = probeLibrary(libraryPath, requiredSymbol)
            val ok = raw.startsWith("OK|")
            NativeProbeResult(
                success = ok,
                detail = raw.substringAfter('|', raw)
            )
        }.getOrElse { error ->
            NativeProbeResult(
                success = false,
                detail = error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun attach(surface: Surface): NativeProbeResult = parseNativeResult {
        attachSurface(surface)
    }

    fun surfaceProbe(): NativeProbeResult = parseNativeResult {
        surfaceStatus()
    }

    fun startGraphics(rendererLibraryPath: String): NativeProbeResult = parseNativeResult {
        startGraphicsTest(rendererLibraryPath)
    }

    fun graphicsProbe(): NativeProbeResult = parseNativeResult {
        graphicsStatus()
    }

    private inline fun parseNativeResult(block: () -> String): NativeProbeResult {
        return runCatching {
            val raw = block()
            NativeProbeResult(
                success = raw.startsWith("OK|"),
                detail = raw.substringAfter('|', raw)
            )
        }.getOrElse { error ->
            NativeProbeResult(false, error.message ?: error.javaClass.simpleName)
        }
    }
}

data class NativeProbeResult(
    val success: Boolean,
    val detail: String
)
