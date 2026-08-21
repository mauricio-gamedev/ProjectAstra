package io.github.astromg01.launcher

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.astromg01.launcher.game.GameSurfaceActivity
import io.github.astromg01.launcher.game.JvmSmokeReportStore
import io.github.astromg01.launcher.game.MinecraftBootReportStore
import io.github.astromg01.launcher.ui.AstraApp
import io.github.astromg01.launcher.ui.AstraTheme

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstraTheme {
                Box(Modifier.fillMaxSize()) {
                    AstraApp()
                    Button(
                        onClick = {
                            startActivity(Intent(this@MainActivity, GameSurfaceActivity::class.java))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 94.dp)
                    ) {
                        Text("Bridge test")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!showPendingMinecraftReport()) {
                showPendingJvmReport()
            }
        }, 800L)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showPendingMinecraftReport(): Boolean {
        if (isFinishing || isDestroyed) return false
        val report = MinecraftBootReportStore.consume(this) ?: return false

        val reportText = buildString {
            append("Project Astra • Minecraft Boot Flight Recorder\n\n")
            append(report.summary)
            append("\nStatus: ${report.status}")
            if (report.instanceId.isNotBlank()) append("\nInstância: ${report.instanceId}")
            if (report.minecraftVersion.isNotBlank()) append("\nMinecraft: ${report.minecraftVersion}")
            if (report.javaMajor > 0) append("\nJava: ${report.javaMajor}")
            if (report.mainClass.isNotBlank()) append("\nMain class: ${report.mainClass}")
            if (report.detail.isNotBlank()) append("\nDetalhe: ${report.detail}")
            append("\nSinais de Minecraft/LWJGL no log: ${if (report.likelyReachedMinecraft) "SIM ✓" else "NÃO"}")

            report.exitInfo?.let { exit ->
                append("\n\n--- Android process exit info ---")
                append("\nProcesso: ${exit.processName}")
                append("\nMotivo: ${exit.reasonLabel} (${exit.reason})")
                append("\nStatus/sinal: ${exit.status}")
                if (exit.description.isNotBlank()) append("\nDescrição: ${exit.description}")
                append("\nImportance: ${exit.importance}")
                append("\nPSS/RSS: ${exit.pssKb} / ${exit.rssKb} KB")
                append("\nTimestamp: ${exit.timestamp}")
            }

            append("\n\n--- stdout/stderr Minecraft ---\n")
            append(report.log.ifBlank { "(nenhuma saída foi capturada)" })
            append("\n\n")
            append(
                when {
                    report.status == "RETURNED_OK" ->
                        "Resultado: a main class retornou normalmente."
                    report.status == "STARTED" && report.exitInfo != null ->
                        "Resultado útil: o Android registrou a causa do encerramento do processo :game; use Motivo + Status/sinal acima para a próxima correção."
                    report.status == "STARTED" && report.likelyReachedMinecraft ->
                        "Resultado útil: o JLI entrou na main class real e chegou ao código Minecraft/LWJGL antes do processo :game encerrar."
                    report.status == "STARTED" ->
                        "O processo :game encerrou dentro do boot real. O log acima define o próximo ponto de integração."
                    report.status == "PRELAUNCH_ERROR" ->
                        "O boot não chegou à main class; corrija o preflight indicado acima."
                    else -> "Use este relatório para a próxima correção do boot."
                }
            )
        }

        showReportDialog(
            title = "Minecraft boot • resultado",
            clipboardLabel = "Project Astra Minecraft boot report",
            reportText = reportText
        )
        return true
    }

    private fun showPendingJvmReport(): Boolean {
        if (isFinishing || isDestroyed) return false
        val report = JvmSmokeReportStore.consume(this) ?: return false

        val reportText = buildString {
            append("Project Astra • JVM Flight Recorder\n\n")
            append(report.summary)
            append("\nStatus: ${report.status}")
            if (report.javaMajor > 0) append("\nJava: ${report.javaMajor}")
            if (report.detail.isNotBlank()) append("\nDetalhe: ${report.detail}")
            append("\nOpenJDK output detectado: ${if (report.openJdkOutputDetected) "SIM ✓" else "NÃO"}")
            append("\n\n--- stdout/stderr ---\n")
            append(report.log.ifBlank { "(nenhuma saída foi capturada)" })
            append("\n\n")
            append(
                when {
                    report.status == "RETURNED_OK" ->
                        "Resultado conclusivo: JLI_Launch retornou normalmente."
                    report.status == "STARTED" && report.openJdkOutputDetected ->
                        "Resultado útil: a JVM/OpenJDK realmente iniciou; o processo :game encerrou durante o launcher."
                    report.status == "STARTED" ->
                        "Resultado inconclusivo: o processo :game encerrou antes de gravar retorno ou saída OpenJDK."
                    else -> "Use este relatório para diagnosticar a próxima correção."
                }
            )
        }

        showReportDialog(
            title = "JVM test • resultado",
            clipboardLabel = "Project Astra JVM report",
            reportText = reportText
        )
        return true
    }

    private fun showReportDialog(title: String, clipboardLabel: String, reportText: String) {
        val textView = TextView(this).apply {
            text = reportText
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(textView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copiar") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, reportText))
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
