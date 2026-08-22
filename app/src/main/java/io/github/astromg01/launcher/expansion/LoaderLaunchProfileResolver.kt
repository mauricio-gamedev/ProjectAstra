package io.github.astromg01.launcher.expansion

import android.content.Context
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.launch.LaunchPlan
import io.github.astromg01.launcher.launch.MinecraftRuleEvaluator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Applies an already-installed loader version profile over Astra's proven vanilla plan.
 * No network or installer processor is executed here.
 */
object LoaderLaunchProfileResolver {
    fun apply(context: Context, instance: MinecraftInstance, base: LaunchPlan): LaunchPlan {
        val profileId = instance.loaderProfileId ?: return base
        val minecraftRoot = File(context.filesDir, "minecraft")
        val profileFile = File(minecraftRoot, "versions/$profileId/$profileId.json")
        require(profileFile.isFile) { "Profile do loader não encontrado: $profileId" }

        val profile = JSONObject(profileFile.readText())
        profile.optString("inheritsFrom").takeIf { it.isNotBlank() }?.let { parent ->
            require(parent == instance.minecraftVersion) {
                "Loader $profileId herda de $parent, mas a instância usa ${instance.minecraftVersion}."
            }
        }

        val librariesRoot = File(minecraftRoot, "libraries")
        val classpath = base.classpath.toMutableList()
        val libraries = profile.optJSONArray("libraries") ?: JSONArray()
        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            if (!MinecraftRuleEvaluator.allows(library.optJSONArray("rules"))) continue
            val path = resolveLibraryPath(library, librariesRoot) ?: continue
            require(path.isFile) { "Library do loader ausente: ${path.absolutePath}" }
            if (classpath.none { File(it).absolutePath == path.absolutePath }) {
                classpath += path.absolutePath
            }
        }

        val replacements = linkedMapOf(
            "${'$'}{library_directory}" to librariesRoot.absolutePath,
            "${'$'}{classpath_separator}" to File.pathSeparator,
            "${'$'}{version_name}" to profileId,
            "${'$'}{natives_directory}" to File(minecraftRoot, "instances/${instance.id}/natives").absolutePath,
            "${'$'}{launcher_name}" to "ProjectAstra",
            "${'$'}{launcher_version}" to "0.1.0-alpha40",
            "${'$'}{classpath}" to classpath.joinToString(File.pathSeparator),
        )

        val arguments = profile.optJSONObject("arguments")
        val loaderJvm = resolveArgumentArray(arguments?.optJSONArray("jvm"), replacements)
        val loaderGame = resolveArgumentArray(arguments?.optJSONArray("game"), replacements)

        val jvm = base.jvmArguments.toMutableList()
        loaderJvm.forEach { addJvmReplacingSameKey(jvm, it) }
        val game = base.gameArguments.toMutableList().apply {
            loaderGame.forEach { if (it !in this) add(it) }
        }

        val environment = LinkedHashMap(base.environment).apply {
            put("ASTRA_LOADER", instance.loader.orEmpty())
            put("ASTRA_LOADER_VERSION", instance.loaderVersion.orEmpty())
            put("ASTRA_LOADER_PROFILE", profileId)
        }

        return base.copy(
            mainClass = profile.optString("mainClass").takeIf { it.isNotBlank() } ?: base.mainClass,
            classpath = classpath,
            jvmArguments = jvm,
            gameArguments = game,
            environment = environment,
            warnings = (base.warnings + "Loader profile local aplicado: $profileId").distinct(),
        )
    }

    private fun resolveLibraryPath(library: JSONObject, librariesRoot: File): File? {
        val artifactPath = library.optJSONObject("downloads")
            ?.optJSONObject("artifact")
            ?.optString("path")
            ?.takeIf { it.isNotBlank() }
        if (artifactPath != null) return File(librariesRoot, artifactPath)

        val name = library.optString("name").takeIf { it.isNotBlank() } ?: return null
        return File(librariesRoot, mavenRelativePath(name))
    }

    private fun mavenRelativePath(raw: String): String {
        val splitExtension = raw.split('@', limit = 2)
        val parts = splitExtension[0].split(':')
        require(parts.size in 3..4) { "Coordenada Maven não suportada no loader: $raw" }
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)?.let { "-$it" }.orEmpty()
        val extension = splitExtension.getOrNull(1) ?: "jar"
        return "$group/$artifact/$version/$artifact-$version$classifier.$extension"
    }

    private fun resolveArgumentArray(array: JSONArray?, replacements: Map<String, String>): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is String -> add(replace(value, replacements))
                    is JSONObject -> {
                        if (!MinecraftRuleEvaluator.allows(value.optJSONArray("rules"))) continue
                        when (val nested = value.opt("value")) {
                            is String -> add(replace(nested, replacements))
                            is JSONArray -> for (nestedIndex in 0 until nested.length()) {
                                nested.optString(nestedIndex).takeIf { it.isNotBlank() }
                                    ?.let { add(replace(it, replacements)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun replace(value: String, replacements: Map<String, String>): String {
        var resolved = value
        replacements.forEach { (key, replacement) -> resolved = resolved.replace(key, replacement) }
        val unresolved = unresolvedPlaceholder(resolved)
        require(unresolved == null) {
            "Argumento do loader contém placeholder não resolvido: ${unresolved ?: resolved}"
        }
        return resolved
    }

    /**
     * Loader placeholders use the literal ${'$'}{name} syntax. A direct scan is intentional here:
     * it avoids constructing a regular expression during launch and therefore cannot fail with
     * PatternSyntaxException on Android's regex implementation.
     */
    private fun unresolvedPlaceholder(value: String): String? {
        val start = value.indexOf("${'$'}{")
        if (start < 0) return null
        val end = value.indexOf('}', startIndex = start + 2)
        return if (end >= 0) value.substring(start, end + 1) else value.substring(start)
    }

    private fun addJvmReplacingSameKey(args: MutableList<String>, candidate: String) {
        val key = when {
            candidate.startsWith("-D") -> candidate.substringBefore('=')
            candidate.startsWith("-Xmx") -> "-Xmx"
            candidate.startsWith("-Xms") -> "-Xms"
            candidate.startsWith("-XX:ActiveProcessorCount=") -> "-XX:ActiveProcessorCount="
            else -> null
        }
        if (key != null) {
            args.removeAll { existing ->
                when (key) {
                    "-Xmx", "-Xms" -> existing.startsWith(key)
                    "-XX:ActiveProcessorCount=" -> existing.startsWith(key)
                    else -> existing == key || existing.startsWith("$key=")
                }
            }
        }
        if (candidate !in args) args += candidate
    }
}
