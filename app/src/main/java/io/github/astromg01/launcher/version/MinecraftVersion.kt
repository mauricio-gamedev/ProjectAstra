package io.github.astromg01.launcher.version

data class MinecraftVersion(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String,
    val sha1: String? = null,
    val complianceLevel: Int = 0
)

data class MinecraftVersionManifest(
    val latestRelease: String,
    val latestSnapshot: String,
    val versions: List<MinecraftVersion>
)
