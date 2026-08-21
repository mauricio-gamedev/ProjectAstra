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

class GameSurfaceActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var statusView: TextView

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
            text = "Project Astra • Surface Test\nAguardando ANativeWindow…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            textSize = 16f
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
        attach(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        attach(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AstraNativeBridge.releaseSurface()
        statusView.text = "Surface Android destruída • bridge liberada"
    }

    override fun onDestroy() {
        AstraNativeBridge.releaseSurface()
        super.onDestroy()
    }

    private fun attach(holder: SurfaceHolder) {
        val result = AstraNativeBridge.attach(holder.surface)
        statusView.text = if (result.success) {
            "Surface nativa pronta ✓\n${result.detail}\n\nPróxima etapa: EGL + callbacks LWJGL."
        } else {
            "Falha no bridge de Surface\n${result.detail}"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
