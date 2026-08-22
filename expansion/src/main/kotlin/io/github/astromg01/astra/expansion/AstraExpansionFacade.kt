package io.github.astromg01.astra.expansion

import java.io.File

data class InstanceInspection(
    val detectedMods: List<DetectedMod>,
    val compatibilityIssues: List<CompatibilityIssue>,
) {
    val blockers: List<CompatibilityIssue>
        get() = compatibilityIssues.filter { it.severity == CompatibilityIssue.Severity.BLOCKER }

    val modIds: Set<String>
        get() = detectedMods.map { it.id.lowercase() }.toSet()
}

data class OptimizationPreview(
    val inspection: InstanceInspection,
    val plan: OptimizationPlan,
)

data class OptimizationApplyResult(
    val preview: OptimizationPreview,
    val snapshot: OptionsSnapshot,
    val changedOptions: List<String>,
)

data class AdaptivePreview(
    val metrics: MinecraftLogMetrics,
    val inspection: InstanceInspection,
    val decision: AdaptiveDecision,
)

data class AdaptiveApplyResult(
    val preview: AdaptivePreview,
    val snapshot: OptionsSnapshot?,
    val changedOptions: List<String>,
)

/**
 * Single entry point for Android UI/ViewModel integration.
 *
 * Network work (loader/mod discovery and installation) is deliberately separated from
 * launch-time work. prepareAlpha38Launch() only inspects local files and transforms the
 * already-resolved launch plan, so boot never depends on an online service.
 */
class AstraExpansionFacade(
    private val loaderRegistry: LoaderRegistry = LoaderRegistry(),
    private val loaderInstaller: LoaderInstallerCoordinator = LoaderInstallerCoordinator(),
    private val modManager: ModManager = ModManager(),
    private val scanner: InstalledModScanner = InstalledModScanner(),
    private val compatibility: CompatibilityEngine = CompatibilityEngine(),
    private val optimization: OptimizationEngine = OptimizationEngine(compatibility),
    private val adaptive: AdaptiveOptimizationEngine = AdaptiveOptimizationEngine(compatibility),
    private val tuner: MinecraftOptionsTuner = MinecraftOptionsTuner(),
    private val rollbackStore: OptimizationRollbackStore = OptimizationRollbackStore(),
    private val launchPipeline: LaunchExtensionPipeline = LaunchExtensionPipeline(),
) {
    fun supportedLoaders(): Set<LoaderType> = loaderRegistry.supportedTypes()

    fun listLoaderVersions(minecraftVersion: String, loader: LoaderType): List<String> =
        loaderRegistry.provider(loader).listVersions(minecraftVersion)

    fun installLoader(
        minecraftHome: File,
        minecraftVersion: String,
        selection: LoaderSelection,
        javaExecutable: File,
    ): LoaderInstallationResult = loaderInstaller.install(
        minecraftHome = minecraftHome,
        minecraftVersion = minecraftVersion,
        selection = selection,
        javaExecutable = javaExecutable,
    )

    fun searchMods(instance: MinecraftInstance, query: String): List<ModProject> {
        require(query.isNotBlank()) { "Mod search query must not be blank" }
        return modManager.search(instance, query.trim())
    }

    fun installLatestCompatibleMod(instance: MinecraftInstance, projectIdOrSlug: String): List<InstalledMod> {
        require(projectIdOrSlug.isNotBlank()) { "Mod project id/slug must not be blank" }
        return modManager.installLatestCompatible(instance, projectIdOrSlug.trim())
    }

    fun installPerformancePreset(instance: MinecraftInstance): PerformancePresetResult =
        PerformanceModPresetInstaller(modManager, compatibility).install(instance)

    fun listInstalledModFiles(instance: MinecraftInstance): List<File> = modManager.listInstalled(instance)

    fun disableMod(instance: MinecraftInstance, modFile: File): File = modManager.disable(instance, modFile)

    fun inspect(instance: MinecraftInstance): InstanceInspection {
        val detected = scanner.scan(instance)
        val ids = detected.map { it.id.lowercase() }.toSet()
        return InstanceInspection(
            detectedMods = detected,
            compatibilityIssues = compatibility.evaluate(instance.loader.type, ids),
        )
    }

    fun previewOptimization(
        instance: MinecraftInstance,
        profile: OptimizationProfile,
        device: DeviceProfile,
    ): OptimizationPreview {
        val inspection = inspect(instance)
        require(inspection.blockers.isEmpty()) {
            inspection.blockers.joinToString("; ") { it.message }
        }
        return OptimizationPreview(
            inspection = inspection,
            plan = optimization.buildPlan(profile, device, instance.loader.type, inspection.modIds),
        )
    }

    fun applyOptimization(
        instance: MinecraftInstance,
        profile: OptimizationProfile,
        device: DeviceProfile,
    ): OptimizationApplyResult {
        val preview = previewOptimization(instance, profile, device)
        return applyPlanTransactional(instance, preview)
    }

    fun rollbackOptimization(instance: MinecraftInstance, result: OptimizationApplyResult) {
        rollbackStore.rollback(File(instance.root, "options.txt"), result.snapshot)
    }

    fun analyzeMinecraftLog(log: String): MinecraftLogMetrics = MinecraftLogPerformanceAnalyzer.analyze(log)

    fun previewAdaptive(
        instance: MinecraftInstance,
        log: String,
        averageFps: Double,
        p95FrameTimeMs: Double,
        usedMemoryMb: Long,
        memoryLimitMb: Long,
        thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
        fallbackRenderDistance: Int = 6,
        fallbackSimulationDistance: Int = 5,
    ): AdaptivePreview {
        val metrics = MinecraftLogPerformanceAnalyzer.analyze(log)
        val inspection = inspect(instance)
        val sample = PerformanceSample(
            averageFps = averageFps,
            p95FrameTimeMs = p95FrameTimeMs,
            serverBehindMs = metrics.lastServerBehindMs,
            usedMemoryMb = usedMemoryMb,
            memoryLimitMb = memoryLimitMb,
            renderDistance = metrics.renderDistance ?: fallbackRenderDistance,
            simulationDistance = metrics.simulationDistance ?: fallbackSimulationDistance,
            thermalStatus = thermalStatus,
        )
        return AdaptivePreview(
            metrics = metrics,
            inspection = inspection,
            decision = adaptive.evaluate(sample, instance.loader.type, inspection.modIds),
        )
    }

    fun applyAdaptive(instance: MinecraftInstance, preview: AdaptivePreview): AdaptiveApplyResult {
        val action = preview.decision.action
            ?: return AdaptiveApplyResult(preview, snapshot = null, changedOptions = emptyList())
        require(preview.inspection.blockers.isEmpty()) { "Adaptive optimization blocked by mod compatibility rules" }

        val plan = OptimizationPlan(
            profile = OptimizationProfile.ADAPTIVE,
            actions = listOf(action),
            suppressedActions = emptyList(),
            warnings = listOf(preview.decision.reason),
        )
        val syntheticPreview = OptimizationPreview(preview.inspection, plan)
        val result = applyPlanTransactional(instance, syntheticPreview)
        return AdaptiveApplyResult(preview, result.snapshot, result.changedOptions)
    }

    fun rollbackAdaptive(instance: MinecraftInstance, result: AdaptiveApplyResult) {
        result.snapshot?.let { rollbackStore.rollback(File(instance.root, "options.txt"), it) }
    }

    /**
     * Launch-time bridge for the alpha38 contract. This function is intentionally local-only:
     * no loader metadata or mod downloads are resolved here.
     */
    fun prepareAlpha38Launch(
        coreInstance: Alpha38InstanceContract,
        corePlan: Alpha38LaunchPlanContract,
        instanceRoot: File,
        device: DeviceProfile,
    ): Alpha38LaunchPlanContract {
        val instance = Alpha38CoreAdapter.instance(coreInstance, instanceRoot)
        val inspection = inspect(instance)
        require(inspection.blockers.isEmpty()) {
            "Launch blocked: " + inspection.blockers.joinToString("; ") { it.message }
        }

        val profile = Alpha38CoreAdapter.optimizationProfile(coreInstance.performanceMode)
        val optimizationPlan = optimization.buildPlan(profile, device, instance.loader.type, inspection.modIds)
        val extension = launchPipeline.apply(
            base = Alpha38CoreAdapter.launchPlan(corePlan),
            loader = null,
            optimization = optimizationPlan,
        )
        return Alpha38CoreAdapter.merge(corePlan, extension)
    }

    private fun applyPlanTransactional(
        instance: MinecraftInstance,
        preview: OptimizationPreview,
    ): OptimizationApplyResult {
        val optionsFile = File(instance.root, "options.txt")
        val snapshots = File(instance.root, ".astra/rollback/${System.currentTimeMillis()}")
        val snapshot = rollbackStore.snapshot(optionsFile, snapshots)
        return try {
            val changes = tuner.apply(optionsFile, preview.plan)
            OptimizationApplyResult(preview, snapshot, changes)
        } catch (failure: Throwable) {
            runCatching { rollbackStore.rollback(optionsFile, snapshot) }
            throw failure
        }
    }
}
