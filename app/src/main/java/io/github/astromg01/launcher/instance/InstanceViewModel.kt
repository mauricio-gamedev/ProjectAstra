package io.github.astromg01.launcher.instance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.install.InstallProgress
import io.github.astromg01.launcher.install.VersionInstaller
import io.github.astromg01.launcher.version.MinecraftVersion
import io.github.astromg01.launcher.version.VersionManifestService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstanceViewModel(application: Application) : AndroidViewModel(application) {
    private val store = InstanceStore(application)

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

    var installProgress by mutableStateOf<InstallProgress?>(null)
        private set

    var installMessage by mutableStateOf<String?>(null)
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

    fun isInstalled(versionId: String): Boolean =
        VersionInstaller.isInstalled(getApplication(), versionId)

    fun installedJavaMajor(versionId: String): Int? =
        VersionInstaller.readInstalledInfo(getApplication(), versionId)?.javaMajorVersion

    fun removeInstance(id: String) {
        if (installingInstanceId == id) return
        instances = instances.filterNot { it.id == id }
        store.save(instances)
    }

    fun clearError() {
        errorMessage = null
    }
}
