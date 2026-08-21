package io.github.astromg01.launcher.game

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.github.astromg01.launcher.nativebridge.AstraNativeBridge
import io.github.astromg01.launcher.nativebridge.NativeRuntimeValidator
import io.github.astromg01.launcher.renderer.RendererComponentManager
import io.github.astromg01.launcher.runtime.RuntimeInstaller
import java.io.File

class GameSurfaceActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var statusView: TextView
    private lateinit var jvmButton: Button
    private var surfaceAttached = false
    private var graphicsStarted = false
    private var jvmTestRunning = false
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val surface = SurfaceView(this).apply {
            holder.addCallback(this@GameSurfaceActivity)
        }
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            text = "Project Astra • Graphics/JVM Bridge\nAguardando ANativeWindow…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xC0000000.toInt())
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        )

        val close = Button(this).apply {
            text = "Voltar"
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(dp(16), dp(12), dp(16), dp(16))
            }
        )

        jvmButton = Button(this).apply {
            text = "JVM test"
            isEnabled = false
            setOnClickListener { runJvmTest() }
        }
        root.addView(
            jvmButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        )

        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val result = AstraNativeBridge.attach(holder.surface)
        surfaceAttached = result.success
        graphicsStarted = false
        statusView.text = if (result.success) {
            "ANativeWindow pronta ✓\n${result.detail}\nAguardando geometria EGL…"
        } else {
            "Falha no bridge de Surface\n${result.detail}"
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (jvmTestRunning) return

        if (!surfaceAttached) {
            val attach = AstraNativeBridge.attach(holder.surface)
            surfaceAttached = attach.success
            if (!attach.success) {
                statusView.text = "Falha no bridge de Surface\n${attach.detail}"
                return
            }
        }

        if (graphicsStarted && width == lastWidth && height == lastHeight) return
        if (graphicsStarted) {
            AstraNativeBridge.shutdownGraphics()
            graphicsStarted = false
        }

        lastWidth = width
        lastHeight = height
        runGraphicsTest()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!jvmTestRunning) {
            AstraNativeBridge.releaseSurface()
        }
        surfaceAttached = false
        graphicsStarted = false
        jvmButton.isEnabled = false
        if (!jvmTestRunning) {
            statusView.text = "Surface Android destruída • EGL e bridge liberados"
        }
    }

    override fun onDestroy() {
        if (!jvmTestRunning) {
            AstraNativeBridge.releaseSurface()
        }
        surfaceAttached = false
        graphicsStarted = false
        super.onDestroy()
    }

    private fun runGraphicsTest() {
        val renderer = RendererComponentManager.installedMobileGlues(this)
        if (renderer == null) {
            statusView.text = buildString {
                append("ANativeWindow pronta ✓\n")
                append(AstraNativeBridge.surfaceProbe().detail)
                append("\n\nMobileGlues ainda não está instalado.\n")
                append("Prepare o plano de uma instância primeiro.")
            }
            return
        }

        val environment = RendererComponentManager.environment(this, renderer)
        val envFailure = environment.entries.firstOrNull { (key, value) ->
            AstraNativeBridge.setEnvironment(key, value) != 0
        }
        if (envFailure != null) {
            statusView.text = "Falha ao configurar ambiente do renderer\n${envFailure.key}"
            return
        }

        statusView.text = buildString {
            append("ANativeWindow pronta ✓\n")
            append(AstraNativeBridge.surfaceProbe().detail)
            append("\nCarregando MobileGlues ${renderer.version} + EGL…")
        }

        val result = AstraNativeBridge.startGraphics(renderer.libraryPath)
        graphicsStarted = result.success
        jvmButton.isEnabled = result.success && !jvmTestRunning
        statusView.text = if (result.success) {
            buildString {
                append("Project Astra • Graphics Bridge ✓\n")
                append(result.detail)
                append("\n\nFrame verde apresentado pela cadeia:\n")
                append("ANativeWindow → MobileGlues → EGL → OpenGL → SwapBuffers ✓\n")
                append("\nAlpha09: toque em JVM test para iniciar OpenJDK via JLI_Launch no processo :game.")
            }
        } else {
            buildString {
                append("ANativeWindow pronta ✓\n")
                append(AstraNativeBridge.surfaceProbe().detail)
                append("\n\nFalha no teste EGL/MobileGlues\n")
                append(result.detail)
            }
        }
    }

    private fun runJvmTest() {
        if (jvmTestRunning) return
        jvmTestRunning = true
        jvmButton.isEnabled = false

        if (graphicsStarted) {
            AstraNativeBridge.shutdownGraphics()
            graphicsStarted = false
        }

        statusView.text = buildString {
            append("Project Astra • JVM Bridge alpha09\n")
            append("Graphics Bridge validado ✓\n")
            append("Processo de jogo isolado: :game ✓\n\n")
            append("Procurando runtime OpenJDK instalado…")
        }

        Thread {
            val result = runCatching {
                val runtime = listOf(21, 17, 8)
                    .firstNotNullOfOrNull { major -> RuntimeInstaller.installedRuntime(this, major) }
                    ?: error("Nenhum Java 21/17/8 instalado no Astra.")

                val nativeStatus = NativeRuntimeValidator.validate(this, runtime)
                if (!nativeStatus.success || nativeStatus.jliPath.isNullOrBlank()) {
                    error(nativeStatus.detail)
                }

                val logFile = File(cacheDir, "astra-jli-alpha09.log").apply { delete() }
                val launch = AstraNativeBridge.testJavaVersion(
                    jliLibraryPath = nativeStatus.jliPath,
                    javaExecutable = runtime.javaPath,
                    workingDirectory = runtime.homePath,
                    logPath = logFile.absolutePath
                )
                val log = if (logFile.isFile) {
                    logFile.readText().trim().takeLast(6000)
                } else {
                    "(stdout/stderr não gerou arquivo)"
                }

                JvmTestReport(
                    success = launch.success,
                    javaMajor = runtime.majorVersion,
                    nativeDetail = nativeStatus.detail,
                    launchDetail = launch.detail,
                    log = log
                )
            }

            runOnUiThread {
                result.onSuccess { report ->
                    statusView.text = buildString {
                        append(if (report.success) "Project Astra • JVM Bridge ✓\n" else "Project Astra • JVM Bridge falhou\n")
                        append("Processo isolado :game ✓\n")
                        append(report.nativeDetail)
                        append("\n")
                        append(report.launchDetail)
                        append("\nJava ${report.javaMajor}\n\n")
                        append("--- stdout/stderr OpenJDK ---\n")
                        append(report.log)
                        append("\n\n")
                        if (report.success) {
                            append("Próximo: GLFW/LWJGL callbacks + Minecraft main class.")
                        } else {
                            append("Envie este texto/print para corrigirmos a ponte JLI.")
                        }
                    }
                }.onFailure { error ->
                    statusView.text = buildString {
                        append("Project Astra • JVM Bridge falhou\n")
                        append("Processo isolado :game ✓\n\n")
                        append(error.message ?: error.javaClass.simpleName)
                    }
                }
                jvmTestRunning = false
            }
        }.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class JvmTestReport(
        val success: Boolean,
        val javaMajor: Int,
        val nativeDetail: String,
        val launchDetail: String,
        val log: String
    )
}
