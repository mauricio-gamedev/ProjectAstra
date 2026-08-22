package io.github.astromg01.astra.expansion

data class PresetModOutcome(
    val slug: String,
    val installed: List<InstalledMod>,
    val skippedReason: String? = null,
)

data class PerformancePresetResult(
    val loader: LoaderType,
    val outcomes: List<PresetModOutcome>,
) {
    val installedFiles: List<InstalledMod>
        get() = outcomes.flatMap { it.installed }

    val skipped: List<PresetModOutcome>
        get() = outcomes.filter { it.skippedReason != null }
}

object AstraPerformancePreset {
    /**
     * Conservative optimization set. Exact Minecraft/loader compatibility is still
     * resolved against Modrinth before each install; these names are not bundled artifacts.
     */
    fun targets(loader: LoaderType): List<String> = when (loader) {
        LoaderType.VANILLA -> emptyList()
        LoaderType.FABRIC -> listOf("sodium", "lithium", "ferrite-core", "immediatelyfast")
        LoaderType.QUILT -> listOf("sodium", "lithium", "ferrite-core", "immediatelyfast")
        LoaderType.FORGE -> listOf("embeddium", "ferrite-core", "modernfix")
        LoaderType.NEOFORGE -> listOf("embeddium", "ferrite-core", "modernfix")
    }
}

class PerformanceModPresetInstaller(
    private val modManager: ModManager = ModManager(),
    private val compatibility: CompatibilityEngine = CompatibilityEngine(),
) {
    fun install(instance: MinecraftInstance): PerformancePresetResult {
        val targets = AstraPerformancePreset.targets(instance.loader.type)
        require(targets.isNotEmpty()) { "Astra Performance Pack requires Fabric, Quilt, Forge or NeoForge." }

        val presetIssues = compatibility.evaluate(instance.loader.type, targets.toSet())
        require(presetIssues.none { it.severity == CompatibilityIssue.Severity.BLOCKER }) {
            presetIssues.filter { it.severity == CompatibilityIssue.Severity.BLOCKER }
                .joinToString("; ") { it.message }
        }

        val outcomes = targets.map { slug ->
            runCatching { modManager.installLatestCompatible(instance, slug) }
                .fold(
                    onSuccess = { PresetModOutcome(slug, it) },
                    onFailure = { failure ->
                        PresetModOutcome(
                            slug = slug,
                            installed = emptyList(),
                            skippedReason = failure.message ?: failure::class.java.simpleName,
                        )
                    },
                )
        }
        require(outcomes.any { it.installed.isNotEmpty() }) {
            "No Astra Performance Pack mod had a compatible release for this instance."
        }
        return PerformancePresetResult(instance.loader.type, outcomes)
    }
}
