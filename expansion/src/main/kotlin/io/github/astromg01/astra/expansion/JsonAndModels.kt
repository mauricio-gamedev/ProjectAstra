package io.github.astromg01.astra.expansion

import java.io.File

sealed interface JsonValue {
    data class Obj(val values: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = values[key]
        fun string(key: String): String? = (values[key] as? Str)?.value
        fun bool(key: String): Boolean? = (values[key] as? Bool)?.value
        fun number(key: String): Double? = (values[key] as? Num)?.value
        fun obj(key: String): Obj? = values[key] as? Obj
        fun array(key: String): Arr? = values[key] as? Arr
    }
    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

object AstraJson {
    fun parse(text: String): JsonValue = Parser(text).parse()

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            require(index == source.length) { "Trailing data at offset $index" }
            return value
        }

        private fun readValue(): JsonValue {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> { literal("true"); JsonValue.Bool(true) }
                'f' -> { literal("false"); JsonValue.Bool(false) }
                'n' -> { literal("null"); JsonValue.Null }
                '-', in '0'..'9' -> JsonValue.Num(readNumber())
                else -> error("Invalid JSON token '${source[index]}' at offset $index")
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            skipWhitespace()
            if (peek('}')) { index++; return JsonValue.Obj(emptyMap()) }
            val result = linkedMapOf<String, JsonValue>()
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace(); expect(':')
                result[key] = readValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> { index++; return JsonValue.Obj(result) }
                    else -> error("Expected ',' or '}' at offset $index")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            skipWhitespace()
            if (peek(']')) { index++; return JsonValue.Arr(emptyList()) }
            val result = mutableListOf<JsonValue>()
            while (true) {
                result += readValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> { index++; return JsonValue.Arr(result) }
                    else -> error("Expected ',' or ']' at offset $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                val c = source[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < source.length) { "Unterminated escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Invalid unicode escape" }
                                val hex = source.substring(index, index + 4)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Invalid escape \\$escaped")
                        }
                    }
                    else -> out.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun readNumber(): Double {
            val start = index
            if (peek('-')) index++
            while (index < source.length && source[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            return source.substring(start, index).toDouble()
        }

        private fun literal(value: String) {
            require(source.regionMatches(index, value, 0, value.length)) { "Expected $value at offset $index" }
            index += value.length
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(c: Char) {
            require(index < source.length && source[index] == c) { "Expected '$c' at offset $index" }
            index++
        }

        private fun peek(c: Char): Boolean = index < source.length && source[index] == c
    }
}

enum class LoaderType { VANILLA, FABRIC, QUILT, FORGE, NEOFORGE }
enum class ModSource { MODRINTH, CURSEFORGE, MANUAL }
enum class OptimizationProfile { COMPATIBILITY, QUALITY, BALANCED, PERFORMANCE, ADAPTIVE, EXPERIMENTAL }
enum class Risk { SAFE, CONDITIONAL, EXPERIMENTAL }

data class MinecraftInstance(
    val id: String,
    val minecraftVersion: String,
    val root: File,
    val loader: LoaderSelection = LoaderSelection(LoaderType.VANILLA, null),
)

data class LoaderSelection(val type: LoaderType, val version: String?)

data class RemoteArtifact(
    val url: String,
    val destinationRelativePath: String,
    val sha1: String? = null,
    val sha512: String? = null,
    val size: Long? = null,
)

data class LoaderInstallPlan(
    val loader: LoaderSelection,
    val minecraftVersion: String,
    val artifacts: List<RemoteArtifact>,
    val mainClass: String? = null,
    val libraries: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    val installerRequired: Boolean = false,
    val notes: List<String> = emptyList(),
)

data class ModProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val source: ModSource,
    val supportedLoaders: Set<LoaderType>,
    val supportedGameVersions: Set<String>,
)

data class ModFile(
    val url: String,
    val fileName: String,
    val primary: Boolean,
    val sha1: String? = null,
    val sha512: String? = null,
    val size: Long? = null,
)

data class ModDependency(val projectId: String?, val versionId: String?, val required: Boolean)

data class ModVersion(
    val id: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    val gameVersions: Set<String>,
    val loaders: Set<LoaderType>,
    val files: List<ModFile>,
    val dependencies: List<ModDependency>,
)

data class InstalledMod(
    val projectId: String,
    val versionId: String,
    val slug: String,
    val file: File,
)

data class DeviceProfile(
    val androidApi: Int,
    val architecture: String,
    val totalRamMb: Int,
    val cpuCores: Int,
    val gpuRenderer: String? = null,
)

data class CompatibilityIssue(
    val code: String,
    val severity: Severity,
    val message: String,
    val relatedMods: Set<String> = emptySet(),
) {
    enum class Severity { INFO, WARNING, BLOCKER }
}

data class OptimizationAction(
    val id: String,
    val risk: Risk,
    val subsystem: String,
    val description: String,
    val jvmArgument: String? = null,
    val optionKey: String? = null,
    val optionValue: String? = null,
)

data class OptimizationPlan(
    val profile: OptimizationProfile,
    val actions: List<OptimizationAction>,
    val suppressedActions: List<String>,
    val warnings: List<String>,
)

data class LaunchPlan(
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val environment: Map<String, String>,
    val mainClass: String,
    val classpath: List<File>,
)

data class LaunchExtensionResult(
    val plan: LaunchPlan,
    val applied: List<String>,
    val warnings: List<String>,
)
