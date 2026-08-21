package io.github.astromg01.launcher.nativebridge

object AstraNativeBridge {
    init {
        System.loadLibrary("astra_native")
    }

    external fun probeLibrary(libraryPath: String, requiredSymbol: String): String

    external fun setEnvironment(key: String, value: String): Int

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
}

data class NativeProbeResult(
    val success: Boolean,
    val detail: String
)
