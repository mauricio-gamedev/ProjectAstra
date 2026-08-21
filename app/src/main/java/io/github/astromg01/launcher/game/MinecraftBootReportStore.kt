package io.github.astromg01.launcher.game

import android.content.Context
import org.json.JSONObject
import java.io.File

object MinecraftBootReportStore {
    private const val REPORT_FILE = "astra-minecraft-boot-report.json"
    private const val LOG_FILE = "astra-minecraft-boot.log"

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun markStarted(
        context: Context,
        instanceId: String,
        minecraftVersion: String,
        javaMajor: Int,
        mainClass: String
    ) {
        logFile(context).delete()
        write(
            context = context,
            status = "STARTED",
            instanceId = instanceId,
            minecraftVersion = minecraftVersion,
            javaMajor = javaMajor,
            mainClass = mainClass,
            detail = "JLI_Launch do Minecraft iniciado",
            completed = false
        )
    }

    fun markReturned(
        context: Context,
        instanceId: String,
        minecraftVersion: String,
        javaMajor: Int,
        mainClass: String,
        success: Boolean,
        detail: String
    ) {
        write(
            context = context,
            status = if (success) "RETURNED_OK" else "RETURNED_ERROR",
            instanceId = instanceId,
            minecraftVersion = minecraftVersion,
            javaMajor = javaMajor,
            mainClass = mainClass,
            detail = detail,
            completed = true
        )
    }

    fun markError(
        context: Context,
        instanceId: String = "",
        minecraftVersion: String = "",
        javaMajor: Int = 0,
        mainClass: String = "",
        detail: String
    ) {
        write(
            context = context,
            status = "PRELAUNCH_ERROR",
            instanceId = instanceId,
            minecraftVersion = minecraftVersion,
            javaMajor = javaMajor,
            mainClass = mainClass,
            detail = detail,
            completed = true
        )
    }

    fun consume(context: Context): MinecraftBootReport? {
        val reportFile = File(context.filesDir, REPORT_FILE)
        if (!reportFile.isFile) return null

        val report = runCatching {
            val json = JSONObject(reportFile.readText())
            val log = logFile(context).takeIf(File::isFile)?.readText().orEmpty().trim()
            MinecraftBootReport(
                status = json.optString("status", "UNKNOWN"),
                instanceId = json.optString("instanceId", ""),
                minecraftVersion = json.optString("minecraftVersion", ""),
                javaMajor = json.optInt("javaMajor", 0),
                mainClass = json.optString("mainClass", ""),
                detail = json.optString("detail", ""),
                completed = json.optBoolean("completed", false),
                startedAt = json.optLong("startedAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                log = log.takeLast(16000)
            )
        }.getOrNull()

        reportFile.delete()
        return report
    }

    private fun write(
        context: Context,
        status: String,
        instanceId: String,
        minecraftVersion: String,
        javaMajor: Int,
        mainClass: String,
        detail: String,
        completed: Boolean
    ) {
        val file = File(context.filesDir, REPORT_FILE)
        val existingStartedAt = runCatching {
            if (file.isFile) JSONObject(file.readText()).optLong("startedAt", 0L) else 0L
        }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("status", status)
            put("instanceId", instanceId)
            put("minecraftVersion", minecraftVersion)
            put("javaMajor", javaMajor)
            put("mainClass", mainClass)
            put("detail", detail)
            put("completed", completed)
            put("startedAt", if (existingStartedAt > 0L) existingStartedAt else now)
            put("updatedAt", now)
        }
        file.writeText(json.toString())
    }
}

data class MinecraftBootReport(
    val status: String,
    val instanceId: String,
    val minecraftVersion: String,
    val javaMajor: Int,
    val mainClass: String,
    val detail: String,
    val completed: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val log: String
) {
    val likelyReachedMinecraft: Boolean
        get() = log.contains("minecraft", ignoreCase = true) ||
            log.contains("lwjgl", ignoreCase = true) ||
            log.contains("glfw", ignoreCase = true) ||
            log.contains("mojang", ignoreCase = true)

    val summary: String
        get() = when {
            status == "RETURNED_OK" -> "Minecraft/JLI retornou normalmente ✓"
            status == "RETURNED_ERROR" -> "Minecraft/JLI retornou erro"
            status == "PRELAUNCH_ERROR" -> "Falha antes de entrar na main class"
            status == "STARTED" && likelyReachedMinecraft ->
                "A main class foi iniciada e o processo :game avançou até Minecraft/LWJGL ✓"
            status == "STARTED" ->
                "O processo :game encerrou depois de entrar no JLI; use o log para localizar o próximo bloqueio"
            else -> "Estado de boot desconhecido: $status"
        }
}
