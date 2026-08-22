package io.github.astromg01.astra.expansion

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object Hashing {
    fun sha1(file: File): String = digest(file, "SHA-1")
    fun sha512(file: File): String = digest(file, "SHA-512")

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class ModrinthClient(private val http: SimpleHttpClient = SimpleHttpClient()) {
    fun search(query: String, minecraftVersion: String? = null, loader: LoaderType? = null, limit: Int = 20): List<ModProject> {
        val facets = mutableListOf<String>()
        minecraftVersion?.let { facets += "versions:$it" }
        loader?.takeIf { it != LoaderType.VANILLA }?.let { facets += "categories:${it.name.lowercase()}" }
        facets += "project_type:mod"

        val url = buildString {
            append("https://api.modrinth.com/v2/search?query=")
            append(enc(query))
            append("&limit=").append(limit.coerceIn(1, 100))
            if (facets.isNotEmpty()) {
                val encodedFacets = facets.joinToString(prefix = "[[\"", separator = "\"],[\"", postfix = "\"]]")
                append("&facets=").append(enc(encodedFacets))
            }
        }
        val root = AstraJson.parse(http.getText(url)) as JsonValue.Obj
        val hits = root.array("hits")?.values.orEmpty()
        return hits.mapNotNull { hit ->
            val obj = hit as? JsonValue.Obj ?: return@mapNotNull null
            val projectId = obj.string("project_id") ?: return@mapNotNull null
            val slug = obj.string("slug") ?: projectId
            val title = obj.string("title") ?: slug
            val description = obj.string("description") ?: ""
            val versions = obj.array("versions")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }.toSet()
            val loaders = obj.array("categories")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value?.toLoader() }.toSet()
            ModProject(projectId, slug, title, description, ModSource.MODRINTH, loaders, versions)
        }
    }

    fun compatibleVersions(projectIdOrSlug: String, minecraftVersion: String, loader: LoaderType): List<ModVersion> {
        val loaderName = loader.name.lowercase()
        val url = "https://api.modrinth.com/v2/project/${enc(projectIdOrSlug)}/version?game_versions=${enc("[\"$minecraftVersion\"]")}&loaders=${enc("[\"$loaderName\"]") }"
        val root = AstraJson.parse(http.getText(url)) as JsonValue.Arr
        return root.values.mapNotNull { parseVersion(it as? JsonValue.Obj ?: return@mapNotNull null) }
    }

    fun version(versionId: String): ModVersion {
        val obj = AstraJson.parse(http.getText("https://api.modrinth.com/v2/version/${enc(versionId)}")) as JsonValue.Obj
        return parseVersion(obj) ?: error("Invalid Modrinth version payload")
    }

    private fun parseVersion(obj: JsonValue.Obj): ModVersion? {
        val id = obj.string("id") ?: return null
        val projectId = obj.string("project_id") ?: return null
        val name = obj.string("name") ?: id
        val versionNumber = obj.string("version_number") ?: id
        val gameVersions = obj.array("game_versions")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }.toSet()
        val loaders = obj.array("loaders")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value?.toLoader() }.toSet()
        val files = obj.array("files")?.values.orEmpty().mapNotNull { fileValue ->
            val file = fileValue as? JsonValue.Obj ?: return@mapNotNull null
            val url = file.string("url") ?: return@mapNotNull null
            val fileName = file.string("filename") ?: return@mapNotNull null
            val hashes = file.obj("hashes")
            ModFile(
                url = url,
                fileName = fileName,
                primary = file.bool("primary") ?: false,
                sha1 = hashes?.string("sha1"),
                sha512 = hashes?.string("sha512"),
                size = file.number("size")?.toLong(),
            )
        }
        val dependencies = obj.array("dependencies")?.values.orEmpty().mapNotNull { depValue ->
            val dep = depValue as? JsonValue.Obj ?: return@mapNotNull null
            val type = dep.string("dependency_type") ?: return@mapNotNull null
            ModDependency(
                projectId = dep.string("project_id"),
                versionId = dep.string("version_id"),
                required = type == "required",
            )
        }
        return ModVersion(id, projectId, name, versionNumber, gameVersions, loaders, files, dependencies)
    }

    private fun String.toLoader(): LoaderType? = when (lowercase()) {
        "fabric" -> LoaderType.FABRIC
        "quilt" -> LoaderType.QUILT
        "forge" -> LoaderType.FORGE
        "neoforge" -> LoaderType.NEOFORGE
        else -> null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

class ModInstaller(private val http: SimpleHttpClient = SimpleHttpClient()) {
    fun install(instance: MinecraftInstance, slug: String, version: ModVersion): InstalledMod {
        require(version.gameVersions.contains(instance.minecraftVersion)) {
            "Mod ${version.name} does not support Minecraft ${instance.minecraftVersion}"
        }
        require(instance.loader.type == LoaderType.VANILLA || version.loaders.contains(instance.loader.type)) {
            "Mod ${version.name} does not support loader ${instance.loader.type}"
        }
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: error("Mod version ${version.id} has no downloadable file")
        val safeName = file.fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._+() -]"), "_")
        require(safeName.endsWith(".jar", ignoreCase = true)) { "Refusing non-JAR mod payload: $safeName" }
        val modsDir = File(instance.root, "mods")
        val target = File(modsDir, safeName)
        val temp = File(modsDir, ".$safeName.part")
        temp.delete()
        http.download(file.url, temp, maxBytes = 256L * 1024 * 1024)
        verify(temp, file)
        if (target.exists() && !target.delete()) error("Could not replace existing mod $safeName")
        require(temp.renameTo(target)) { "Could not finalize mod install: $safeName" }
        return InstalledMod(version.projectId, version.id, slug, target)
    }

    private fun verify(file: File, expected: ModFile) {
        expected.size?.let { require(file.length() == it) { "Downloaded size mismatch for ${expected.fileName}" } }
        expected.sha512?.let { require(Hashing.sha512(file).equals(it, ignoreCase = true)) { "SHA-512 mismatch for ${expected.fileName}" } }
            ?: expected.sha1?.let { require(Hashing.sha1(file).equals(it, ignoreCase = true)) { "SHA-1 mismatch for ${expected.fileName}" } }
            ?: error("Provider did not supply an integrity hash for ${expected.fileName}")
    }
}

class ModManager(
    private val modrinth: ModrinthClient = ModrinthClient(),
    private val installer: ModInstaller = ModInstaller(),
) {
    fun search(instance: MinecraftInstance, query: String): List<ModProject> =
        modrinth.search(query, instance.minecraftVersion, instance.loader.type.takeUnless { it == LoaderType.VANILLA })

    fun installLatestCompatible(instance: MinecraftInstance, projectIdOrSlug: String): List<InstalledMod> {
        require(instance.loader.type != LoaderType.VANILLA) { "Mod installation requires a mod loader for this workflow." }
        val versions = modrinth.compatibleVersions(projectIdOrSlug, instance.minecraftVersion, instance.loader.type)
        val selected = versions.firstOrNull() ?: error("No compatible version found for $projectIdOrSlug")
        val installed = mutableListOf<InstalledMod>()
        installRecursive(instance, projectIdOrSlug, selected, linkedSetOf(), installed)
        return installed
    }

    fun listInstalled(instance: MinecraftInstance): List<File> =
        File(instance.root, "mods").listFiles()?.filter { it.isFile && it.extension.equals("jar", true) }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun disable(instance: MinecraftInstance, modFile: File): File {
        require(modFile.parentFile?.canonicalFile == File(instance.root, "mods").canonicalFile) { "Mod is outside this instance" }
        val disabled = File(modFile.parentFile, modFile.name + ".disabled")
        require(modFile.renameTo(disabled)) { "Could not disable ${modFile.name}" }
        return disabled
    }

    private fun installRecursive(
        instance: MinecraftInstance,
        slugOrProjectId: String,
        version: ModVersion,
        visitedVersionIds: MutableSet<String>,
        installed: MutableList<InstalledMod>,
    ) {
        if (!visitedVersionIds.add(version.id)) return
        validateCompatibility(instance, version)

        for (dependency in version.dependencies.filter { it.required }) {
            val depVersion = when {
                dependency.versionId != null -> modrinth.version(dependency.versionId)
                dependency.projectId != null -> modrinth.compatibleVersions(
                    dependency.projectId,
                    instance.minecraftVersion,
                    instance.loader.type,
                ).firstOrNull() ?: error("No compatible required dependency ${dependency.projectId} for ${version.name}")
                else -> error("Required dependency metadata is incomplete for ${version.name}")
            }
            installRecursive(instance, dependency.projectId ?: depVersion.projectId, depVersion, visitedVersionIds, installed)
        }

        if (installed.none { it.versionId == version.id }) {
            installed += installer.install(instance, slugOrProjectId, version)
        }
    }

    private fun validateCompatibility(instance: MinecraftInstance, version: ModVersion) {
        require(instance.minecraftVersion in version.gameVersions) {
            "Required mod ${version.name} does not support Minecraft ${instance.minecraftVersion}"
        }
        require(instance.loader.type in version.loaders) {
            "Required mod ${version.name} does not support ${instance.loader.type}"
        }
    }
}
