package io.github.astromg01.launcher.runtime

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeViewModel(application: Application) : AndroidViewModel(application) {
    val architecture: String = RuntimeCatalog.deviceArchitecture() ?: "não suportada"
    val availableMajors: List<Int> = RuntimeCatalog.availableMajors()

    var installingMajor by mutableStateOf<Int?>(null)
        private set

    var progress by mutableStateOf<RuntimeProgress?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var refreshToken by mutableStateOf(0)

    fun installedRuntime(majorVersion: Int): InstalledRuntime? {
        refreshToken
        return RuntimeInstaller.installedRuntime(getApplication(), majorVersion)
    }

    fun isInstalled(majorVersion: Int): Boolean = installedRuntime(majorVersion) != null

    fun install(majorVersion: Int) {
        if (installingMajor != null) return
        if (majorVersion !in availableMajors) {
            errorMessage = "Java $majorVersion não está disponível para $architecture."
            return
        }

        viewModelScope.launch {
            installingMajor = majorVersion
            progress = RuntimeProgress("Preparando Java $majorVersion")
            message = null
            errorMessage = null

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RuntimeInstaller.install(getApplication(), majorVersion) { update ->
                        viewModelScope.launch { progress = update }
                    }
                }
            }

            result.onSuccess { runtime ->
                refreshToken++
                progress = RuntimeProgress("Java ${runtime.majorVersion} pronto", 1, 1)
                message = "Java ${runtime.majorVersion} instalado para ${runtime.architecture}."
            }.onFailure { error ->
                progress = null
                errorMessage = error.message ?: "Falha ao instalar o runtime Java."
            }

            installingMajor = null
        }
    }

    fun clearStatus() {
        message = null
        errorMessage = null
    }
}
