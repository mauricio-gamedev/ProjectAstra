package io.github.astromg01.launcher.lwjgl

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object AndroidLwjglNativesManager {
    private const val UPSTREAM_COMMIT = "360d708262ff703d9b52782d20cd348410a33df5"
    private const val UPSTREAM_REPOSITORY = "AngelAuraMC/Amethyst-Android"
    private const val USER_AGENT = "ProjectAstra/0.1.0-alpha15"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    private data class NativePackageSpec(
        val version: String,
        val fileName: String,
        val gitBlobSha1: String,
        val size: Long
    )

    private val packages = mapOf(
        "3.3.3" to NativePackageSpec(
            version = "3.3.3",
            fileName = "lwjgl-3.3.3-natives-release.aar",
            gitBlobSha1 = "4f58d4ddeeaf4e8a337d2bfd7955bd0ed7015de8",
            size = 16_269_516
        ),
        "3.4.1" to NativePackageSpec(
            version = "3.4.1",
            fileName = "lwjgl-3.4.1-natives-release.aar",
            gitBlobSha1 = "523e976c9fa282a63407e39fbe8d7c846efe0778",
            size = 16_567_622
        )
    )

    fun ensureInstalled(context: Context, version: String): InstalledLwjglNatives {
        val spec = packages[version]
            ?: error("LWJGL $version não possui pacote native Android validado.")
        val abi = resolveAbi()
        val root = File(context.filesDir, "components/lwjgl3/$version")
        val packageFile = File(root, spec.fileName)
        val nativeDir = File(root, "natives/$abi")
        val marker = File(nativeDir, ".astra-native-ready")

        root.mkdirs()
        if (!isValid(packageFile, spec)) {
            packageFile.delete()
            downloadWithRetry(spec, packageFile)
            if (!isValid(packageFile, spec)) {
                packageFile.delete()
                error("Falha de integridade no pacote native LWJGL ${spec.version}.")
            }
            nativeDir.deleteRecursively()
        }

        if (!marker.isFile || !hasRequiredLibraries(nativeDir)) {
            nativeDir.deleteRecursively()
            nativeDir.mkdirs()
            val extracted = extractAbiLibraries(packageFile, spec.version, abi, nativeDir)
            if (extracted.isEmpty()) {
                nativeDir.deleteRecursively()
                error("O pacote LWJGL ${spec.version} não contém natives reconhecíveis para $abi.")
            }
            if (!hasRequiredLibraries(nativeDir)) {
                val names = nativeLibraries(nativeDir).joinToString { it.name }
                nativeDir.deleteRecursively()
                error("Pacote LWJGL ${spec.version} para $abi incompleto. Extraídos: $names")
            }
            marker.writeText(
                buildString {
                    appendLine("version=${spec.version}")
                    appendLine("abi=$abi")
                    appendLine("source=$UPSTREAM_REPOSITORY@$UPSTREAM_COMMIT")
                    appendLine("package_sha1=${spec.gitBlobSha1}")
                    appendLine("libraries=${extracted.sorted().joinToString(",")}")
                }
            )
        }

        val libraries = nativeLibraries(nativeDir)
        if (!hasRequiredLibraries(nativeDir)) {
            error("Diretório native LWJGL incompleto após extração: ${nativeDir.absolutePath}")
        }

        return InstalledLwjglNatives(
            version = version,
            abi = abi,
            directoryPath = nativeDir.absolutePath,
            libraryPaths = libraries.map(File::getAbsolutePath),
            source = "$UPSTREAM_REPOSITORY@$UPSTREAM_COMMIT"
        )
    }

    fun installed(context: Context, version: String): InstalledLwjglNatives? {
        val spec = packages[version] ?: return null
        val abi = runCatching { resolveAbi() }.getOrNull() ?: return null
        val root = File(context.filesDir, "components/lwjgl3/$version")
        val packageFile = File(root, spec.fileName)
        val nativeDir = File(root, "natives/$abi")
        val libraries = nativeLibraries(nativeDir)
        if (!isValid(packageFile, spec) || !hasRequiredLibraries(nativeDir)) return null
        return InstalledLwjglNatives(
            version = version,
            abi = abi,
            directoryPath = nativeDir.absolutePath,
            libraryPaths = libraries.map(File::getAbsolutePath),
            source = "$UPSTREAM_REPOSITORY@$UPSTREAM_COMMIT"
        )
    }

    private fun resolveAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        return listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            .firstOrNull { it in supported }
            ?: error("ABI Android não suportada para LWJGL natives: ${supported.joinToString()}")
    }

    private fun nativeLibraries(directory: File): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun hasRequiredLibraries(directory: File): Boolean {
        val names = nativeLibraries(directory).mapTo(hashSetOf()) { it.name }
        return "liblwjgl.so" in names && "liblwjgl_opengl.so" in names
    }

    private fun extractAbiLibraries(
        packageFile: File,
        version: String,
        abi: String,
        outputDir: File
    ): List<String> {
        // Amethyst packages these binaries as Android assets rather than standard AAR jniLibs.
        // Keep the jni/ prefix as a compatibility fallback for future/upstream package changes.
        val prefixes = listOf(
            "assets/components/lwjgl-$version-natives/$abi/",
            "jni/$abi/"
        )
        val extracted = linkedSetOf<String>()
        val canonicalRoot = outputDir.canonicalFile

        ZipInputStream(BufferedInputStream(FileInputStream(packageFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val matchingPrefix = prefixes.firstOrNull { prefix -> name.startsWith(prefix) }
                if (!entry.isDirectory && matchingPrefix != null && name.endsWith(".so")) {
                    val relativeName = name.removePrefix(matchingPrefix)
                    // The currently validated package stores native libraries directly below the ABI
                    // directory. Reject nested paths rather than flattening untrusted archive entries.
                    require(relativeName.isNotBlank() && '/' !in relativeName && '\\' !in relativeName) {
                        "Entrada native LWJGL inválida: $name"
                    }
                    val target = File(canonicalRoot, relativeName).canonicalFile
                    require(target.parentFile == canonicalRoot) {
                        "Entrada native LWJGL escaparia do diretório de destino: $name"
                    }
                    FileOutputStream(target).use { output -> zip.copyTo(output, 128 * 1024) }
                    target.setReadable(true, true)
                    target.setExecutable(true, true)
                    extracted += relativeName
                }
                zip.closeEntry()
            }
        }
        return extracted.toList()
    }

    private fun rawUrl(spec: NativePackageSpec): String =
        "https://raw.githubusercontent.com/$UPSTREAM_REPOSITORY/$UPSTREAM_COMMIT/" +
            "app_pojavlauncher/libs/${spec.fileName}"

    private fun downloadWithRetry(spec: NativePackageSpec, target: File) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadOnce(rawUrl(spec), target)
                return
            } catch (error: Throwable) {
                lastError = error
                target.delete()
                File(target.parentFile, "${target.name}.part").delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) Thread.sleep(750L * (attempt + 1))
            }
        }
        throw lastError ?: error("Falha ao baixar ${spec.fileName}")
    }

    private fun downloadOnce(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part").apply { delete() }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Download de ${target.name} retornou HTTP $code.")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 128 * 1024) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValid(file: File, spec: NativePackageSpec): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        return gitBlobSha1(file).equals(spec.gitBlobSha1, ignoreCase = true)
    }

    private fun gitBlobSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

data class InstalledLwjglNatives(
    val version: String,
    val abi: String,
    val directoryPath: String,
    val libraryPaths: List<String>,
    val source: String
)
