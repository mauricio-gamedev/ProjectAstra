package io.github.astromg01.launcher.game

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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
            val startedAt = json.optLong("startedAt", 0L)
            val log = logFile(context).takeIf(File::isFile)?.readText().orEmpty().trim()
            MinecraftBootReport(
                status = json.optString("status", "UNKNOWN"),
                instanceId = json.optString("instanceId", ""),
                minecraftVersion = json.optString("minecraftVersion", ""),
                javaMajor = json.optInt("javaMajor", 0),
                mainClass = json.optString("mainClass", ""),
                detail = json.optString("detail", ""),
                completed = json.optBoolean("completed", false),
                startedAt = startedAt,
                updatedAt = json.optLong("updatedAt", 0L),
                log = log.takeLast(16000),
                exitInfo = findGameExitInfo(context, startedAt)
            )
        }.getOrNull()

        reportFile.delete()
        return report
    }

    private fun findGameExitInfo(context: Context, startedAt: Long): GameProcessExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val expectedProcess = "${context.packageName}:game"
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
        }.getOrNull().orEmpty()

        val match = exits.asSequence()
            .filter { info -> info.processName == expectedProcess || info.processName.orEmpty().endsWith(":game") }
            .filter { info -> startedAt <= 0L || info.timestamp >= startedAt - 5_000L }
            .maxByOrNull(ApplicationExitInfo::getTimestamp)
            ?: return null

        return GameProcessExitInfo(
            reason = match.reason,
            reasonLabel = reasonLabel(match.reason),
            status = match.status,
            description = match.description.orEmpty(),
            timestamp = match.timestamp,
            processName = match.processName.orEmpty(),
            importance = match.importance,
            pssKb = match.pss,
            rssKb = match.rss
        )
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "REASON_$reason"
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

data class GameProcessExitInfo(
    val reason: Int,
    val reasonLabel: String,
    val status: Int,
    val description: String,
    val timestamp: Long,
    val processName: String,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long
)

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
    val log: String,
    val exitInfo: GameProcessExitInfo?
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
            status == "STARTED" && exitInfo?.reasonLabel != null ->
                "O processo :game encerrou dentro do boot real • Android: ${exitInfo.reasonLabel}"
            status == "STARTED" && likelyReachedMinecraft ->
                "A main class foi iniciada e o processo :game avançou até Minecraft/LWJGL ✓"
            status == "STARTED" ->
                "O processo :game encerrou depois de entrar no JLI; use o log para localizar o próximo bloqueio"
            else -> "Estado de boot desconhecido: $status"
        }
}
