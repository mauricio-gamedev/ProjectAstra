package io.github.astromg01.astra.expansion

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class SimpleHttpClient(
    private val userAgent: String = "ProjectAstra/next (+https://github.com/mauricio-gamedev/ProjectAstra)",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun getText(url: String): String {
        val connection = open(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    fun download(url: String, target: File, maxBytes: Long = 512L * 1024 * 1024): File {
        target.parentFile?.mkdirs()
        val connection = open(url)
        val length = connection.contentLengthLong
        require(length <= 0 || length <= maxBytes) { "Remote file too large: $length bytes" }
        connection.inputStream.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Download exceeded limit of $maxBytes bytes" }
                    output.write(buffer, 0, count)
                }
            }
        }
        return target
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
        connection.connect()
        require(connection.responseCode in 200..299) {
            "HTTP ${connection.responseCode} for $url"
        }
        return connection
    }
}

interface LoaderProvider {
    val type: LoaderType
    fun listVersions(minecraftVersion: String): List<String>
    fun resolve(minecraftVersion: String, loaderVersion: String? = null): LoaderInstallPlan
}

class LoaderRegistry(http: SimpleHttpClient = SimpleHttpClient()) {
    private val providers: Map<LoaderType, LoaderProvider> = listOf(
        VanillaProvider(),
        FabricProvider(http),
        QuiltProvider(http),
        ForgeProvider(http),
        NeoForgeProvider(http),
    ).associateBy { it.type }

    fun provider(type: LoaderType): LoaderProvider = providers[type] ?: error("No provider for $type")
    fun supportedTypes(): Set<LoaderType> = providers.keys
}

class VanillaProvider : LoaderProvider {
    override val type = LoaderType.VANILLA
    override fun listVersions(minecraftVersion: String): List<String> = listOf(minecraftVersion)
    override fun resolve(minecraftVersion: String, loaderVersion: String?): LoaderInstallPlan =
        LoaderInstallPlan(
            loader = LoaderSelection(type, null),
            minecraftVersion = minecraftVersion,
            artifacts = emptyList(),
            notes = listOf("Vanilla uses the instance's resolved Minecraft version metadata."),
        )
}

class FabricProvider(private val http: SimpleHttpClient) : LoaderProvider {
    override val type = LoaderType.FABRIC

    override fun listVersions(minecraftVersion: String): List<String> = entries(minecraftVersion).map { it.loaderVersion }

    override fun resolve(minecraftVersion: String, loaderVersion: String?): LoaderInstallPlan {
        val entry = entries(minecraftVersion).firstOrNull { loaderVersion == null || it.loaderVersion == loaderVersion }
            ?: error("No Fabric loader found for Minecraft $minecraftVersion${loaderVersion?.let { " / $it" } ?: ""}")
        val profileUrl = "https://meta.fabricmc.net/v2/versions/loader/$minecraftVersion/${entry.loaderVersion}/profile/json"
        val profileJson = http.getText(profileUrl)
        val profile = AstraJson.parse(profileJson) as JsonValue.Obj
        return LoaderInstallPlan(
            loader = LoaderSelection(type, entry.loaderVersion),
            minecraftVersion = minecraftVersion,
            artifacts = emptyList(),
            profileId = profile.string("id"),
            profileJson = profileJson,
            mainClass = profile.string("mainClass"),
            libraries = parseProfileLibraries(profile),
            notes = listOf("Fabric profile metadata resolved from Fabric Meta API."),
        )
    }

    private fun entries(minecraftVersion: String): List<Entry> {
        val root = AstraJson.parse(http.getText("https://meta.fabricmc.net/v2/versions/loader/$minecraftVersion")) as JsonValue.Arr
        return root.values.mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            val loader = obj.obj("loader") ?: return@mapNotNull null
            val version = loader.string("version") ?: return@mapNotNull null
            Entry(version)
        }
    }

    private data class Entry(val loaderVersion: String)
}

class QuiltProvider(private val http: SimpleHttpClient) : LoaderProvider {
    override val type = LoaderType.QUILT

    override fun listVersions(minecraftVersion: String): List<String> = entries(minecraftVersion).map { it.loaderVersion }

    override fun resolve(minecraftVersion: String, loaderVersion: String?): LoaderInstallPlan {
        val entry = entries(minecraftVersion).firstOrNull { loaderVersion == null || it.loaderVersion == loaderVersion }
            ?: error("No Quilt loader found for Minecraft $minecraftVersion")
        val profileUrl = "https://meta.quiltmc.org/v3/versions/loader/$minecraftVersion/${entry.loaderVersion}/profile/json"
        val profileJson = http.getText(profileUrl)
        val profile = AstraJson.parse(profileJson) as JsonValue.Obj
        return LoaderInstallPlan(
            loader = LoaderSelection(type, entry.loaderVersion),
            minecraftVersion = minecraftVersion,
            artifacts = emptyList(),
            profileId = profile.string("id"),
            profileJson = profileJson,
            mainClass = profile.string("mainClass"),
            libraries = parseProfileLibraries(profile),
            installerRequired = false,
            notes = listOf("Quilt launcher profile resolved from the official Quilt Meta v3 API."),
        )
    }

    private fun entries(minecraftVersion: String): List<Entry> {
        val root = AstraJson.parse(http.getText("https://meta.quiltmc.org/v3/versions/loader/$minecraftVersion")) as JsonValue.Arr
        return root.values.mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            val loader = obj.obj("loader") ?: return@mapNotNull null
            val version = loader.string("version") ?: return@mapNotNull null
            Entry(version)
        }
    }

    private data class Entry(val loaderVersion: String)
}

private fun parseProfileLibraries(profile: JsonValue.Obj): List<LoaderLibrary> =
    profile.array("libraries")?.values.orEmpty().mapNotNull { value ->
        val obj = value as? JsonValue.Obj ?: return@mapNotNull null
        val name = obj.string("name") ?: return@mapNotNull null
        val artifact = obj.obj("downloads")?.obj("artifact")
        LoaderLibrary(
            name = name,
            repositoryUrl = obj.string("url"),
            directUrl = artifact?.string("url"),
            sha1 = artifact?.string("sha1"),
            size = artifact?.number("size")?.toLong(),
        )
    }

class LoaderProfileMaterializer(private val http: SimpleHttpClient = SimpleHttpClient()) {
    fun materialize(minecraftHome: File, plan: LoaderInstallPlan): File {
        val profileId = plan.profileId ?: error("Loader ${plan.loader.type} did not provide a launcher profile id")
        val profileJson = plan.profileJson ?: error("Loader ${plan.loader.type} did not provide launcher profile JSON")
        require(plan.loader.type == LoaderType.FABRIC || plan.loader.type == LoaderType.QUILT) {
            "Direct profile materialization is only valid for Fabric/Quilt"
        }

        val librariesRoot = File(minecraftHome, "libraries")
        plan.libraries.forEach { library ->
            val coordinate = MavenCoordinate.parse(library.name)
            val relativePath = coordinate.relativePath()
            val target = File(librariesRoot, relativePath)
            if (!target.exists()) {
                val url = library.directUrl ?: coordinate.downloadUrl(library.repositoryUrl
                    ?: error("No repository URL for ${library.name}"))
                validateHttps(url)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".astra.part")
                temp.delete()
                http.download(url, temp, maxBytes = 64L * 1024 * 1024)
                library.size?.let { require(temp.length() == it) { "Size mismatch for ${library.name}" } }
                library.sha1?.let { require(Hashing.sha1(temp).equals(it, ignoreCase = true)) { "SHA-1 mismatch for ${library.name}" } }
                if (target.exists() && !target.delete()) error("Could not replace ${target.name}")
                require(temp.renameTo(target)) { "Could not finalize ${library.name}" }
            }
        }

        val versionDir = File(File(minecraftHome, "versions"), profileId)
        versionDir.mkdirs()
        val profileFile = File(versionDir, "$profileId.json")
        val tempProfile = File(versionDir, ".$profileId.json.astra.tmp")
        tempProfile.writeText(profileJson)
        require(AstraJson.parse(tempProfile.readText()) is JsonValue.Obj) { "Invalid loader profile JSON" }
        if (profileFile.exists() && !profileFile.delete()) error("Could not replace ${profileFile.name}")
        require(tempProfile.renameTo(profileFile)) { "Could not finalize loader profile" }
        return profileFile
    }

    private fun validateHttps(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Refusing non-HTTPS loader artifact: $url" }
        require(!uri.host.isNullOrBlank()) { "Loader artifact URL has no host: $url" }
    }
}

internal data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    fun relativePath(): String {
        val fileName = buildString {
            append(artifact).append('-').append(version)
            classifier?.let { append('-').append(it) }
            append('.').append(extension)
        }
        return group.replace('.', '/') + "/$artifact/$version/$fileName"
    }

    fun downloadUrl(repositoryUrl: String): String = repositoryUrl.trimEnd('/') + "/" + relativePath()

    companion object {
        fun parse(raw: String): MavenCoordinate {
            val splitExtension = raw.split('@', limit = 2)
            val parts = splitExtension[0].split(':')
            require(parts.size in 3..4) { "Unsupported Maven coordinate: $raw" }
            return MavenCoordinate(
                group = parts[0], artifact = parts[1], version = parts[2],
                classifier = parts.getOrNull(3), extension = splitExtension.getOrNull(1) ?: "jar",
            )
        }
    }
}

class ForgeProvider(private val http: SimpleHttpClient) : LoaderProvider {
    override val type = LoaderType.FORGE

    override fun listVersions(minecraftVersion: String): List<String> =
        MavenMetadata.versions(http.getText(METADATA)).filter { it.startsWith("$minecraftVersion-") }

    override fun resolve(minecraftVersion: String, loaderVersion: String?): LoaderInstallPlan {
        val coordinateVersion = listVersions(minecraftVersion).firstOrNull { loaderVersion == null || it == loaderVersion || it.substringAfter('-') == loaderVersion }
            ?: error("No Forge build found for Minecraft $minecraftVersion")
        val url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$coordinateVersion/forge-$coordinateVersion-installer.jar"
        return LoaderInstallPlan(
            loader = LoaderSelection(type, coordinateVersion.substringAfter('-')),
            minecraftVersion = minecraftVersion,
            artifacts = listOf(RemoteArtifact(url, "installers/forge-$coordinateVersion-installer.jar")),
            installerRequired = true,
            notes = listOf(
                "Forge installer artifact resolved from the official Forge Maven.",
                "Android processor execution must run through Astra's validated Java integration; desktop installer assumptions are not executed blindly."
            ),
        )
    }

    companion object { private const val METADATA = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml" }
}

internal object MavenMetadata {
    fun versions(xml: String): List<String> = Regex("<version>([^<]+)</version>")
        .findAll(xml).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList().asReversed()
}

class NeoForgeProvider(private val http: SimpleHttpClient) : LoaderProvider {
    override val type = LoaderType.NEOFORGE

    override fun listVersions(minecraftVersion: String): List<String> {
        val mcTail = minecraftVersion.split('.').drop(1).joinToString(".")
        return MavenMetadata.versions(http.getText(METADATA)).filter { it.startsWith(mcTail) }
    }

    override fun resolve(minecraftVersion: String, loaderVersion: String?): LoaderInstallPlan {
        val version = listVersions(minecraftVersion).firstOrNull { loaderVersion == null || it == loaderVersion }
            ?: error("No NeoForge build found for Minecraft $minecraftVersion")
        val url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
        return LoaderInstallPlan(
            loader = LoaderSelection(type, version),
            minecraftVersion = minecraftVersion,
            artifacts = listOf(RemoteArtifact(url, "installers/neoforge-$version-installer.jar")),
            installerRequired = true,
            notes = listOf(
                "NeoForge installer artifact resolved from the official NeoForged Maven.",
                "Installer processors remain isolated from the graphics/input core."
            ),
        )
    }

    companion object { private const val METADATA = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" }
}
