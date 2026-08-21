package io.github.astromg01.launcher.version

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object VersionManifestService {
    private const val MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    fun fetch(): MinecraftVersionManifest {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ProjectAstra/0.1.0-alpha01")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Manifest da Mojang retornou HTTP $status")
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line)
                    }
                }
            }

            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): MinecraftVersionManifest {
        val root = JSONObject(json)
        val latest = root.getJSONObject("latest")
        val versionsJson = root.getJSONArray("versions")
        val versions = buildList {
            for (index in 0 until versionsJson.length()) {
                val item = versionsJson.getJSONObject(index)
                add(
                    MinecraftVersion(
                        id = item.getString("id"),
                        type = item.getString("type"),
                        url = item.getString("url"),
                        releaseTime = item.optString("releaseTime"),
                        sha1 = item.optString("sha1").takeIf { it.isNotBlank() },
                        complianceLevel = item.optInt("complianceLevel", 0)
                    )
                )
            }
        }

        return MinecraftVersionManifest(
            latestRelease = latest.getString("release"),
            latestSnapshot = latest.getString("snapshot"),
            versions = versions
        )
    }
}
