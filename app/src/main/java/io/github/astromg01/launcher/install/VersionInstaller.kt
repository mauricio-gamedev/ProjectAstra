package io.github.astromg01.launcher.install

import android.content.Context
import io.github.astromg01.launcher.version.MinecraftVersion
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object VersionInstaller {
    private const val USER_AGENT = "ProjectAstra/0.1.0-alpha02"
    private const val ASSET_BASE_URL = "https://resources.download.minecraft.net"

    fun isInstalled(context: Context, versionId: String): Boolean =
        markerFile(context, versionId).isFile

    fun readInstalledInfo(context: Context, versionId: String): InstalledVersionInfo? {
        val marker = markerFile(context, versionId)
        if (!marker.isFile) return null
        return runCatching {
            val json = JSONObject(marker.readText())
            InstalledVersionInfo(
                versionId = json.getString("versionId"),
                javaMajorVersion = json.getInt("javaMajorVersion"),
                mainClass = json.getString("mainClass"),
                assetIndexId = json.getString("assetIndexId")
            )
        }.getOrNull()
    }

    fun install(
        context: Context,
        version: MinecraftVersion,
        onProgress: (InstallProgress) -> Unit = {}
    ): InstalledVersionInfo {
        val root = minecraftRoot(context)
        val versionDir = File(root, "versions/${version.id}").apply { mkdirs() }
        val versionJson = File(versionDir, "${version.id}.json")

        onProgress(InstallProgress("Metadata da versão", 0, 1, versionJson.name))
        downloadVerified(
            url = version.url,
            target = versionJson,
            expectedSha1 = version.sha1
        )
        onProgress(InstallProgress("Metadata da versão", 1, 1, versionJson.name))

        val metadata = JSONObject(versionJson.readText())
        val downloads = metadata.getJSONObject("downloads")
        val client = downloads.getJSONObject("client")
        val assetIndex = metadata.getJSONObject("assetIndex")
        val libraries = metadata.getJSONArray("libraries")

        val clientJar = File(versionDir, "${version.id}.jar")
        onProgress(InstallProgress("Cliente Minecraft", 0, 1, clientJar.name))
        downloadVerified(
            url = client.getString("url"),
            target = clientJar,
            expectedSha1 = client.optString("sha1").takeIf { it.isNotBlank() }
        )
        onProgress(InstallProgress("Cliente Minecraft", 1, 1, clientJar.name))

        val libraryDownloads = buildList {
            for (index in 0 until libraries.length()) {
                val library = libraries.getJSONObject(index)
                val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
                val path = artifact.optString("path")
                val url = artifact.optString("url")
                if (path.isBlank() || url.isBlank()) continue
                add(
                    DownloadEntry(
                        url = url,
                        target = File(root, "libraries/$path"),
                        sha1 = artifact.optString("sha1").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        libraryDownloads.forEachIndexed { index, entry ->
            onProgress(
                InstallProgress(
                    stage = "Libraries",
                    completed = index,
                    total = libraryDownloads.size,
                    currentFile = entry.target.name
                )
            )
            downloadVerified(entry.url, entry.target, entry.sha1)
        }
        onProgress(
            InstallProgress(
                stage = "Libraries",
                completed = libraryDownloads.size,
                total = libraryDownloads.size
            )
        )

        val assetIndexId = assetIndex.getString("id")
        val assetIndexFile = File(root, "assets/indexes/$assetIndexId.json")
        onProgress(InstallProgress("Índice de assets", 0, 1, assetIndexFile.name))
        downloadVerified(
            url = assetIndex.getString("url"),
            target = assetIndexFile,
            expectedSha1 = assetIndex.optString("sha1").takeIf { it.isNotBlank() }
        )
        onProgress(InstallProgress("Índice de assets", 1, 1, assetIndexFile.name))

        val objects = JSONObject(assetIndexFile.readText()).getJSONObject("objects")
        val assetEntries = buildList {
            val keys = objects.keys()
            while (keys.hasNext()) {
                val logicalName = keys.next()
                val data = objects.getJSONObject(logicalName)
                val hash = data.getString("hash")
                if (hash.length < 2) continue
                add(
                    DownloadEntry(
                        url = "$ASSET_BASE_URL/${hash.substring(0, 2)}/$hash",
                        target = File(root, "assets/objects/${hash.substring(0, 2)}/$hash"),
                        sha1 = hash,
                        displayName = logicalName
                    )
                )
            }
        }

        assetEntries.forEachIndexed { index, entry ->
            if (index == 0 || index % 20 == 0 || index == assetEntries.lastIndex) {
                onProgress(
                    InstallProgress(
                        stage = "Assets",
                        completed = index,
                        total = assetEntries.size,
                        currentFile = entry.displayName ?: entry.target.name
                    )
                )
            }
            downloadVerified(entry.url, entry.target, entry.sha1)
        }
        onProgress(
            InstallProgress(
                stage = "Assets",
                completed = assetEntries.size,
                total = assetEntries.size
            )
        )

        val info = InstalledVersionInfo(
            versionId = version.id,
            javaMajorVersion = metadata.optJSONObject("javaVersion")?.optInt("majorVersion", 8) ?: 8,
            mainClass = metadata.optString("mainClass", "net.minecraft.client.main.Main"),
            assetIndexId = assetIndexId
        )

        markerFile(context, version.id).apply {
            parentFile?.mkdirs()
            writeText(
                JSONObject()
                    .put("versionId", info.versionId)
                    .put("javaMajorVersion", info.javaMajorVersion)
                    .put("mainClass", info.mainClass)
                    .put("assetIndexId", info.assetIndexId)
                    .toString()
            )
        }

        onProgress(InstallProgress("Concluído", 1, 1, version.id))
        return info
    }

    private fun minecraftRoot(context: Context): File =
        File(context.filesDir, "minecraft").apply { mkdirs() }

    private fun markerFile(context: Context, versionId: String): File =
        File(minecraftRoot(context), "versions/$versionId/.astra-installed.json")

    private fun downloadVerified(
        url: String,
        target: File,
        expectedSha1: String?
    ) {
        target.parentFile?.mkdirs()

        if (target.isFile && (expectedSha1 == null || sha1(target).equals(expectedSha1, true))) {
            return
        }

        if (target.exists()) target.delete()
        val temp = File(target.parentFile, "${target.name}.part")
        if (temp.exists()) temp.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Download retornou HTTP $code: $url")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }

            if (expectedSha1 != null) {
                val actual = sha1(temp)
                if (!actual.equals(expectedSha1, true)) {
                    temp.delete()
                    throw IllegalStateException(
                        "SHA-1 inválido para ${target.name}: esperado $expectedSha1, recebido $actual"
                    )
                }
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
            if (temp.exists() && !target.exists()) temp.delete()
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class DownloadEntry(
        val url: String,
        val target: File,
        val sha1: String?,
        val displayName: String? = null
    )
}
