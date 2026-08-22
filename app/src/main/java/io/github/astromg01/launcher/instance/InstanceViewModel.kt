package io.github.astromg01.launcher.instance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.astromg01.astra.expansion.DetectedMod
import io.github.astromg01.astra.expansion.LoaderType
import io.github.astromg01.astra.expansion.ModProject
import io.github.astromg01.launcher.account.AccountStore
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.core.PerformanceMode
import io.github.astromg01.launcher.core.RendererKind
import io.github.astromg01.launcher.expansion.AstraExpansionBridge
import io.github.astromg01.launcher.install.InstallProgress
import io.github.astromg01.launcher.install.VersionInstaller
import io.github.astromg01.launcher.launch.LaunchPlan
import io.github.astromg01.launcher.launch.LaunchPlanBuilder
import io.github.astromg01.launcher.lwjgl.AndroidLwjglManager
import io.github.astromg01.launcher.nativebridge.NativeRuntimeValidator
import io.github.astromg01.launcher.renderer.RendererComponentManager
import io.github.astromg01.launcher.runtime.RuntimeInstaller
import io.github.astromg01.launcher.version.MinecraftVersion
import io.github.astromg01.launcher.version.VersionManifestService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstanceViewModel(application: Application) : AndroidViewModel(application) {
    private val store = InstanceStore(application)
    private val accountStore = AccountStore(application)

    var instances by mutableStateOf(store.load())
        private set

    var versions by mutableStateOf<List<MinecraftVersion>>(emptyList())
        private set

    var latestRelease by mutableStateOf<String?>(null)
        private set

    var latestSnapshot by mutableStateOf<String?>(null)
        private set

    var isLoadingVersions by mutableStateOf(false)
        private set

    var installingInstanceId by mutableStateOf<String?>(null)
        private set

    var preparingLaunchInstanceId by mutableStateOf<String?>(null)
        private set

    var managingLoaderInstanceId by mutableStateOf<String?>(null)
        private set

    var managingModsInstanceId by mutableStateOf<String?>(null)
        private set

    var applyingOptimizationInstanceId by mutableStateOf<String?>(null)
        private set

    var installProgress by mutableStateOf<InstallProgress?>(null)
        private set

    var installMessage by mutableStateOf<String?>(null)
        private set

    var loaderVersions by mutableStateOf<List<String>>(emptyList())
        private set

    var loaderVersionsInstanceId by mutableStateOf<String?>(null)
        private set

    var loaderMessage by mutableStateOf<String?>(null)
        private set

    var modSearchResults by mutableStateOf<List<ModProject>>(emptyList())
        private set

    var modSearchInstanceId by mutableStateOf<String?>(null)
        private set

    var detectedMods by mutableStateOf<List<DetectedMod>>(emptyList())
        private set

    var detectedModsInstanceId by mutableStateOf<String?>(null)
        private set

    var modMessage by mutableStateOf<String?>(null)
        private set

    var optimizationMessage by mutableStateOf<String?>(null)
        private set

    var preparedLaunchPlan by mutableStateOf<LaunchPlan?>(null)
        private set

    var launchMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        refreshVersions()
    }

    fun refreshVersions() {
        if (isLoadingVersions) return

        viewModelScope.launch {
            isLoadingVersions = true
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    VersionManifestService.fetch()
                }
            }

            result.onSuccess { manifest ->
                versions = manifest.versions
                latestRelease = manifest.latestRelease
                latestSnapshot = manifest.latestSnapshot
            }.onFailure { error ->
                errorMessage = error.message ?: "Não foi possível carregar as versões do Minecraft."
            }

            isLoadingVersions = false
        }
    }

    fun createInstance(name: String, versionId: String): Boolean {
        val cleanName = name.trim()
        val cleanVersion = versionId.trim()

        if (cleanName.length !in 2..40) {
            errorMessage = "O nome da instância precisa ter entre 2 e 40 caracteres."
            return false
        }

        if (cleanVersion.isBlank()) {
            errorMessage = "Escolha uma versão do Minecraft."
            return false
        }

        if (versions.isNotEmpty() && versions.none { it.id == cleanVersion }) {
            errorMessage = "Essa versão não está no manifest atual da Mojang."
            return false
        }

        if (instances.any { it.name.equals(cleanName, ignoreCase = true) }) {
            errorMessage = "Já existe uma instância com esse nome."
            return false
        }

        val instance = MinecraftInstance(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            minecraftVersion = cleanVersion
        )

        instances = instances + instance
        store.save(instances)
        errorMessage = null
        return true
    }

    fun installInstance(instanceId: String) {
        if (installingInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        viewModelScope.launch {
            installingInstanceId = instanceId
            installProgress = InstallProgress("Preparando", 0, 1, instance.minecraftVersion)
            installMessage = null
            preparedLaunchPlan = null
            launchMessage = null
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val version = versions.firstOrNull { it.id == instance.minecraftVersion }
                        ?: VersionManifestService.fetch().versions.firstOrNull {
                            it.id == instance.minecraftVersion
                        }
                        ?: error("Versão ${instance.minecraftVersion} não encontrada no manifest da Mojang.")

                    VersionInstaller.install(getApplication(), version) { progress ->
                        viewModelScope.launch {
                            installProgress = progress
                        }
                    }
                }
            }

            result.onSuccess { info ->
                installProgress = InstallProgress("Concluído", 1, 1, info.versionId)
                installMessage = "Minecraft ${info.versionId} instalado • Java ${info.javaMajorVersion} necessário"
            }.onFailure { error ->
                errorMessage = error.message ?: "Falha ao instalar os arquivos do Minecraft."
                installProgress = null
            }

            installingInstanceId = null
        }
    }

    fun refreshLoaderVersions(instanceId: String, loaderType: LoaderType) {
        if (managingLoaderInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        if (loaderType == LoaderType.VANILLA) {
            loaderVersions = emptyList()
            loaderVersionsInstanceId = instanceId
            loaderMessage = "Vanilla não precisa de versão de loader."
            return
        }

        viewModelScope.launch {
            managingLoaderInstanceId = instanceId
            loaderVersions = emptyList()
            loaderVersionsInstanceId = instanceId
            loaderMessage = "Buscando versões de ${loaderType.name.lowercase()}…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AstraExpansionBridge.listLoaderVersions(instance, loaderType)
                }
            }

            result.onSuccess { resolved ->
                loaderVersions = resolved
                loaderMessage = if (resolved.isEmpty()) {
                    "Nenhuma versão compatível de ${loaderType.name.lowercase()} encontrada."
                } else {
                    "${resolved.size} versões compatíveis encontradas."
                }
            }.onFailure { error ->
                loaderMessage = null
                errorMessage = error.message ?: "Falha ao consultar versões do loader."
            }
            managingLoaderInstanceId = null
        }
    }

    fun installLoader(instanceId: String, loaderType: LoaderType, loaderVersion: String? = null) {
        if (managingLoaderInstanceId != null || installingInstanceId != null || preparingLaunchInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        if (loaderType == LoaderType.VANILLA) {
            updateInstance(
                instance.copy(loader = null, loaderVersion = null, loaderProfileId = null),
                invalidateLaunch = true
            )
            loaderMessage = "Instância restaurada para Vanilla."
            refreshDetectedMods(instanceId)
            return
        }

        viewModelScope.launch {
            managingLoaderInstanceId = instanceId
            loaderMessage = "Preparando ${loaderType.name.lowercase()}…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val installedInfo = VersionInstaller.readInstalledInfo(app, instance.minecraftVersion)
                        ?: error("Instale Minecraft ${instance.minecraftVersion} antes do loader.")
                    val runtime = RuntimeInstaller.installedRuntime(app, installedInfo.javaMajorVersion)
                        ?: error("Java ${installedInfo.javaMajorVersion} precisa estar instalado antes do loader.")

                    AstraExpansionBridge.installLoader(
                        context = app,
                        instance = instance,
                        type = loaderType,
                        version = loaderVersion,
                        javaExecutable = File(runtime.javaPath),
                    )
                }
            }

            result.onSuccess { installed ->
                val updated = instance.copy(
                    loader = AstraExpansionBridge.loaderStorageName(installed.selection.type),
                    loaderVersion = installed.selection.version,
                    loaderProfileId = installed.installedVersionId,
                )
                updateInstance(updated, invalidateLaunch = true)
                loaderMessage = buildString {
                    append(installed.selection.type.name)
                    installed.selection.version?.let { append(" ").append(it) }
                    append(" instalado • profile ").append(installed.installedVersionId)
                }
                refreshDetectedMods(instanceId)
            }.onFailure { error ->
                loaderMessage = null
                errorMessage = error.message ?: "Falha ao instalar o loader."
            }
            managingLoaderInstanceId = null
        }
    }

    fun searchMods(instanceId: String, query: String) {
        if (managingModsInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return
        if (AstraExpansionBridge.loaderType(instance.loader) == LoaderType.VANILLA) {
            errorMessage = "Escolha Fabric, Quilt, Forge ou NeoForge antes de instalar mods."
            return
        }

        viewModelScope.launch {
            managingModsInstanceId = instanceId
            modSearchInstanceId = instanceId
            modSearchResults = emptyList()
            modMessage = "Buscando mods compatíveis…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AstraExpansionBridge.searchMods(getApplication(), instance, query)
                }
            }
            result.onSuccess { projects ->
                modSearchResults = projects
                modMessage = "${projects.size} mods compatíveis encontrados."
            }.onFailure { error ->
                modMessage = null
                errorMessage = error.message ?: "Falha ao pesquisar mods."
            }
            managingModsInstanceId = null
        }
    }

    fun installMod(instanceId: String, projectIdOrSlug: String) {
        if (managingModsInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        viewModelScope.launch {
            managingModsInstanceId = instanceId
            modMessage = "Instalando mod e dependências…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AstraExpansionBridge.installMod(getApplication(), instance, projectIdOrSlug)
                }
            }
            result.onSuccess { installed ->
                modMessage = "${installed.size} arquivo(s) instalado(s) com verificação de integridade."
                refreshDetectedMods(instanceId)
                preparedLaunchPlan = null
                launchMessage = null
            }.onFailure { error ->
                modMessage = null
                errorMessage = error.message ?: "Falha ao instalar mod."
            }
            managingModsInstanceId = null
        }
    }

    fun refreshDetectedMods(instanceId: String) {
        val instance = instances.firstOrNull { it.id == instanceId } ?: return
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AstraExpansionBridge.detectedMods(getApplication(), instance)
                }
            }
            result.onSuccess { mods ->
                detectedMods = mods
                detectedModsInstanceId = instanceId
            }
        }
    }

    fun setPerformanceMode(instanceId: String, mode: PerformanceMode) {
        val instance = instances.firstOrNull { it.id == instanceId } ?: return
        if (instance.performanceMode == mode) return
        updateInstance(instance.copy(performanceMode = mode), invalidateLaunch = true)
        optimizationMessage = "Perfil ${mode.name.lowercase()} selecionado."
    }

    fun applyPerformanceProfile(instanceId: String) {
        if (applyingOptimizationInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        viewModelScope.launch {
            applyingOptimizationInstanceId = instanceId
            optimizationMessage = "Aplicando perfil ${instance.performanceMode.name.lowercase()}…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AstraExpansionBridge.applyOptimization(getApplication(), instance)
                }
            }
            result.onSuccess { applied ->
                optimizationMessage = if (applied.changedOptions.isEmpty()) {
                    "Perfil validado • nenhuma opção do Minecraft precisou mudar."
                } else {
                    "Perfil aplicado • ${applied.changedOptions.joinToString()}"
                }
                preparedLaunchPlan = null
                launchMessage = null
            }.onFailure { error ->
                optimizationMessage = null
                errorMessage = error.message ?: "Falha ao aplicar perfil de otimização."
            }
            applyingOptimizationInstanceId = null
        }
    }

    fun prepareLaunchPlan(instanceId: String) {
        if (preparingLaunchInstanceId != null || installingInstanceId != null || managingLoaderInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        viewModelScope.launch {
            preparingLaunchInstanceId = instanceId
            preparedLaunchPlan = null
            launchMessage = "Validando runtime nativo…"
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val accounts = accountStore.load()
                    val account = instance.accountId?.let { wantedId ->
                        accounts.firstOrNull { it.id == wantedId }
                    } ?: accounts.firstOrNull { it.isDefault }
                    ?: accounts.firstOrNull()
                    ?: error("Adicione uma conta offline ou Microsoft antes de preparar o jogo.")

                    val installedInfo = VersionInstaller.readInstalledInfo(app, instance.minecraftVersion)
                        ?: error("Minecraft ${instance.minecraftVersion} ainda não está instalado.")
                    val runtime = RuntimeInstaller.installedRuntime(app, installedInfo.javaMajorVersion)
                        ?: error("Java ${installedInfo.javaMajorVersion} ainda não está instalado.")

                    val nativeStatus = NativeRuntimeValidator.validate(app, runtime)
                    if (!nativeStatus.success) {
                        error("Bridge Java nativa falhou: ${nativeStatus.detail}")
                    }
                    viewModelScope.launch {
                        launchMessage = "JLI_Launch OK • preparando renderer…"
                    }

                    val renderer = when (instance.renderer) {
                        RendererKind.AUTO,
                        RendererKind.MOBILEGLUES -> RendererComponentManager.ensureMobileGlues(app) { progress ->
                            viewModelScope.launch {
                                launchMessage = if (progress.totalBytes > 0L) {
                                    "${progress.stage}: ${progress.percent}%"
                                } else {
                                    progress.stage
                                }
                            }
                        }
                        RendererKind.GL4ES -> error("GL4ES ainda não possui componente nativo nesta alpha.")
                        RendererKind.ANGLE -> error("ANGLE ainda não possui componente nativo nesta alpha.")
                        RendererKind.ZINK -> error("Mesa/Zink ainda não possui componente nativo nesta alpha.")
                    }

                    viewModelScope.launch {
                        launchMessage = "MobileGlues ${renderer.version} OK • preparando LWJGL Android…"
                    }
                    val lwjgl = AndroidLwjglManager.ensureForMinecraft(app, instance.minecraftVersion) { progress ->
                        viewModelScope.launch {
                            launchMessage = if (progress.total > 0) {
                                "${progress.stage}: ${progress.percent}% • ${progress.detail.orEmpty()}"
                            } else {
                                progress.stage
                            }
                        }
                    }

                    val vanillaPlan = LaunchPlanBuilder.build(
                        context = app,
                        instance = instance,
                        account = account
                    )
                    val finalPlan = AstraExpansionBridge.prepareLaunch(
                        context = app,
                        instance = instance,
                        vanillaPlan = vanillaPlan,
                    )
                    PreparedNativeLaunch(
                        plan = finalPlan,
                        rendererVersion = renderer.version,
                        lwjglVersion = lwjgl.version,
                        nativeDetail = nativeStatus.detail
                    )
                }
            }

            result.onSuccess { prepared ->
                preparedLaunchPlan = prepared.plan
                launchMessage = buildString {
                    append("Plano nativo + expansão pronto • ")
                    append(prepared.nativeDetail)
                    append(" • MobileGlues ")
                    append(prepared.rendererVersion)
                    append(" • LWJGL Android ")
                    append(prepared.lwjglVersion)
                    append(" • Java ${prepared.plan.javaMajorVersion}")
                    append(" • ${prepared.plan.classpathCount} JARs")
                }
            }.onFailure { error ->
                launchMessage = null
                errorMessage = error.message ?: "Não foi possível preparar a execução nativa do Minecraft."
            }

            preparingLaunchInstanceId = null
        }
    }

    fun launchPlanFor(instanceId: String): LaunchPlan? =
        preparedLaunchPlan?.takeIf { it.instanceId == instanceId }

    fun isInstalled(versionId: String): Boolean =
        VersionInstaller.isInstalled(getApplication(), versionId)

    fun installedJavaMajor(versionId: String): Int? =
        VersionInstaller.readInstalledInfo(getApplication(), versionId)?.javaMajorVersion

    fun removeInstance(id: String) {
        if (installingInstanceId == id || preparingLaunchInstanceId == id || managingLoaderInstanceId == id || managingModsInstanceId == id) return
        instances = instances.filterNot { it.id == id }
        if (preparedLaunchPlan?.instanceId == id) {
            preparedLaunchPlan = null
            launchMessage = null
        }
        store.save(instances)
    }

    fun clearError() {
        errorMessage = null
    }

    private fun updateInstance(updated: MinecraftInstance, invalidateLaunch: Boolean) {
        instances = instances.map { current -> if (current.id == updated.id) updated else current }
        store.save(instances)
        if (invalidateLaunch && preparedLaunchPlan?.instanceId == updated.id) {
            preparedLaunchPlan = null
            launchMessage = null
        }
    }

    private data class PreparedNativeLaunch(
        val plan: LaunchPlan,
        val rendererVersion: String,
        val lwjglVersion: String,
        val nativeDetail: String
    )
}
