package io.github.astromg01.launcher.instance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.astromg01.launcher.account.AccountStore
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.install.InstallProgress
import io.github.astromg01.launcher.install.VersionInstaller
import io.github.astromg01.launcher.launch.LaunchPlan
import io.github.astromg01.launcher.launch.LaunchPlanBuilder
import io.github.astromg01.launcher.version.MinecraftVersion
import io.github.astromg01.launcher.version.VersionManifestService
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

    var installProgress by mutableStateOf<InstallProgress?>(null)
        private set

    var installMessage by mutableStateOf<String?>(null)
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

    fun prepareLaunchPlan(instanceId: String) {
        if (preparingLaunchInstanceId != null || installingInstanceId != null) return
        val instance = instances.firstOrNull { it.id == instanceId } ?: return

        viewModelScope.launch {
            preparingLaunchInstanceId = instanceId
            preparedLaunchPlan = null
            launchMessage = null
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val accounts = accountStore.load()
                    val account = instance.accountId?.let { wantedId ->
                        accounts.firstOrNull { it.id == wantedId }
                    } ?: accounts.firstOrNull { it.isDefault }
                    ?: accounts.firstOrNull()
                    ?: error("Adicione uma conta offline ou Microsoft antes de preparar o jogo.")

                    LaunchPlanBuilder.build(
                        context = getApplication(),
                        instance = instance,
                        account = account
                    )
                }
            }

            result.onSuccess { plan ->
                preparedLaunchPlan = plan
                launchMessage = buildString {
                    append("Plano pronto • Java ${plan.javaMajorVersion}")
                    append(" • ${plan.classpathCount} entradas no classpath")
                    append(" • ${plan.mainClass}")
                }
            }.onFailure { error ->
                errorMessage = error.message ?: "Não foi possível preparar a execução do Minecraft."
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
        if (installingInstanceId == id || preparingLaunchInstanceId == id) return
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
}
