package io.github.astromg01.launcher.expansion

import android.content.Context
import android.os.Build
import io.github.astromg01.astra.expansion.AstraExpansionFacade
import io.github.astromg01.astra.expansion.AstraPerformancePreset
import io.github.astromg01.astra.expansion.DeviceProfile as ExpansionDeviceProfile
import io.github.astromg01.astra.expansion.DetectedMod
import io.github.astromg01.astra.expansion.InstanceInspection
import io.github.astromg01.astra.expansion.InstalledMod
import io.github.astromg01.astra.expansion.LaunchExtensionPipeline
import io.github.astromg01.astra.expansion.LaunchPlan as ExpansionLaunchPlan
import io.github.astromg01.astra.expansion.LoaderInstallationResult
import io.github.astromg01.astra.expansion.LoaderSelection
import io.github.astromg01.astra.expansion.LoaderType
import io.github.astromg01.astra.expansion.MinecraftInstance as ExpansionInstance
import io.github.astromg01.astra.expansion.ModProject
import io.github.astromg01.astra.expansion.OptimizationApplyResult
import io.github.astromg01.astra.expansion.OptimizationProfile
import io.github.astromg01.astra.expansion.PerformancePresetResult
import io.github.astromg01.launcher.core.DeviceProfiler
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.core.PerformanceMode
import io.github.astromg01.launcher.launch.LaunchPlan
import java.io.File

object AstraExpansionBridge {
    private val facade = AstraExpansionFacade()
    private val launchPipeline = LaunchExtensionPipeline()

    fun minecraftRoot(context: Context): File = File(context.filesDir, "minecraft")

    fun gameRoot(context: Context, instanceId: String): File =
        File(minecraftRoot(context), "instances/$instanceId/game").apply { mkdirs() }

    fun expansionInstance(context: Context, instance: MinecraftInstance): ExpansionInstance =
        ExpansionInstance(
            id = instance.id,
            minecraftVersion = instance.minecraftVersion,
            root = gameRoot(context, instance.id),
            loader = LoaderSelection(loaderType(instance.loader), instance.loaderVersion),
        )

    fun deviceProfile(context: Context): ExpansionDeviceProfile {
        val core = DeviceProfiler.read(context)
        return ExpansionDeviceProfile(
            androidApi = core.androidSdk,
            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            totalRamMb = core.totalMemoryMb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            cpuCores = core.availableProcessors,
            gpuRenderer = null,
        )
    }

    fun inspect(context: Context, instance: MinecraftInstance): InstanceInspection =
        facade.inspect(expansionInstance(context, instance))

    fun listLoaderVersions(instance: MinecraftInstance, type: LoaderType): List<String> =
        facade.listLoaderVersions(instance.minecraftVersion, type)

    fun installLoader(
        context: Context,
        instance: MinecraftInstance,
        type: LoaderType,
        version: String?,
        javaExecutable: File,
    ): LoaderInstallationResult = facade.installLoader(
        minecraftHome = minecraftRoot(context),
        minecraftVersion = instance.minecraftVersion,
        selection = LoaderSelection(type, version),
        javaExecutable = javaExecutable,
    )

    fun searchMods(context: Context, instance: MinecraftInstance, query: String): List<ModProject> =
        facade.searchMods(expansionInstance(context, instance), query)

    fun installMod(context: Context, instance: MinecraftInstance, projectIdOrSlug: String): List<InstalledMod> =
        facade.installLatestCompatibleMod(expansionInstance(context, instance), projectIdOrSlug)

    fun performancePackTargets(instance: MinecraftInstance): List<String> =
        AstraPerformancePreset.targets(loaderType(instance.loader))

    fun installPerformancePack(context: Context, instance: MinecraftInstance): PerformancePresetResult =
        facade.installPerformancePreset(expansionInstance(context, instance))

    fun detectedMods(context: Context, instance: MinecraftInstance): List<DetectedMod> =
        inspect(context, instance).detectedMods

    fun disableMod(context: Context, instance: MinecraftInstance, modFile: File): File =
        facade.disableMod(expansionInstance(context, instance), modFile)

    fun applyOptimization(context: Context, instance: MinecraftInstance): OptimizationApplyResult =
        facade.applyOptimization(
            instance = expansionInstance(context, instance),
            profile = optimizationProfile(instance.performanceMode),
            device = deviceProfile(context),
        )

    fun rollbackOptimization(
        context: Context,
        instance: MinecraftInstance,
        result: OptimizationApplyResult,
    ) {
        facade.rollbackOptimization(expansionInstance(context, instance), result)
    }

    fun rollbackLatestOptimization(context: Context, instance: MinecraftInstance): Boolean =
        facade.rollbackLatestOptimization(expansionInstance(context, instance))

    /**
     * Purely local launch transformation: loader profile + compatibility-aware optimization.
     * No network is touched here.
     */
    fun prepareLaunch(context: Context, instance: MinecraftInstance, vanillaPlan: LaunchPlan): LaunchPlan {
        val loaderPlan = LoaderLaunchProfileResolver.apply(context, instance, vanillaPlan)
        val expansionInstance = expansionInstance(context, instance)
        val preview = facade.previewOptimization(
            instance = expansionInstance,
            profile = optimizationProfile(instance.performanceMode),
            device = deviceProfile(context),
        )

        val extension = launchPipeline.apply(
            base = ExpansionLaunchPlan(
                jvmArgs = loaderPlan.jvmArguments,
                gameArgs = loaderPlan.gameArguments,
                environment = loaderPlan.environment,
                mainClass = loaderPlan.mainClass,
                classpath = loaderPlan.classpath.map(::File),
            ),
            loader = null,
            optimization = preview.plan,
        )

        return loaderPlan.copy(
            mainClass = extension.plan.mainClass,
            classpath = extension.plan.classpath.map(File::getAbsolutePath),
            jvmArguments = extension.plan.jvmArgs,
            gameArguments = extension.plan.gameArgs,
            environment = extension.plan.environment,
            warnings = (loaderPlan.warnings + extension.warnings).distinct(),
        )
    }

    fun loaderType(raw: String?): LoaderType = when (raw?.trim()?.lowercase()) {
        null, "", "vanilla", "none" -> LoaderType.VANILLA
        "fabric" -> LoaderType.FABRIC
        "quilt" -> LoaderType.QUILT
        "forge" -> LoaderType.FORGE
        "neoforge", "neo-forge", "neo_forge" -> LoaderType.NEOFORGE
        else -> error("Loader não suportado pelo Astra: $raw")
    }

    fun loaderStorageName(type: LoaderType): String? = when (type) {
        LoaderType.VANILLA -> null
        LoaderType.FABRIC -> "fabric"
        LoaderType.QUILT -> "quilt"
        LoaderType.FORGE -> "forge"
        LoaderType.NEOFORGE -> "neoforge"
    }

    fun optimizationProfile(mode: PerformanceMode): OptimizationProfile = when (mode) {
        PerformanceMode.PERFORMANCE -> OptimizationProfile.PERFORMANCE
        PerformanceMode.BALANCED -> OptimizationProfile.BALANCED
        PerformanceMode.QUALITY -> OptimizationProfile.QUALITY
        PerformanceMode.ADAPTIVE -> OptimizationProfile.ADAPTIVE
    }
}
