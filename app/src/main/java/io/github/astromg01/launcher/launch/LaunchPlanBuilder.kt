package io.github.astromg01.launcher.launch

import android.content.Context
import io.github.astromg01.launcher.account.AccountProfile
import io.github.astromg01.launcher.account.AccountType
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.core.RendererKind
import io.github.astromg01.launcher.install.VersionInstaller
import io.github.astromg01.launcher.lwjgl.AndroidLwjglManager
import io.github.astromg01.launcher.lwjgl.AndroidLwjglNativesManager
import io.github.astromg01.launcher.lwjgl.InstalledAndroidLwjgl
import io.github.astromg01.launcher.nativebridge.NativeRuntimeValidator
import io.github.astromg01.launcher.renderer.RendererComponentManager
import io.github.astromg01.launcher.runtime.RuntimeInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LaunchPlanBuilder {
    private const val LAUNCHER_NAME = "ProjectAstra"
    private const val LAUNCHER_VERSION = "0.1.0-alpha19"

    fun build(
        context: Context,
        instance: MinecraftInstance,
        account: AccountProfile
    ): LaunchPlan {
        val installedInfo = VersionInstaller.readInstalledInfo(context, instance.minecraftVersion)
            ?: error("Minecraft ${instance.minecraftVersion} ainda não está instalado.")

        val runtime = RuntimeInstaller.installedRuntime(context, installedInfo.javaMajorVersion)
            ?: error("Java ${installedInfo.javaMajorVersion} ainda não está instalado.")

        val renderer = when (instance.renderer) {
            RendererKind.AUTO,
            RendererKind.MOBILEGLUES -> RendererComponentManager.installedMobileGlues(context)
                ?: error("MobileGlues ainda não está preparado para esta instância.")
            RendererKind.GL4ES -> error("GL4ES ainda não está integrado ao launch plan.")
            RendererKind.ANGLE -> error("ANGLE ainda não está integrado ao launch plan.")
            RendererKind.ZINK -> error("Mesa/Zink ainda não está integrado ao launch plan.")
        }

        val root = File(context.filesDir, "minecraft")
        val versionDir = File(root, "versions/${instance.minecraftVersion}")
        val metadataFile = File(versionDir, "${instance.minecraftVersion}.json")
        val clientJar = File(versionDir, "${instance.minecraftVersion}.jar")
        if (!metadataFile.isFile) error("Metadata da versão não encontrada: ${metadataFile.name}")
        if (!clientJar.isFile) error("Client JAR não encontrado: ${clientJar.name}")

        val metadata = JSONObject(metadataFile.readText())
        val androidLwjgl = AndroidLwjglManager.installedForMetadata(context, metadata)
        val lwjglNatives = AndroidLwjglNativesManager.ensureInstalled(context, androidLwjgl.version)
        val gameDir = File(root, "instances/${instance.id}/game").apply { mkdirs() }
        val nativesDir = File(root, "instances/${instance.id}/natives").apply { mkdirs() }
        val assetsDir = File(root, "assets").apply { mkdirs() }
        val librariesDir = File(root, "libraries").apply { mkdirs() }
        val nativeSearchPath = buildNativeSearchPath(
            context = context,
            nativesDir = nativesDir,
            rendererDirectory = renderer.directoryPath,
            lwjglNativeDirectory = lwjglNatives.directoryPath
        )

        val classpath = resolveClasspath(metadata, librariesDir, clientJar, androidLwjgl)
        val classpathString = classpath.joinToString(File.pathSeparator)
        val placeholders = buildPlaceholders(
            instance = instance,
            account = account,
            metadata = metadata,
            gameDir = gameDir,
            nativesDirectoryValue = nativeSearchPath,
            assetsDir = assetsDir,
            librariesDir = librariesDir,
            classpath = classpathString,
            assetIndexId = installedInfo.assetIndexId
        )

        val jvmArguments = buildList {
            add("-Xms512M")
            add("-Xmx${instance.memoryMb.coerceAtLeast(768)}M")
            add("-Dorg.lwjgl.librarypath=${lwjglNatives.directoryPath}")
            add("-Dorg.lwjgl.system.allocator=system")

            val metadataJvm = metadata.optJSONObject("arguments")?.optJSONArray("jvm")
            if (metadataJvm != null) {
                addAll(resolveArgumentArray(metadataJvm, placeholders))
            } else {
                add("-Djava.library.path=$nativeSearchPath")
                add("-cp")
                add(classpathString)
            }

            if (none { it == "-cp" || it == "-classpath" }) {
                add("-cp")
                add(classpathString)
            }
        }

        val gameArguments = metadata.optJSONObject("arguments")?.optJSONArray("game")?.let {
            resolveArgumentArray(it, placeholders)
        } ?: resolveLegacyGameArguments(
            metadata.optString("minecraftArguments"),
            placeholders
        )

        val nativeEnvironment = NativeRuntimeValidator.buildEnvironment(context, runtime).toMutableMap()
        val rendererEnvironment = RendererComponentManager.environment(context, renderer)
        val existingLd = nativeEnvironment["LD_LIBRARY_PATH"].orEmpty()
        nativeEnvironment["LD_LIBRARY_PATH"] = listOf(
            lwjglNatives.directoryPath,
            renderer.directoryPath,
            existingLd
        ).filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)

        val environment = linkedMapOf<String, String>().apply {
            putAll(nativeEnvironment)
            putAll(rendererEnvironment)
            put("ASTRA_GAME_DIR", gameDir.absolutePath)
            put("ASTRA_RENDERER", instance.renderer.name)
            put("ASTRA_RENDERER_RESOLVED", renderer.id)
            put("ASTRA_PERFORMANCE_MODE", instance.performanceMode.name)
            put("ASTRA_LWJGL_VERSION", androidLwjgl.version)
            put("ASTRA_LWJGL_DIR", androidLwjgl.directoryPath)
            put("ASTRA_LWJGL_NATIVE_DIR", lwjglNatives.directoryPath)
            put("ASTRA_LWJGL_NATIVE_ABI", lwjglNatives.abi)
            put("ASTRA_LWJGL_NATIVE_COUNT", lwjglNatives.libraryPaths.size.toString())
        }

        val warnings = buildList {
            add("JLI + MobileGlues + LWJGL Android JARs/natives + ANativeWindow preparados; bridge GLFW em validação de janela real.")
            if (account.type == AccountType.OFFLINE) {
                add("Conta offline: válida para single-player/LAN e servidores que aceitam identidades offline.")
            }
            if (metadata.has("inheritsFrom")) {
                add("Metadata herdada detectada; loaders/modpacks terão resolução de herança em uma etapa própria.")
            }
        }

        return LaunchPlan(
            instanceId = instance.id,
            minecraftVersion = instance.minecraftVersion,
            javaMajorVersion = installedInfo.javaMajorVersion,
            javaExecutable = runtime.javaPath,
            workingDirectory = gameDir.absolutePath,
            mainClass = metadata.optString("mainClass", installedInfo.mainClass),
            classpath = classpath.map(File::getAbsolutePath),
            jvmArguments = jvmArguments,
            gameArguments = gameArguments,
            environment = environment,
            warnings = warnings
        )
    }

    private fun buildNativeSearchPath(
        context: Context,
        nativesDir: File,
        rendererDirectory: String,
        lwjglNativeDirectory: String
    ): String = listOf(
        lwjglNativeDirectory,
        nativesDir.absolutePath,
        rendererDirectory,
        context.applicationInfo.nativeLibraryDir.orEmpty()
    ).filter { it.isNotBlank() }
        .distinct()
        .joinToString(File.pathSeparator)

    private fun resolveClasspath(
        metadata: JSONObject,
        librariesDir: File,
        clientJar: File,
        androidLwjgl: InstalledAndroidLwjgl
    ): List<File> {
        val result = androidLwjgl.jarPaths.map(::File).toMutableList()
        val libraries = metadata.optJSONArray("libraries") ?: JSONArray()

        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            if (!MinecraftRuleEvaluator.allows(library.optJSONArray("rules"))) continue

            val coordinate = library.optString("name")
            if (AndroidLwjglManager.shouldReplaceMojangLibrary(coordinate, androidLwjgl)) {
                continue
            }

            val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val path = artifact.optString("path")
            if (path.isBlank()) continue

            val file = File(librariesDir, path)
            if (!file.isFile) {
                error("Library obrigatória ausente: $path")
            }
            result += file
        }

        result += clientJar
        return result.distinctBy { it.absolutePath }
    }

    private fun resolveArgumentArray(
        array: JSONArray,
        placeholders: Map<String, String>
    ): List<String> = buildList {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is String -> add(expand(value, placeholders))
                is JSONObject -> {
                    if (!MinecraftRuleEvaluator.allows(value.optJSONArray("rules"))) continue
                    when (val argumentValue = value.opt("value")) {
                        is String -> add(expand(argumentValue, placeholders))
                        is JSONArray -> {
                            for (valueIndex in 0 until argumentValue.length()) {
                                val item = argumentValue.optString(valueIndex)
                                if (item.isNotBlank()) add(expand(item, placeholders))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveLegacyGameArguments(
        raw: String,
        placeholders: Map<String, String>
    ): List<String> {
        if (raw.isBlank()) return emptyList()
        return tokenize(raw).map { expand(it, placeholders) }
    }

    private fun buildPlaceholders(
        instance: MinecraftInstance,
        account: AccountProfile,
        metadata: JSONObject,
        gameDir: File,
        nativesDirectoryValue: String,
        assetsDir: File,
        librariesDir: File,
        classpath: String,
        assetIndexId: String
    ): Map<String, String> {
        val versionType = metadata.optString("type", "release")
        val accessToken = if (account.type == AccountType.OFFLINE) "0" else ""
        val session = if (account.type == AccountType.OFFLINE) {
            "token:0:${account.uuid}"
        } else {
            ""
        }

        return mapOf(
            "${'$'}{auth_player_name}" to account.username,
            "${'$'}{version_name}" to instance.minecraftVersion,
            "${'$'}{game_directory}" to gameDir.absolutePath,
            "${'$'}{assets_root}" to assetsDir.absolutePath,
            "${'$'}{game_assets}" to File(assetsDir, "virtual/legacy").absolutePath,
            "${'$'}{assets_index_name}" to assetIndexId,
            "${'$'}{auth_uuid}" to account.uuid.replace("-", ""),
            "${'$'}{auth_access_token}" to accessToken,
            "${'$'}{auth_session}" to session,
            "${'$'}{clientid}" to "",
            "${'$'}{auth_xuid}" to "",
            "${'$'}{user_type}" to if (account.type == AccountType.OFFLINE) "legacy" else "msa",
            "${'$'}{user_properties}" to "{}",
            "${'$'}{version_type}" to versionType,
            "${'$'}{natives_directory}" to nativesDirectoryValue,
            "${'$'}{launcher_name}" to LAUNCHER_NAME,
            "${'$'}{launcher_version}" to LAUNCHER_VERSION,
            "${'$'}{classpath}" to classpath,
            "${'$'}{classpath_separator}" to File.pathSeparator,
            "${'$'}{library_directory}" to librariesDir.absolutePath,
            "${'$'}{resolution_width}" to "1280",
            "${'$'}{resolution_height}" to "720"
        )
    }

    private fun expand(value: String, placeholders: Map<String, String>): String {
        var expanded = value
        placeholders.forEach { (key, replacement) ->
            expanded = expanded.replace(key, replacement)
        }
        return expanded
    }

    private fun tokenize(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        raw.forEach { char ->
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }

        if (escaping) current.append('\\')
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
