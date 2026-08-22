package io.github.astromg01.astra.expansion

import java.io.File
import java.util.zip.ZipFile

data class DetectedMod(
    val id: String,
    val name: String,
    val version: String?,
    val sourceFormat: String,
    val file: File,
)

class InstalledModScanner {
    fun scan(instance: MinecraftInstance): List<DetectedMod> {
        val modsDir = File(instance.root, "mods")
        return modsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.flatMap { scanJar(it) }
            ?.distinctBy { it.id.lowercase() to it.file.canonicalPath }
            .orEmpty()
    }

    fun scanJar(jar: File): List<DetectedMod> = runCatching {
        ZipFile(jar).use { zip ->
            when {
                zip.getEntry("fabric.mod.json") != null -> parseFabric(zip.readText("fabric.mod.json"), jar)
                zip.getEntry("quilt.mod.json") != null -> parseQuilt(zip.readText("quilt.mod.json"), jar)
                zip.getEntry("META-INF/neoforge.mods.toml") != null -> parseForgeLike(zip.readText("META-INF/neoforge.mods.toml"), jar, "neoforge.mods.toml")
                zip.getEntry("META-INF/mods.toml") != null -> parseForgeLike(zip.readText("META-INF/mods.toml"), jar, "mods.toml")
                zip.getEntry("optifine/OptiFineClassTransformer.class") != null || jar.name.contains("optifine", ignoreCase = true) ->
                    listOf(DetectedMod("optifine", "OptiFine", versionFromFileName(jar.name), "optifine", jar))
                else -> emptyList()
            }
        }
    }.getOrElse { emptyList() }

    private fun parseFabric(json: String, jar: File): List<DetectedMod> {
        val obj = AstraJson.parse(json) as? JsonValue.Obj ?: return emptyList()
        val id = obj.string("id") ?: return emptyList()
        return listOf(DetectedMod(id, obj.string("name") ?: id, obj.string("version"), "fabric.mod.json", jar))
    }

    private fun parseQuilt(json: String, jar: File): List<DetectedMod> {
        val obj = AstraJson.parse(json) as? JsonValue.Obj ?: return emptyList()
        val loader = obj.obj("quilt_loader") ?: return emptyList()
        val id = loader.string("id") ?: return emptyList()
        val metadata = loader.obj("metadata")
        return listOf(DetectedMod(id, metadata?.string("name") ?: id, loader.string("version"), "quilt.mod.json", jar))
    }

    private fun parseForgeLike(toml: String, jar: File, source: String): List<DetectedMod> {
        val blocks = Regex("(?ms)^\\[\\[mods]]\\s*(.*?)(?=^\\[\\[|\\z)").findAll(toml).map { it.groupValues[1] }.toList()
        val candidates = if (blocks.isEmpty()) listOf(toml) else blocks
        return candidates.mapNotNull { block ->
            val id = tomlValue(block, "modId") ?: return@mapNotNull null
            val name = tomlValue(block, "displayName") ?: id
            val version = tomlValue(block, "version")?.takeUnless { it.startsWith("${'$'}{") }
            DetectedMod(id, name, version, source, jar)
        }
    }

    private fun tomlValue(block: String, key: String): String? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*[\"']([^\"']+)[\"']").find(block)?.groupValues?.get(1)

    private fun ZipFile.readText(path: String): String =
        getInputStream(getEntry(path)).bufferedReader().use { it.readText() }

    private fun versionFromFileName(name: String): String? =
        Regex("(?i)optifine[_-]([A-Za-z0-9._-]+)").find(name)?.groupValues?.get(1)
}
