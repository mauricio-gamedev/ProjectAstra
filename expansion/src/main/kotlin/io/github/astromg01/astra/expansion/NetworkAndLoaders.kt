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

    fun provider(type: LoaderType): LoaderProvider =
        providers[type] ?: error("No provider for $type")

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
        val profile = AstraJson.parse(http.getText(profileUrl)) as JsonValue.Obj
        val libraries = profile.array("libraries")?.values.orEmpty().mapNotNull { (it as? JsonValue.Obj)?.string("name") }
        return LoaderInstallPlan(
            loader = LoaderSelection(type, entry.loaderVersion),
            minecraftVersion = minecraftVersion,
            artifacts = emptyList(),
            mainClass = profile.string("mainClass"),
            libraries = libraries,
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
        return LoaderInstallPlan(
            loader = LoaderSelection(type, entry.loaderVersion),
            minecraftVersion = minecraftVersion,
            artifacts = emptyList(),
            installerRequired = true,
            notes = listOf(
                "Quilt version compatibility resolved from Quilt Meta API.",
                "Profile materialization is delegated to the launcher integration layer so the Android launch core remains authoritative."
            ),
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
