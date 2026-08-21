package io.github.astromg01.launcher.nativebridge

import android.content.Context
import io.github.astromg01.launcher.runtime.InstalledRuntime
import java.io.File

object NativeRuntimeValidator {
    fun validate(context: Context, runtime: InstalledRuntime): NativeRuntimeStatus {
        val home = File(runtime.homePath)
        if (!home.isDirectory) {
            return NativeRuntimeStatus(false, null, "Diretório JAVA_HOME não existe.")
        }

        val jli = findLibrary(home, "libjli.so")
            ?: return NativeRuntimeStatus(false, null, "libjli.so não encontrada no Java ${runtime.majorVersion}.")

        val env = buildEnvironment(context, runtime)
        env.forEach { (key, value) ->
            val result = AstraNativeBridge.setEnvironment(key, value)
            if (result != 0) {
                return NativeRuntimeStatus(false, jli.absolutePath, "Falha ao configurar $key (errno $result).")
            }
        }

        val probe = AstraNativeBridge.probe(jli.absolutePath, "JLI_Launch")
        return if (probe.success) {
            NativeRuntimeStatus(
                success = true,
                jliPath = jli.absolutePath,
                detail = "JLI_Launch disponível • Java ${runtime.majorVersion}"
            )
        } else {
            NativeRuntimeStatus(
                success = false,
                jliPath = jli.absolutePath,
                detail = "libjli.so não carregou: ${probe.detail}"
            )
        }
    }

    fun buildEnvironment(context: Context, runtime: InstalledRuntime): Map<String, String> {
        val home = File(runtime.homePath)
        val libraryDirs = linkedSetOf<File>()

        home.walkTopDown()
            .maxDepth(4)
            .filter { file ->
                file.isDirectory && (
                    file.name == "lib" ||
                        file.name == "jli" ||
                        file.name == "server" ||
                        file.name == "client" ||
                        file.name.startsWith("aarch") ||
                        file.name.startsWith("arm") ||
                        file.name.startsWith("x86")
                    )
            }
            .forEach(libraryDirs::add)

        val nativeDir = context.applicationInfo.nativeLibraryDir
        if (!nativeDir.isNullOrBlank()) libraryDirs += File(nativeDir)

        return linkedMapOf(
            "JAVA_HOME" to home.absolutePath,
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to listOf(File(home, "bin").absolutePath, System.getenv("PATH").orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator),
            "LD_LIBRARY_PATH" to libraryDirs
                .filter(File::isDirectory)
                .joinToString(File.pathSeparator) { it.absolutePath }
        )
    }

    private fun findLibrary(root: File, name: String): File? =
        root.walkTopDown()
            .maxDepth(5)
            .firstOrNull { it.isFile && it.name == name }
}

data class NativeRuntimeStatus(
    val success: Boolean,
    val jliPath: String?,
    val detail: String
)
