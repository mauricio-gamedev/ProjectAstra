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
        mainHandler.postDelayed({ showPendingJvmReport() }, 350L)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showPendingJvmReport() {
        if (isFinishing || isDestroyed) return
        val report = JvmSmokeReportStore.consume(this) ?: return

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

        val textView = TextView(this).apply {
            text = reportText
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(this)
            .setTitle("JVM test • resultado")
            .setView(textView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copiar") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Project Astra JVM report", reportText))
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
