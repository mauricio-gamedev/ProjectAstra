package io.github.astromg01.launcher.game

import android.content.Context
import org.json.JSONObject
import java.io.File

object JvmSmokeReportStore {
    private const val REPORT_FILE = "astra-jvm-smoke-report.json"
    private const val LOG_FILE = "astra-jvm-smoke.log"

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun markStarted(context: Context, javaMajor: Int, nativeDetail: String) {
        logFile(context).delete()
        write(
            context,
            status = "STARTED",
            javaMajor = javaMajor,
            detail = nativeDetail,
            completed = false
        )
    }

    fun markReturned(
        context: Context,
        javaMajor: Int,
        success: Boolean,
        detail: String
    ) {
        write(
            context,
            status = if (success) "RETURNED_OK" else "RETURNED_ERROR",
            javaMajor = javaMajor,
            detail = detail,
            completed = true
        )
    }

    fun markError(context: Context, javaMajor: Int?, detail: String) {
        write(
            context,
            status = "PRELAUNCH_ERROR",
            javaMajor = javaMajor ?: 0,
            detail = detail,
            completed = true
        )
    }

    fun consume(context: Context): JvmSmokeReport? {
        val reportFile = File(context.filesDir, REPORT_FILE)
        if (!reportFile.isFile) return null

        val report = runCatching {
            val json = JSONObject(reportFile.readText())
            val log = logFile(context).takeIf(File::isFile)?.readText().orEmpty().trim()
            JvmSmokeReport(
                status = json.optString("status", "UNKNOWN"),
                javaMajor = json.optInt("javaMajor", 0),
                detail = json.optString("detail", ""),
                completed = json.optBoolean("completed", false),
                startedAt = json.optLong("startedAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                log = log.takeLast(8000)
            )
        }.getOrNull()

        reportFile.delete()
        return report
    }

    private fun write(
        context: Context,
        status: String,
        javaMajor: Int,
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
            put("javaMajor", javaMajor)
            put("detail", detail)
            put("completed", completed)
            put("startedAt", if (existingStartedAt > 0L) existingStartedAt else now)
            put("updatedAt", now)
        }
        file.writeText(json.toString())
    }
}

data class JvmSmokeReport(
    val status: String,
    val javaMajor: Int,
    val detail: String,
    val completed: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val log: String
) {
    val openJdkOutputDetected: Boolean
        get() = log.contains("openjdk", ignoreCase = true) ||
            log.contains("java version", ignoreCase = true) ||
            log.contains("runtime environment", ignoreCase = true)

    val summary: String
        get() = when {
            status == "RETURNED_OK" -> "JLI_Launch retornou normalmente ✓"
            status == "RETURNED_ERROR" -> "JLI_Launch retornou erro"
            status == "PRELAUNCH_ERROR" -> "Falha antes do JLI_Launch"
            status == "STARTED" && openJdkOutputDetected ->
                "Processo :game encerrou durante o JLI, mas a saída real do OpenJDK foi capturada ✓"
            status == "STARTED" ->
                "Processo :game encerrou durante o JLI antes de gravar o resultado final"
            else -> "Estado JVM desconhecido: $status"
        }
}
