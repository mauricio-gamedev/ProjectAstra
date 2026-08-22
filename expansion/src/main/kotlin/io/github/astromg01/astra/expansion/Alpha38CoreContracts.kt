package io.github.astromg01.astra.expansion

import java.io.File

/**
 * Stable contract recovered from Project Astra alpha38's own DEX.
 * It deliberately models only public launch-state data; native graphics/input internals
 * remain outside the expansion module.
 */
enum class Alpha38PerformanceMode { ADAPTIVE, BALANCED, PERFORMANCE, QUALITY }
enum class Alpha38RendererKind { ANGLE, AUTO, GL4ES, MOBILEGLUES, ZINK }

data class Alpha38InstanceContract(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val loader: String,
    val accountId: String,
    val performanceMode: Alpha38PerformanceMode,
    val renderer: Alpha38RendererKind,
    val memoryMb: Int,
)

data class Alpha38LaunchPlanContract(
    val instanceId: String,
    val minecraftVersion: String,
    val javaMajorVersion: Int,
    val javaExecutable: String,
    val workingDirectory: String,
    val mainClass: String,
    val classpath: List<File>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val environment: Map<String, String>,
    val warnings: List<String>,
)

object Alpha38CoreAdapter {
    fun optimizationProfile(mode: Alpha38PerformanceMode): OptimizationProfile = when (mode) {
        Alpha38PerformanceMode.ADAPTIVE -> OptimizationProfile.ADAPTIVE
        Alpha38PerformanceMode.BALANCED -> OptimizationProfile.BALANCED
        Alpha38PerformanceMode.PERFORMANCE -> OptimizationProfile.PERFORMANCE
        Alpha38PerformanceMode.QUALITY -> OptimizationProfile.QUALITY
    }

    fun loaderType(raw: String): LoaderType = when (raw.trim().lowercase()) {
        "", "vanilla", "none" -> LoaderType.VANILLA
        "fabric" -> LoaderType.FABRIC
        "quilt" -> LoaderType.QUILT
        "forge" -> LoaderType.FORGE
        "neoforge", "neo-forge", "neo_forge" -> LoaderType.NEOFORGE
        else -> error("Unsupported Astra loader '$raw'")
    }

    fun instance(core: Alpha38InstanceContract, root: File): MinecraftInstance = MinecraftInstance(
        id = core.id,
        minecraftVersion = core.minecraftVersion,
        root = root,
        loader = LoaderSelection(loaderType(core.loader), null),
    )

    fun launchPlan(core: Alpha38LaunchPlanContract): LaunchPlan = LaunchPlan(
        jvmArgs = core.jvmArguments,
        gameArgs = core.gameArguments,
        environment = core.environment,
        mainClass = core.mainClass,
        classpath = core.classpath,
    )

    fun merge(core: Alpha38LaunchPlanContract, extension: LaunchExtensionResult): Alpha38LaunchPlanContract = core.copy(
        mainClass = extension.plan.mainClass,
        classpath = extension.plan.classpath,
        jvmArguments = extension.plan.jvmArgs,
        gameArguments = extension.plan.gameArgs,
        environment = extension.plan.environment,
        warnings = (core.warnings + extension.warnings).distinct(),
    )
}
