package io.github.astromg01.launcher.launch

import io.github.astromg01.launcher.runtime.RuntimeCatalog
import org.json.JSONArray
import org.json.JSONObject

object MinecraftRuleEvaluator {
    private const val ANDROID_MINECRAFT_OS = "linux"

    fun allows(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() == 0) return true

        var allowed = false
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            if (!matches(rule)) continue
            allowed = rule.optString("action", "disallow") == "allow"
        }
        return allowed
    }

    private fun matches(rule: JSONObject): Boolean {
        val os = rule.optJSONObject("os")
        if (os != null) {
            val name = os.optString("name")
            if (name.isNotBlank() && name != ANDROID_MINECRAFT_OS) return false

            val arch = os.optString("arch")
            if (arch.isNotBlank() && !matchesArchitecture(arch)) return false

            // Mojang's optional os.version field is a regex aimed at desktop OS versions.
            // Android should not claim to match such desktop-specific constraints.
            if (os.optString("version").isNotBlank()) return false
        }

        val features = rule.optJSONObject("features")
        if (features != null) {
            val keys = features.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val requested = features.optBoolean(key, false)
                val actual = when (key) {
                    "is_demo_user" -> false
                    "has_custom_resolution" -> false
                    "has_quick_plays_support" -> false
                    "is_quick_play_singleplayer" -> false
                    "is_quick_play_multiplayer" -> false
                    "is_quick_play_realms" -> false
                    else -> false
                }
                if (requested != actual) return false
            }
        }

        return true
    }

    private fun matchesArchitecture(mojangArch: String): Boolean {
        val deviceArch = RuntimeCatalog.deviceArchitecture() ?: return false
        return when (mojangArch.lowercase()) {
            "x86", "i386", "i686" -> deviceArch == "x86"
            "x86_64", "amd64" -> deviceArch == "x86_64"
            "arm", "arm32" -> deviceArch == "arm"
            "arm64", "aarch64" -> deviceArch == "arm64"
            else -> false
        }
    }
}
