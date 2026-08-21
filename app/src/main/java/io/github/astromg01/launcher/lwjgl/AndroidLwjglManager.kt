package io.github.astromg01.launcher.lwjgl

import android.content.Context
import io.github.astromg01.launcher.launch.MinecraftRuleEvaluator
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AndroidLwjglManager {
    private const val UPSTREAM_COMMIT = "360d708262ff703d9b52782d20cd348410a33df5"
    private const val UPSTREAM_REPOSITORY = "AngelAuraMC/Amethyst-Android"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val USER_AGENT = "ProjectAstra/0.1.0-alpha07"

    private data class FileSpec(
        val name: String,
        val gitBlobSha1: String,
        val size: Long
    )

    private data class Catalog(
        val version: String,
        val files: Map<String, FileSpec>,
        val separatelyPackagedArtifacts: Set<String>
    )

    private val catalogs = mapOf(
        "3.3.3" to Catalog(
            version = "3.3.3",
            files = listOf(
                FileSpec("jsr305.jar", "59222d9ca5e5654f5dcf6680783d0e265baa848f", 19_936),
                FileSpec("lwjgl-3.3.3-merged-modules.jar", "37518cb2c1cd9b298bd4165e2b9d18b414ff4196", 1_081_868),
                FileSpec("lwjgl-freetype.jar", "28cb82b13145430fd7e8fe27e281b79d20b9db18", 451_815),
                FileSpec("lwjgl-lwjglx.jar", "5e048e788112f09975ee0469220a1c6201856d58", 237_225),
                FileSpec("lwjgl-nanovg.jar", "79374dcd85c5580831507cd02bf5be93701ebc8e", 126_928),
                FileSpec("lwjgl-openal.jar", "1738288135d83676ce92dbe1633c53e323da312e", 109_538),
                FileSpec("lwjgl-shaderc.jar", "15ca06e9a0ce6a05e6a975ab6534fd19cda29b89", 24_400),
                FileSpec("lwjgl-spvc.jar", "8ca5a19098af1f6e22c9213f740e235cd4ead475", 127_690),
                FileSpec("lwjgl-stb.jar", "0480c8efc4d8359935e349f6f96eb3663a0b362f", 115_903),
                FileSpec("lwjgl-tinyfd.jar", "89d38b2c0824ce369cbe8e85476188d98167347a", 7_711),
                FileSpec("lwjgl-vma.jar", "a8757c2405b5689697449b8853cb84d47188de6c", 98_286),
                FileSpec("lwjgl-vulkan.jar", "ffb4a042b4becf6c282adcf1dae66355f810e22b", 6_106_103),
                FileSpec("lwjgl.jar", "85322cf449e8cec02ce9b698adf9e9908109b519", 793_777)
            ).associateBy { it.name },
            separatelyPackagedArtifacts = setOf(
                "lwjgl-freetype",
                "lwjgl-lwjglx",
                "lwjgl-nanovg",
                "lwjgl-openal",
                "lwjgl-shaderc",
                "lwjgl-spvc",
                "lwjgl-stb",
                "lwjgl-tinyfd",
                "lwjgl-vma",
                "lwjgl-vulkan"
            )
        ),
        "3.4.1" to Catalog(
            version = "3.4.1",
            files = listOf(
                FileSpec("lwjgl-3.4.1-merged-modules.jar", "13a5ab69ac726cfe7adfcbccee951a10c128f65c", 1_105_331),
                FileSpec("lwjgl-freetype.jar", "928e2828489ae7734b266c7614c9406862249720", 465_796),
                FileSpec("lwjgl-lwjglx.jar", "2d47d906f495d98248bfd5f91d0d6ec3429ca2eb", 237_121),
                FileSpec("lwjgl-nanovg.jar", "c0a962516299c71220298d9c3dde96cd80d98874", 76_348),
                FileSpec("lwjgl-openal.jar", "85a9de4d930da39bd2a7d22927fdf0bb3130a9c2", 150_981),
                FileSpec("lwjgl-sdl.jar", "1a9a04e37073cf34fd8c29ada8b6a8030a33877f", 992_333),
                FileSpec("lwjgl-shaderc.jar", "aa7dc9a5ea006d5068ba7ad1dc655b87e7fab6e0", 147_618),
                FileSpec("lwjgl-spng.jar", "46b8af6cbbaec61808e2473a5b346c9bc5e72f7b", 114_650),
                FileSpec("lwjgl-spvc.jar", "3ee06375eb621d650b4ef4e36200d5b9c47ce73b", 141_553),
                FileSpec("lwjgl-stb.jar", "a7db37cb621d3959cfe1fdf17113b171c0956ac4", 136_953),
                FileSpec("lwjgl-tinyfd.jar", "0b1ef1fb8bb7e1cc2723a65cc073573ce831d290", 7_677),
                FileSpec("lwjgl-vma.jar", "20dcad845e0e89251629f37ad408e264dd7ca983", 103_366),
                FileSpec("lwjgl-vulkan.jar", "236996afdd2d67e72adb71b7b117db8b0ed93b08", 8_540_645),
                FileSpec("lwjgl.jar", "f77c2f24ed0f8034d98195a3e2092dfc5e64d293", 1_169_477)
            ).associateBy { it.name },
            separatelyPackagedArtifacts = setOf(
                "lwjgl-freetype",
                "lwjgl-lwjglx",
                "lwjgl-nanovg",
                "lwjgl-openal",
                "lwjgl-sdl",
                "lwjgl-shaderc",
                "lwjgl-spng",
                "lwjgl-spvc",
                "lwjgl-stb",
                "lwjgl-tinyfd",
                "lwjgl-vma",
                "lwjgl-vulkan"
            )
        )
    )

    fun ensureForMinecraft(
        context: Context,
        minecraftVersion: String,
        onProgress: (LwjglProgress) -> Unit = {}
    ): InstalledAndroidLwjgl {
        val metadata = readVersionMetadata(context, minecraftVersion)
        val requirement = requirementFor(metadata)
        val root = componentRoot(context, requirement.version).apply { mkdirs() }
        val specs = requiredFiles(requirement)

        specs.forEachIndexed { index, spec ->
            val target = File(root, spec.name)
            if (!isValid(target, spec)) {
                target.delete()
                onProgress(
                    LwjglProgress(
                        stage = "Baixando LWJGL Android ${requirement.version}",
                        current = index,
                        total = specs.size,
                        detail = spec.name
                    )
                )
                downloadWithRetry(requirement.version, spec, target)
                if (!isValid(target, spec)) {
                    target.delete()
                    error("Falha de integridade no componente LWJGL: ${spec.name}")
                }
            }
        }

        val installed = buildInstalled(root, requirement, specs)
        writeMarker(root, installed, specs)
        onProgress(
            LwjglProgress(
                stage = "LWJGL Android pronto",
                current = specs.size,
                total = specs.size,
                detail = requirement.version
            )
        )
        return installed
    }

    fun installedForMetadata(context: Context, metadata: JSONObject): InstalledAndroidLwjgl {
        val requirement = requirementFor(metadata)
        val root = componentRoot(context, requirement.version)
        val specs = requiredFiles(requirement)
        val missing = specs.firstOrNull { spec ->
            val file = File(root, spec.name)
            !file.isFile || file.length() != spec.size
        }
        if (missing != null) {
            error("LWJGL Android ${requirement.version} incompleto: ${missing.name}")
        }
        return buildInstalled(root, requirement, specs)
    }

    fun shouldReplaceMojangLibrary(coordinate: String, installed: InstalledAndroidLwjgl): Boolean {
        val parsed = parseCoordinate(coordinate) ?: return false
        if (parsed.group != "org.lwjgl" || parsed.version != installed.version) return false
        return parsed.artifact in installed.replacedArtifacts
    }

    private fun requirementFor(metadata: JSONObject): LwjglRequirement {
        val libraries = metadata.optJSONArray("libraries") ?: JSONArray()
        val requested = linkedSetOf<String>()
        val versions = linkedSetOf<String>()

        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            if (!MinecraftRuleEvaluator.allows(library.optJSONArray("rules"))) continue
            val coordinate = parseCoordinate(library.optString("name")) ?: continue
            if (coordinate.group != "org.lwjgl") continue
            requested += coordinate.artifact
            versions += coordinate.version
        }

        if (versions.isEmpty()) {
            error("A metadata do Minecraft não declarou uma versão LWJGL compatível.")
        }
        if (versions.size != 1) {
            error("A metadata usa múltiplas versões LWJGL (${versions.joinToString()}); resolução híbrida ainda não é suportada.")
        }

        val version = versions.single()
        if (version !in catalogs) {
            error("LWJGL $version ainda não possui componente Android validado no Astra. Suportados: ${catalogs.keys.joinToString()}.")
        }
        return LwjglRequirement(version, requested)
    }

    private fun requiredFiles(requirement: LwjglRequirement): List<FileSpec> {
        val catalog = catalogs.getValue(requirement.version)
        val names = linkedSetOf(
            "lwjgl-${requirement.version}-merged-modules.jar",
            "lwjgl.jar"
        )
        if (requirement.version == "3.3.3") names += "jsr305.jar"

        requirement.artifacts.forEach { artifact ->
            if (artifact in catalog.separatelyPackagedArtifacts) {
                val fileName = "$artifact.jar"
                if (fileName in catalog.files) names += fileName
            }
        }

        return names.map { name ->
            catalog.files[name] ?: error("Catálogo LWJGL ${requirement.version} sem $name")
        }
    }

    private fun buildInstalled(
        root: File,
        requirement: LwjglRequirement,
        specs: List<FileSpec>
    ): InstalledAndroidLwjgl {
        val catalog = catalogs.getValue(requirement.version)
        val replaced = linkedSetOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl")
        requirement.artifacts.forEach { artifact ->
            if (artifact in catalog.separatelyPackagedArtifacts) replaced += artifact
        }

        return InstalledAndroidLwjgl(
            version = requirement.version,
            directoryPath = root.absolutePath,
            jarPaths = specs.map { File(root, it.name).absolutePath },
            replacedArtifacts = replaced,
            source = "$UPSTREAM_REPOSITORY@$UPSTREAM_COMMIT"
        )
    }

    private fun readVersionMetadata(context: Context, version: String): JSONObject {
        val file = File(context.filesDir, "minecraft/versions/$version/$version.json")
        if (!file.isFile) error("Metadata do Minecraft $version não encontrada.")
        return JSONObject(file.readText())
    }

    private fun componentRoot(context: Context, version: String): File =
        File(context.filesDir, "components/lwjgl3/$version")

    private fun rawUrl(version: String, fileName: String): String =
        "https://raw.githubusercontent.com/$UPSTREAM_REPOSITORY/$UPSTREAM_COMMIT/" +
            "app_pojavlauncher/src/main/assets/components/lwjgl3/$version/$fileName"

    private fun downloadWithRetry(version: String, spec: FileSpec, target: File) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadOnce(rawUrl(version, spec.name), target)
                return
            } catch (error: Throwable) {
                lastError = error
                target.delete()
                File(target.parentFile, "${target.name}.part").delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) Thread.sleep(650L * (attempt + 1))
            }
        }
        throw lastError ?: error("Falha ao baixar ${spec.name}")
    }

    private fun downloadOnce(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Download de ${target.name} retornou HTTP $code.")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValid(file: File, spec: FileSpec): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        return gitBlobSha1(file).equals(spec.gitBlobSha1, ignoreCase = true)
    }

    private fun gitBlobSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun writeMarker(root: File, installed: InstalledAndroidLwjgl, specs: List<FileSpec>) {
        val files = JSONArray()
        specs.forEach { spec ->
            files.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("size", spec.size)
                    .put("gitBlobSha1", spec.gitBlobSha1)
            )
        }
        File(root, ".astra-lwjgl.json").writeText(
            JSONObject()
                .put("version", installed.version)
                .put("upstreamCommit", UPSTREAM_COMMIT)
                .put("source", installed.source)
                .put("files", files)
                .toString()
        )
    }

    private fun parseCoordinate(raw: String): MavenCoordinate? {
        val parts = raw.split(':')
        if (parts.size < 3) return null
        return MavenCoordinate(parts[0], parts[1], parts[2])
    }

    private data class MavenCoordinate(
        val group: String,
        val artifact: String,
        val version: String
    )

    private data class LwjglRequirement(
        val version: String,
        val artifacts: Set<String>
    )
}

data class InstalledAndroidLwjgl(
    val version: String,
    val directoryPath: String,
    val jarPaths: List<String>,
    val replacedArtifacts: Set<String>,
    val source: String
)

data class LwjglProgress(
    val stage: String,
    val current: Int,
    val total: Int,
    val detail: String? = null
) {
    val percent: Int
        get() = if (total <= 0) 0 else ((current * 100) / total).coerceIn(0, 100)
}
