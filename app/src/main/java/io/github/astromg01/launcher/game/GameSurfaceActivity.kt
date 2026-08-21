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
import io.github.astromg01.launcher.renderer.RendererComponentManager

class GameSurfaceActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var statusView: TextView
    private var surfaceAttached = false
    private var graphicsStarted = false
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
            text = "Project Astra • EGL / MobileGlues Test\nAguardando ANativeWindow…"
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
        AstraNativeBridge.releaseSurface()
        surfaceAttached = false
        graphicsStarted = false
        statusView.text = "Surface Android destruída • EGL e bridge liberados"
    }

    override fun onDestroy() {
        AstraNativeBridge.releaseSurface()
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
        statusView.text = if (result.success) {
            buildString {
                append("Project Astra • Graphics Bridge ✓\n")
                append(result.detail)
                append("\n\nFrame verde apresentado pela cadeia:\n")
                append("ANativeWindow → MobileGlues → EGL → OpenGL → SwapBuffers ✓\n")
                append("\nPróximo: callbacks GLFW/LWJGL + JLI_Launch.")
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
