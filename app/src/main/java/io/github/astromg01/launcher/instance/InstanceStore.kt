package io.github.astromg01.launcher.instance

import android.content.Context
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.core.PerformanceMode
import io.github.astromg01.launcher.core.RendererKind
import org.json.JSONArray
import org.json.JSONObject

class InstanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("astra_instances", Context.MODE_PRIVATE)

    fun load(): List<MinecraftInstance> {
        val raw = preferences.getString(KEY_INSTANCES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        MinecraftInstance(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            minecraftVersion = item.getString("minecraftVersion"),
                            loader = item.optString("loader").takeIf { it.isNotBlank() },
                            accountId = item.optString("accountId").takeIf { it.isNotBlank() },
                            performanceMode = enumValueOrDefault(
                                item.optString("performanceMode"),
                                PerformanceMode.ADAPTIVE
                            ),
                            renderer = enumValueOrDefault(
                                item.optString("renderer"),
                                RendererKind.AUTO
                            ),
                            memoryMb = item.optInt("memoryMb", 2048),
                            loaderVersion = item.optString("loaderVersion").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(instances: List<MinecraftInstance>) {
        val array = JSONArray()
        instances.forEach { instance ->
            array.put(
                JSONObject().apply {
                    put("id", instance.id)
                    put("name", instance.name)
                    put("minecraftVersion", instance.minecraftVersion)
                    put("loader", instance.loader ?: "")
                    put("loaderVersion", instance.loaderVersion ?: "")
                    put("accountId", instance.accountId ?: "")
                    put("performanceMode", instance.performanceMode.name)
                    put("renderer", instance.renderer.name)
                    put("memoryMb", instance.memoryMb)
                }
            )
        }
        preferences.edit().putString(KEY_INSTANCES, array.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val KEY_INSTANCES = "instances"
    }
}
