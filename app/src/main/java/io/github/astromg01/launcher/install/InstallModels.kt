package io.github.astromg01.launcher.install

data class InstallProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val currentFile: String? = null
) {
    val percent: Int
        get() = if (total <= 0) 0 else ((completed.toDouble() / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
}

data class InstalledVersionInfo(
    val versionId: String,
    val javaMajorVersion: Int,
    val mainClass: String,
    val assetIndexId: String
)
