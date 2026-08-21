package io.github.astromg01.launcher.core

enum class PerformanceMode {
    PERFORMANCE,
    BALANCED,
    QUALITY,
    ADAPTIVE
}

enum class RendererKind {
    AUTO,
    MOBILEGLUES,
    GL4ES,
    ANGLE,
    ZINK
}

data class MinecraftInstance(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val loader: String? = null,
    val accountId: String? = null,
    val performanceMode: PerformanceMode = PerformanceMode.ADAPTIVE,
    val renderer: RendererKind = RendererKind.AUTO,
    val memoryMb: Int = 2048
)

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
    val availableProcessors: Int,
    val totalMemoryMb: Long
)
