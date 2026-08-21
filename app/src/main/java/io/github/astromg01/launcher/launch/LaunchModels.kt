package io.github.astromg01.launcher.launch

import java.io.File

data class LaunchPlan(
    val instanceId: String,
    val minecraftVersion: String,
    val javaMajorVersion: Int,
    val javaExecutable: String,
    val workingDirectory: String,
    val mainClass: String,
    val classpath: List<String>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val environment: Map<String, String>,
    val warnings: List<String> = emptyList()
) {
    val command: List<String>
        get() = buildList {
            add(javaExecutable)
            addAll(jvmArguments)
            add(mainClass)
            addAll(gameArguments)
        }

    val commandPreview: String
        get() = command.joinToString(" ") { argument ->
            if (argument.any(Char::isWhitespace)) {
                "\"${argument.replace("\"", "\\\"")}\""
            } else {
                argument
            }
        }

    val classpathCount: Int
        get() = classpath.size

    fun workingDirectoryFile(): File = File(workingDirectory)
}

data class LaunchPlanSummary(
    val instanceId: String,
    val minecraftVersion: String,
    val javaMajorVersion: Int,
    val mainClass: String,
    val classpathEntries: Int,
    val jvmArgumentCount: Int,
    val gameArgumentCount: Int,
    val warnings: List<String>
)
