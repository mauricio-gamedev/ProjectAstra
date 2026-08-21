package io.github.astromg01.launcher.runtime

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object RuntimeInstaller {
    private const val USER_AGENT = "ProjectAstra/0.1.0-alpha05"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    fun installedRuntime(context: Context, majorVersion: Int): InstalledRuntime? {
        val descriptor = RuntimeCatalog.descriptor(majorVersion) ?: return null
        val marker = markerFile(context, majorVersion, descriptor.architecture)
        if (!marker.isFile) return null

        return runCatching {
            val json = JSONObject(marker.readText())
            val home = File(json.getString("homePath"))
            val java = File(json.getString("javaPath"))
            if (!home.isDirectory || !java.isFile) return null

            InstalledRuntime(
                majorVersion = json.getInt("majorVersion"),
                architecture = json.getString("architecture"),
                homePath = home.absolutePath,
                javaPath = java.absolutePath,
                source = json.getString("source")
            )
        }.getOrNull()
    }

    fun isInstalled(context: Context, majorVersion: Int): Boolean =
        installedRuntime(context, majorVersion) != null

    fun install(
        context: Context,
        majorVersion: Int,
        onProgress: (RuntimeProgress) -> Unit = {}
    ): InstalledRuntime {
        val descriptor = RuntimeCatalog.descriptor(majorVersion)
            ?: error("Java $majorVersion não possui runtime Android compatível com este dispositivo.")

        val root = runtimeRoot(context)
        val archive = File(root, "downloads/jre${majorVersion}-${descriptor.architecture}.tar.xz")
        archive.parentFile?.mkdirs()

        onProgress(RuntimeProgress("Baixando Java $majorVersion", detail = descriptor.architecture))
        downloadArchive(descriptor, archive, onProgress)

        val finalDir = File(root, "jre-${majorVersion}-${descriptor.architecture}")
        val staging = File(root, ".extract-${majorVersion}-${descriptor.architecture}")
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            onProgress(RuntimeProgress("Extraindo Java $majorVersion", detail = archive.name))
            extractTarXz(archive, staging, onProgress)

            val extractedHome = findRuntimeHome(staging)
                ?: error("Runtime extraído sem bin/java.")

            finalDir.deleteRecursively()
            if (!extractedHome.renameTo(finalDir)) {
                error("Não foi possível mover o runtime Java para o diretório final.")
            }
            if (staging.exists()) staging.deleteRecursively()

            val javaBinary = File(finalDir, "bin/java")
            if (!javaBinary.isFile) error("Runtime final sem executável bin/java.")
            javaBinary.setExecutable(true, true)

            val installed = InstalledRuntime(
                majorVersion = majorVersion,
                architecture = descriptor.architecture,
                homePath = finalDir.absolutePath,
                javaPath = javaBinary.absolutePath,
                source = descriptor.source
            )

            writeMarker(context, installed)
            onProgress(RuntimeProgress("Java $majorVersion pronto", 1, 1, descriptor.architecture))
            return installed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun downloadArchive(
        descriptor: RuntimeDescriptor,
        target: File,
        onProgress: (RuntimeProgress) -> Unit
    ) {
        if (target.isFile && verifySha256(target, descriptor.sha256)) return

        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadOnce(descriptor, target, onProgress)
                return
            } catch (error: Throwable) {
                lastError = error
                target.delete()
                File(target.parentFile, "${target.name}.part").delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: error("Falha ao baixar Java ${descriptor.majorVersion}.")
    }

    private fun downloadOnce(
        descriptor: RuntimeDescriptor,
        target: File,
        onProgress: (RuntimeProgress) -> Unit
    ) {
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()

        val connection = (URL(descriptor.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Download do Java retornou HTTP $code.")

            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var completed = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        completed += read
                        onProgress(
                            RuntimeProgress(
                                stage = "Baixando Java ${descriptor.majorVersion}",
                                completedBytes = completed,
                                totalBytes = total,
                                detail = descriptor.architecture
                            )
                        )
                    }
                }
            }

            if (!verifySha256(temp, descriptor.sha256)) {
                temp.delete()
                error("SHA-256 inválido para Java ${descriptor.majorVersion}.")
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarXz(
        archive: File,
        destination: File,
        onProgress: (RuntimeProgress) -> Unit
    ) {
        val deferredLinks = mutableListOf<Pair<File, TarArchiveEntry>>()
        val destinationCanonical = destination.canonicalFile
        var entries = 0L

        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput).use { buffered ->
                XZInputStream(buffered).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val target = safeTarget(destinationCanonical, entry.name)

                            when {
                                entry.isDirectory -> target.mkdirs()
                                entry.isSymbolicLink || entry.isLink -> {
                                    deferredLinks += target to entry
                                }
                                entry.isFile -> {
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { output -> tar.copyTo(output) }
                                    if (entry.mode and 0b001001001 != 0) {
                                        target.setExecutable(true, true)
                                    }
                                }
                            }

                            entries++
                            if (entries % 80L == 0L) {
                                onProgress(
                                    RuntimeProgress(
                                        stage = "Extraindo runtime",
                                        completedBytes = entries,
                                        detail = entry.name
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        deferredLinks.forEach { (target, entry) ->
            target.parentFile?.mkdirs()
            val linkName = entry.linkName

            if (entry.isSymbolicLink) {
                validateSymbolicLink(destinationCanonical, target, linkName, entry.name)
                target.delete()
                runCatching { Os.symlink(linkName, target.absolutePath) }
                    .getOrElse { error("Falha ao criar link do runtime: ${entry.name}") }
            } else {
                val source = safeTarget(destinationCanonical, linkName)
                if (!source.isFile) error("Hard link aponta para arquivo inexistente: $linkName")
                target.delete()
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun validateSymbolicLink(
        root: File,
        linkFile: File,
        linkName: String,
        entryName: String
    ) {
        if (linkName.isBlank() || linkName.startsWith('/')) {
            error("Link inseguro no runtime: $entryName")
        }

        // Symlinks de OpenJDK usam caminhos relativos como ../../libfoo.so.
        // Eles são válidos desde que, após a resolução a partir da pasta do link,
        // o destino continue confinado dentro da árvore extraída do runtime.
        val resolved = File(linkFile.parentFile, linkName).canonicalFile
        ensureInsideRoot(root, resolved, "Link inseguro no runtime: $entryName")
    }

    private fun safeTarget(root: File, entryName: String): File {
        val target = File(root, entryName).canonicalFile
        ensureInsideRoot(root, target, "Entrada insegura no arquivo do runtime: $entryName")
        return target
    }

    private fun ensureInsideRoot(root: File, target: File, message: String) {
        val rootCanonical = root.canonicalFile
        val targetCanonical = target.canonicalFile
        val rootPath = rootCanonical.path + File.separator
        if (targetCanonical != rootCanonical && !targetCanonical.path.startsWith(rootPath)) {
            error(message)
        }
    }

    private fun findRuntimeHome(root: File): File? {
        return root.walkTopDown()
            .maxDepth(5)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
            ?.parentFile
            ?.parentFile
    }

    private fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return file.isFile && file.length() > 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun runtimeRoot(context: Context): File =
        File(context.filesDir, "runtimes").apply { mkdirs() }

    private fun markerFile(context: Context, majorVersion: Int, architecture: String): File =
        File(runtimeRoot(context), "jre-$majorVersion-$architecture/.astra-runtime.json")

    private fun writeMarker(context: Context, runtime: InstalledRuntime) {
        markerFile(context, runtime.majorVersion, runtime.architecture).writeText(
            JSONObject()
                .put("majorVersion", runtime.majorVersion)
                .put("architecture", runtime.architecture)
                .put("homePath", runtime.homePath)
                .put("javaPath", runtime.javaPath)
                .put("source", runtime.source)
                .toString()
        )
    }
}
