package io.github.astromg01.launcher.instance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.astromg01.launcher.core.MinecraftInstance
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

    fun removeInstance(id: String) {
        instances = instances.filterNot { it.id == id }
        store.save(instances)
    }

    fun clearError() {
        errorMessage = null
    }
}
