package io.github.astromg01.launcher.runtime

data class RuntimeDescriptor(
    val majorVersion: Int,
    val architecture: String,
    val url: String,
    val sha256: String?,
    val source: String
)

data class InstalledRuntime(
    val majorVersion: Int,
    val architecture: String,
    val homePath: String,
    val javaPath: String,
    val source: String
)

data class RuntimeProgress(
    val stage: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val detail: String? = null
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else
            ((completedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
}
