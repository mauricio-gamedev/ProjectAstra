package io.github.astromg01.astra.expansion

import java.io.File

enum class ExpansionOperation {
    IDLE,
    INSPECT_INSTANCE,
    LIST_LOADERS,
    INSTALL_LOADER,
    SEARCH_MODS,
    INSTALL_MOD,
    APPLY_OPTIMIZATION,
    ANALYZE_PERFORMANCE,
}

data class ExpansionUiState(
    val operation: ExpansionOperation = ExpansionOperation.IDLE,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Small synchronous controller intended to be called from a ViewModel/worker dispatcher.
 * It has no Android dependency so it remains unit-testable. Heavy/network operations must
 * never be invoked from the Android main thread by the integration layer.
 */
class AstraExpansionController(
    private val facade: AstraExpansionFacade = AstraExpansionFacade(),
    private val stateListener: (ExpansionUiState) -> Unit = {},
) {
    @Volatile
    var state: ExpansionUiState = ExpansionUiState()
        private set

    fun inspect(instance: MinecraftInstance): Result<InstanceInspection> =
        run(ExpansionOperation.INSPECT_INSTANCE, "Inspecting installed mods") {
            facade.inspect(instance)
        }

    fun listLoaderVersions(minecraftVersion: String, loader: LoaderType): Result<List<String>> =
        run(ExpansionOperation.LIST_LOADERS, "Resolving ${loader.name.lowercase()} versions") {
            facade.listLoaderVersions(minecraftVersion, loader)
        }

    fun installLoader(
        minecraftHome: File,
        minecraftVersion: String,
        selection: LoaderSelection,
        javaExecutable: File,
    ): Result<LoaderInstallationResult> =
        run(ExpansionOperation.INSTALL_LOADER, "Installing ${selection.type.name.lowercase()}") {
            facade.installLoader(minecraftHome, minecraftVersion, selection, javaExecutable)
        }

    fun searchMods(instance: MinecraftInstance, query: String): Result<List<ModProject>> =
        run(ExpansionOperation.SEARCH_MODS, "Searching compatible mods") {
            facade.searchMods(instance, query)
        }

    fun installMod(instance: MinecraftInstance, projectIdOrSlug: String): Result<List<InstalledMod>> =
        run(ExpansionOperation.INSTALL_MOD, "Installing mod and required dependencies") {
            facade.installLatestCompatibleMod(instance, projectIdOrSlug)
        }

    fun applyOptimization(
        instance: MinecraftInstance,
        profile: OptimizationProfile,
        device: DeviceProfile,
    ): Result<OptimizationApplyResult> =
        run(ExpansionOperation.APPLY_OPTIMIZATION, "Applying ${profile.name.lowercase()} profile") {
            facade.applyOptimization(instance, profile, device)
        }

    fun previewAdaptive(
        instance: MinecraftInstance,
        log: String,
        averageFps: Double,
        p95FrameTimeMs: Double,
        usedMemoryMb: Long,
        memoryLimitMb: Long,
        thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
    ): Result<AdaptivePreview> =
        run(ExpansionOperation.ANALYZE_PERFORMANCE, "Analyzing measured performance") {
            facade.previewAdaptive(
                instance = instance,
                log = log,
                averageFps = averageFps,
                p95FrameTimeMs = p95FrameTimeMs,
                usedMemoryMb = usedMemoryMb,
                memoryLimitMb = memoryLimitMb,
                thermalStatus = thermalStatus,
            )
        }

    private fun <T> run(operation: ExpansionOperation, message: String, block: () -> T): Result<T> {
        publish(ExpansionUiState(operation, busy = true, message = message))
        return kotlin.runCatching(block).onSuccess {
            publish(ExpansionUiState(message = "Completed"))
        }.onFailure { failure ->
            publish(ExpansionUiState(error = failure.message ?: failure::class.java.simpleName))
        }
    }

    private fun publish(value: ExpansionUiState) {
        state = value
        stateListener(value)
    }
}
