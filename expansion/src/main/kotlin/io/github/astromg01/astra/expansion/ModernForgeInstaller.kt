package io.github.astromg01.astra.expansion

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

data class JavaProcessResult(val exitCode: Int, val output: String)

fun interface JavaProcessRunner {
    fun run(javaExecutable: File, workingDirectory: File, arguments: List<String>): JavaProcessResult
}

class ProcessBuilderJavaRunner(private val maxCapturedChars: Int = 256_000) : JavaProcessRunner {
    override fun run(javaExecutable: File, workingDirectory: File, arguments: List<String>): JavaProcessResult {
        require(javaExecutable.isFile && javaExecutable.canExecute()) { "Java executable is not runnable: $javaExecutable" }
        val command = buildList { add(javaExecutable.absolutePath); addAll(arguments) }
        val process = ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true).start()
        val output = StringBuilder()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (output.length < maxCapturedChars) output.appendLine(line.take(maxCapturedChars - output.length))
            }
        }
        return JavaProcessResult(process.waitFor(), output.toString())
    }
}

data class ModernLoaderInstallResult(
    val versionId: String,
    val minecraftVersion: String,
    val processorsRun: Int,
    val processorsSkipped: Int,
    val profileFile: File,
)

/** Modern Forge/NeoForge V2 installer processor. Legacy Forge V1 is intentionally separate. */
class ModernForgeInstaller(
    private val http: SimpleHttpClient = SimpleHttpClient(),
    private val runner: JavaProcessRunner = ProcessBuilderJavaRunner(),
) {
    fun install(installerJar: File, minecraftHome: File, javaExecutable: File): ModernLoaderInstallResult {
        require(installerJar.isFile) { "Installer JAR is missing: $installerJar" }
        val librariesRoot = File(minecraftHome, "libraries").apply { mkdirs() }
        val tempRoot = File(minecraftHome, ".astra/loader-install/${installerJar.nameWithoutExtension}").apply {
            deleteRecursively(); mkdirs()
        }

        ZipFile(installerJar).use { zip ->
            val profileText = zip.readRequiredText("install_profile.json")
            val versionText = zip.readRequiredText("version.json")
            val profile = AstraJson.parse(profileText) as? JsonValue.Obj ?: error("Invalid install_profile.json")
            val version = AstraJson.parse(versionText) as? JsonValue.Obj ?: error("Invalid version.json")
            val versionId = version.string("id") ?: error("version.json has no id")
            val minecraftVersion = profile.string("minecraft") ?: error("install_profile.json has no minecraft version")

            require(File(minecraftHome, "versions/$minecraftVersion/$minecraftVersion.jar").isFile) {
                "Base Minecraft client JAR is missing for $minecraftVersion"
            }

            installLibraries(zip, profile.array("libraries"), librariesRoot)
            installLibraries(zip, version.array("libraries"), librariesRoot)
            val profileFile = writeVersionProfile(minecraftHome, versionId, versionText)
            val data = materializeData(zip, profile, minecraftHome, librariesRoot, installerJar, tempRoot, minecraftVersion)
            val processors = profile.array("processors")?.values.orEmpty()
            var runCount = 0
            var skippedCount = 0

            processors.forEachIndexed { index, value ->
                val processor = value as? JsonValue.Obj ?: error("Processor $index is not an object")
                if (!isClientProcessor(processor)) return@forEachIndexed

                val outputSpecs = resolveOutputs(processor.obj("outputs"), data, librariesRoot)
                if (outputSpecs.isNotEmpty() && outputsSatisfied(outputSpecs)) {
                    skippedCount++
                    return@forEachIndexed
                }

                val processorCoordinate = processor.string("jar") ?: error("Processor $index has no jar")
                val processorJar = libraryFile(librariesRoot, processorCoordinate)
                require(processorJar.isFile) { "Processor JAR is missing: $processorCoordinate" }
                val cpCoordinates = processor.array("classpath")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
                val classpathFiles = (cpCoordinates + processorCoordinate).distinct().map { coordinate ->
                    libraryFile(librariesRoot, coordinate).also { require(it.isFile) { "Processor dependency is missing: $coordinate" } }
                }
                val mainClass = JarFile(processorJar).use { jar -> jar.manifest?.mainAttributes?.getValue("Main-Class")?.trim() }
                    ?: error("Processor $processorCoordinate has no Main-Class")
                val args = processor.array("args")?.values.orEmpty().map { argValue ->
                    val raw = (argValue as? JsonValue.Str)?.value ?: error("Processor argument is not a string")
                    resolveProcessorArg(raw, data, librariesRoot)
                }
                val javaArgs = buildList {
                    add("-cp")
                    add(classpathFiles.joinToString(File.pathSeparator) { it.absolutePath })
                    add(mainClass)
                    addAll(args)
                }
                val result = runner.run(javaExecutable, tempRoot, javaArgs)
                require(result.exitCode == 0) {
                    "Loader processor $processorCoordinate failed with exit code ${result.exitCode}: ${result.output.takeLast(4000)}"
                }
                verifyOutputs(outputSpecs)
                runCount++
            }

            return ModernLoaderInstallResult(versionId, minecraftVersion, runCount, skippedCount, profileFile)
        }
    }

    private fun installLibraries(zip: ZipFile, values: JsonValue.Arr?, librariesRoot: File) {
        values?.values.orEmpty().forEach { raw ->
            val library = raw as? JsonValue.Obj ?: return@forEach
            val name = library.string("name") ?: return@forEach
            val artifact = library.obj("downloads")?.obj("artifact")
            val coordinate = runCatching { MavenCoordinate.parse(name) }.getOrNull()
            val relativePath = artifact?.string("path") ?: coordinate?.relativePath() ?: return@forEach
            val target = File(librariesRoot, relativePath)
            val expectedSha1 = artifact?.string("sha1")
            val size = artifact?.number("size")?.toLong()

            if (target.isFile && artifactMatches(target, expectedSha1, size)) return@forEach
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".astra.part")
            temp.delete()
            val embedded = zip.getEntry("maven/$relativePath")
            when {
                embedded != null -> zip.getInputStream(embedded).use { input -> temp.outputStream().use { input.copyTo(it) } }
                artifact?.string("url") != null -> http.download(artifact.string("url")!!, temp, 128L * 1024 * 1024)
                library.string("url") != null && coordinate != null -> http.download(coordinate.downloadUrl(library.string("url")!!), temp, 128L * 1024 * 1024)
                else -> return@forEach
            }
            require(artifactMatches(temp, expectedSha1, size)) { "Library integrity mismatch: $name" }
            if (target.exists() && !target.delete()) error("Could not replace ${target.name}")
            require(temp.renameTo(target)) { "Could not finalize $name" }
        }
    }

    private fun materializeData(
        zip: ZipFile,
        profile: JsonValue.Obj,
        minecraftHome: File,
        librariesRoot: File,
        installerJar: File,
        tempRoot: File,
        minecraftVersion: String,
    ): MutableMap<String, String> {
        val rawData = linkedMapOf<String, String>()
        profile.obj("data")?.values?.forEach { (key, value) ->
            val sided = value as? JsonValue.Obj ?: return@forEach
            sided.string("client")?.let { rawData[key] = it }
        }
        val data = linkedMapOf<String, String>()
        data["SIDE"] = "client"
        data["MINECRAFT_JAR"] = File(minecraftHome, "versions/$minecraftVersion/$minecraftVersion.jar").absolutePath
        data["MINECRAFT_VERSION"] = minecraftVersion
        data["ROOT"] = minecraftHome.absolutePath
        data["INSTALLER"] = installerJar.absolutePath
        data["LIBRARY_DIR"] = librariesRoot.absolutePath
        rawData.forEach { (key, raw) -> data[key] = resolveDataValue(raw, zip, librariesRoot, tempRoot, data) }
        return data
    }

    private fun resolveDataValue(raw: String, zip: ZipFile, librariesRoot: File, tempRoot: File, data: Map<String, String>): String {
        val substituted = replaceTokens(raw, data)
        if (substituted.startsWith("[") && substituted.endsWith("]")) {
            return libraryFile(librariesRoot, substituted.substring(1, substituted.length - 1)).absolutePath
        }
        if (substituted.length >= 2 && substituted.first() == '\'' && substituted.last() == '\'') {
            return substituted.substring(1, substituted.length - 1)
        }
        val entryName = substituted.removePrefix("/")
        val entry = zip.getEntry(entryName)
        if (entry != null) {
            val safe = safeRelative(entryName)
            val target = File(tempRoot, "resources/$safe")
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            return target.absolutePath
        }
        return substituted
    }

    private fun resolveProcessorArg(raw: String, data: Map<String, String>, librariesRoot: File): String {
        val replaced = replaceTokens(raw, data)
        return if (replaced.startsWith("[") && replaced.endsWith("]")) {
            libraryFile(librariesRoot, replaced.substring(1, replaced.length - 1)).absolutePath
        } else replaced
    }

    private fun resolveOutputs(outputs: JsonValue.Obj?, data: Map<String, String>, librariesRoot: File): List<Pair<File, String>> =
        outputs?.values.orEmpty().mapNotNull { (rawPath, rawHash) ->
            val hash = (rawHash as? JsonValue.Str)?.value?.let { replaceTokens(it, data).trim('\'', '"') } ?: return@mapNotNull null
            File(resolveProcessorArg(rawPath, data, librariesRoot)) to hash
        }

    private fun outputsSatisfied(outputs: List<Pair<File, String>>): Boolean =
        outputs.all { (file, expected) -> file.isFile && sha1(file).equals(expected, ignoreCase = true) }

    private fun verifyOutputs(outputs: List<Pair<File, String>>) {
        outputs.forEach { (file, expected) ->
            require(file.isFile) { "Loader processor did not produce ${file.absolutePath}" }
            require(sha1(file).equals(expected, ignoreCase = true)) { "Loader processor output hash mismatch: ${file.name}" }
        }
    }

    private fun writeVersionProfile(minecraftHome: File, versionId: String, versionText: String): File {
        val versionDir = File(minecraftHome, "versions/$versionId").apply { mkdirs() }
        val target = File(versionDir, "$versionId.json")
        val temp = File(versionDir, ".$versionId.json.astra.tmp")
        temp.writeText(versionText)
        require(AstraJson.parse(temp.readText()) is JsonValue.Obj) { "Invalid generated loader version profile" }
        if (target.exists() && !target.delete()) error("Could not replace ${target.name}")
        require(temp.renameTo(target)) { "Could not finalize loader version profile" }
        return target
    }

    private fun isClientProcessor(processor: JsonValue.Obj): Boolean {
        val sides = processor.array("sides")?.values?.mapNotNull { (it as? JsonValue.Str)?.value }
        return sides.isNullOrEmpty() || "client" in sides
    }

    private fun libraryFile(root: File, coordinate: String): File = File(root, MavenCoordinate.parse(coordinate).relativePath())

    private fun replaceTokens(raw: String, data: Map<String, String>): String {
        var value = raw
        data.forEach { (key, replacement) -> value = value.replace("{$key}", replacement) }
        return value
    }

    private fun safeRelative(raw: String): String {
        val normalized = Path.of(raw).normalize()
        require(!normalized.isAbsolute && normalized.none { it.toString() == ".." }) { "Unsafe installer resource path: $raw" }
        return normalized.toString()
    }

    private fun artifactMatches(file: File, expectedSha1: String?, expectedSize: Long?): Boolean {
        if (expectedSize != null && file.length() != expectedSize) return false
        if (expectedSha1 != null && !sha1(file).equals(expectedSha1, ignoreCase = true)) return false
        return true
    }

    private fun sha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
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

    private fun ZipFile.readRequiredText(path: String): String {
        val entry = getEntry(path) ?: error("Modern loader installer is missing $path")
        return getInputStream(entry).bufferedReader().use { it.readText() }
    }
}
