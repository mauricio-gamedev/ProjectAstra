package io.github.astromg01.launcher.renderer

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

object RendererComponentManager {
    const val MOBILEGLUES_VERSION = "2.0.0"
    private const val MOBILEGLUES_APK_URL =
        "https://github.com/MobileGL-Dev/MobileGlues-release/releases/download/V2.0.0/MobileGlues_2.0.0.apk"
    private const val MOBILEGLUES_APK_SHA256 =
        "a7e1eb29731fdece7ab3af7fb00692b856f1808a7544436e7b3eb6c08355581a"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val USER_AGENT = "ProjectAstra/0.1.0-alpha19"

    fun installedMobileGlues(context: Context): InstalledRenderer? {
        val abi = preferredAbi() ?: return null
        val root = rendererRoot(context, MOBILEGLUES_VERSION, abi)
        val marker = File(root, ".astra-renderer.json")
        if (!marker.isFile) return null

        return runCatching {
            val json = JSONObject(marker.readText())
            val library = File(json.getString("libraryPath"))
            if (!library.isFile || !looksLikeElf(library)) return null
            InstalledRenderer(
                id = "mobileglues",
                version = json.getString("version"),
                abi = json.getString("abi"),
                libraryPath = library.absolutePath,
                directoryPath = root.absolutePath,
                source = json.getString("source")
            )
        }.getOrNull()
    }

    fun ensureMobileGlues(
        context: Context,
        onProgress: (RendererProgress) -> Unit = {}
    ): InstalledRenderer {
        installedMobileGlues(context)?.let { return it }

        val abi = preferredAbi()
            ?: error("Nenhuma ABI Android compatível foi encontrada para o renderer.")
        val components = File(context.filesDir, "components/renderers/mobileglues").apply { mkdirs() }
        val download = File(components, "MobileGlues_${MOBILEGLUES_VERSION}.apk")

        if (!download.isFile || !verifySha256(download, MOBILEGLUES_APK_SHA256)) {
            download.delete()
            downloadWithRetry(download, onProgress)
        }

        val finalDir = rendererRoot(context, MOBILEGLUES_VERSION, abi)
        val staging = File(finalDir.parentFile, ".extract-$abi").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            onProgress(RendererProgress("Extraindo MobileGlues", detail = abi))
            extractAbiLibraries(download, abi, staging)
            val mobileGlues = File(staging, "libmobileglues.so")
            if (!mobileGlues.isFile || !looksLikeElf(mobileGlues)) {
                error("O APK oficial do MobileGlues não contém uma libmobileglues.so válida para $abi.")
            }

            finalDir.deleteRecursively()
            finalDir.parentFile?.mkdirs()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }

            // O carregamento real do renderer depende de todas as .so irmãs e da
            // superfície EGL. Nesta etapa validamos o APK, a ABI e o ELF; o dlopen
            // completo acontece junto ao bootstrap gráfico, evitando falso negativo.
            val installedLibrary = File(finalDir, "libmobileglues.so")
            val installed = InstalledRenderer(
                id = "mobileglues",
                version = MOBILEGLUES_VERSION,
                abi = abi,
                libraryPath = installedLibrary.absolutePath,
                directoryPath = finalDir.absolutePath,
                source = "MobileGL-Dev/MobileGlues-release V$MOBILEGLUES_VERSION • LGPL-2.1"
            )

            File(finalDir, ".astra-renderer.json").writeText(
                JSONObject()
                    .put("id", installed.id)
                    .put("version", installed.version)
                    .put("abi", installed.abi)
                    .put("libraryPath", installed.libraryPath)
                    .put("source", installed.source)
                    .put("apkSha256", MOBILEGLUES_APK_SHA256)
                    .toString()
            )
            onProgress(RendererProgress("MobileGlues pronto", 1, 1, "V$MOBILEGLUES_VERSION • $abi"))
            return installed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun environment(context: Context, renderer: InstalledRenderer): Map<String, String> {
        val mgDir = File(context.filesDir, "MobileGlues").apply { mkdirs() }
        return linkedMapOf(
            // POJAV_RENDERER is consumed by the launcher/native renderer layer.
            "POJAV_RENDERER" to "opengles3",
            // The patched Android LWJGL GLFW shim separately reads AMETHYST_RENDERER
            // in glfwCreateWindow() to select its advertised GL context profile.
            // Without this value its glDriver local is null and GLFW crashes before
            // the first real window is registered.
            "AMETHYST_RENDERER" to "opengles_mobileglues",
            "POJAV_LOAD_TURNIP" to "0",
            "POJAVEXEC_EGL" to renderer.libraryPath,
            "LIBGL_EGL" to renderer.libraryPath,
            "MG_DIR_PATH" to mgDir.absolutePath,
            "MG_COUNT_LAUNCH" to "1",
            // MobileGlues is the selected renderer in this provider. Keep its backend
            // deterministic on the system GLES/EGL driver; ANGLE gets its own provider later.
            "MG_ANGLE_DIR" to "",
            "LIBGL_ES" to "3",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOERROR" to "1",
            "LIBGL_NORMALIZE" to "1",
            "force_glsl_extensions_warn" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "ASTRA_RENDERER_VERSION" to renderer.version
        )
    }

    private fun downloadWithRetry(
        target: File,
        onProgress: (RendererProgress) -> Unit
    ) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadOnce(target, onProgress)
                return
            } catch (error: Throwable) {
                lastError = error
                target.delete()
                File(target.parentFile, "${target.name}.part").delete()
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw lastError ?: error("Falha ao baixar MobileGlues.")
    }

    private fun downloadOnce(
        target: File,
        onProgress: (RendererProgress) -> Unit
    ) {
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()

        val connection = (URL(MOBILEGLUES_APK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Download do MobileGlues retornou HTTP $code.")

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
                            RendererProgress(
                                stage = "Baixando MobileGlues $MOBILEGLUES_VERSION",
                                completedBytes = completed,
                                totalBytes = total,
                                detail = preferredAbi()
                            )
                        )
                    }
                }
            }

            if (!verifySha256(temp, MOBILEGLUES_APK_SHA256)) {
                temp.delete()
                error("SHA-256 inválido no APK oficial do MobileGlues.")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractAbiLibraries(apk: File, abi: String, destination: File) {
        ZipFile(apk).use { zip ->
            val prefix = "lib/$abi/"
            val entries = zip.entries()
            var extracted = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith(prefix) || !entry.name.endsWith(".so")) continue
                val fileName = entry.name.substringAfterLast('/')
                if (fileName.isBlank() || '/' in fileName || '\\' in fileName) continue
                val output = File(destination, fileName)
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { stream -> input.copyTo(stream) }
                }
                extracted++
            }
            if (extracted == 0) error("Nenhuma biblioteca nativa $abi encontrada no APK do MobileGlues.")
        }
    }

    private fun rendererRoot(context: Context, version: String, abi: String): File =
        File(context.filesDir, "components/renderers/mobileglues/$version/$abi")

    private fun preferredAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }

    private fun looksLikeElf(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) return false
            return magic[0] == 0x7f.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
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
}

data class InstalledRenderer(
    val id: String,
    val version: String,
    val abi: String,
    val libraryPath: String,
    val directoryPath: String,
    val source: String
)

data class RendererProgress(
    val stage: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val detail: String? = null
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}
