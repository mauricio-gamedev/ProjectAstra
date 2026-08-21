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

    external fun launchJavaVersionTest(
        jliLibraryPath: String,
        javaExecutable: String,
        workingDirectory: String,
        logPath: String
    ): String

    external fun launchMinecraftPlan(
        jliLibraryPath: String,
        javaExecutable: String,
        javaHome: String,
        workingDirectory: String,
        logPath: String,
        jvmArguments: Array<String>,
        mainClass: String,
        gameArguments: Array<String>
    ): String

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

    fun attach(surface: Surface): NativeProbeResult {
        val astra = parseNativeResult { attachSurface(surface) }
        if (!astra.success) return astra

        val glfw = AstraGlfwBridge.attach(surface)
        if (!glfw.success) {
            return NativeProbeResult(false, "ANativeWindow Astra OK; GLFW bridge falhou: ${glfw.detail}")
        }
        return NativeProbeResult(true, "${astra.detail} • GLFW bridge anexada")
    }

    fun surfaceProbe(): NativeProbeResult = parseNativeResult {
        surfaceStatus()
    }

    fun startGraphics(rendererLibraryPath: String): NativeProbeResult {
        val glfw = AstraGlfwBridge.configure(rendererLibraryPath)
        if (!glfw.success) {
            return NativeProbeResult(false, "Configuração GLFW/MobileGlues falhou: ${glfw.detail}")
        }
        return parseNativeResult { startGraphicsTest(rendererLibraryPath) }
    }

    fun graphicsProbe(): NativeProbeResult = parseNativeResult {
        graphicsStatus()
    }

    fun testJavaVersion(
        jliLibraryPath: String,
        javaExecutable: String,
        workingDirectory: String,
        logPath: String
    ): NativeProbeResult = parseNativeResult {
        launchJavaVersionTest(jliLibraryPath, javaExecutable, workingDirectory, logPath)
    }

    fun launchMinecraft(
        jliLibraryPath: String,
        javaExecutable: String,
        javaHome: String,
        workingDirectory: String,
        logPath: String,
        jvmArguments: List<String>,
        mainClass: String,
        gameArguments: List<String>
    ): NativeProbeResult = parseNativeResult {
        launchMinecraftPlan(
            jliLibraryPath = jliLibraryPath,
            javaExecutable = javaExecutable,
            javaHome = javaHome,
            workingDirectory = workingDirectory,
            logPath = logPath,
            jvmArguments = jvmArguments.toTypedArray(),
            mainClass = mainClass,
            gameArguments = gameArguments.toTypedArray()
        )
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
