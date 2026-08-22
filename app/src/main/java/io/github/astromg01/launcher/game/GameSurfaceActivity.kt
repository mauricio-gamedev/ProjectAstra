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
import io.github.astromg01.launcher.account.AccountProfile
import io.github.astromg01.launcher.account.AccountStore
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.expansion.AstraExpansionBridge
import io.github.astromg01.launcher.instance.InstanceStore
import io.github.astromg01.launcher.launch.LaunchPlanBuilder
import io.github.astromg01.launcher.nativebridge.AstraNativeBridge
import io.github.astromg01.launcher.nativebridge.NativeRuntimeValidator
import io.github.astromg01.launcher.renderer.RendererComponentManager
import io.github.astromg01.launcher.runtime.RuntimeInstaller

class GameSurfaceActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var statusView: TextView
    private lateinit var jvmButton: Button
    private lateinit var minecraftButton: Button
    private var surfaceAttached = false
    private var graphicsStarted = false
    private var launchRunning = false
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
                setMargins(dp(16), dp(16), dp(176), dp(16))
            }
        )

        minecraftButton = Button(this).apply {
            text = "Minecraft boot"
            isEnabled = false
            setOnClickListener { runMinecraftBoot() }
        }
        root.addView(
            minecraftButton,
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
        if (launchRunning) return

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
        if (!launchRunning) {
            AstraNativeBridge.releaseSurface()
        }
        surfaceAttached = false
        graphicsStarted = false
        setLaunchButtons(false)
        if (!launchRunning) {
            statusView.text = "Surface Android destruída • EGL e bridge liberados"
        }
    }

    override fun onDestroy() {
        if (!launchRunning) {
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
        setLaunchButtons(result.success && !launchRunning)
        statusView.text = if (result.success) {
            buildString {
                append("Project Astra • Graphics Bridge ✓\n")
                append(result.detail)
                append("\n\nFrame verde apresentado pela cadeia:\n")
                append("ANativeWindow → MobileGlues → EGL → OpenGL → SwapBuffers ✓\n")
                append("\nAlpha12: JVM validada; Minecraft boot real disponível.")
                append("\nEscolha apenas UM teste por abertura do Bridge test.")
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
        if (launchRunning) return
        launchRunning = true
        setLaunchButtons(false)

        if (graphicsStarted) {
            AstraNativeBridge.shutdownGraphics()
            graphicsStarted = false
        }

        statusView.text = buildString {
            append("Project Astra • JVM Bridge alpha12\n")
            append("Graphics Bridge validado ✓\n")
            append("Processo de jogo isolado: :game ✓\n\n")
            append("Executando smoke test do OpenJDK…")
        }

        Thread {
            var selectedJavaMajor: Int? = null
            val result = runCatching {
                val runtime = listOf(21, 17, 8)
                    .firstNotNullOfOrNull { major -> RuntimeInstaller.installedRuntime(this, major) }
                    ?: error("Nenhum Java 21/17/8 instalado no Astra.")
                selectedJavaMajor = runtime.majorVersion

                val nativeStatus = NativeRuntimeValidator.validate(this, runtime)
                if (!nativeStatus.success || nativeStatus.jliPath.isNullOrBlank()) {
                    error(nativeStatus.detail)
                }

                JvmSmokeReportStore.markStarted(
                    context = this,
                    javaMajor = runtime.majorVersion,
                    nativeDetail = nativeStatus.detail
                )
                val logFile = JvmSmokeReportStore.logFile(this)

                val launch = AstraNativeBridge.testJavaVersion(
                    jliLibraryPath = nativeStatus.jliPath,
                    javaExecutable = runtime.javaPath,
                    workingDirectory = runtime.homePath,
                    logPath = logFile.absolutePath
                )

                JvmSmokeReportStore.markReturned(
                    context = this,
                    javaMajor = runtime.majorVersion,
                    success = launch.success,
                    detail = launch.detail
                )

                JvmTestReport(
                    success = launch.success,
                    javaMajor = runtime.majorVersion,
                    nativeDetail = nativeStatus.detail,
                    launchDetail = launch.detail,
                    log = logFile.takeIf { it.isFile }?.readText().orEmpty().trim().takeLast(6000)
                )
            }

            result.onFailure { error ->
                JvmSmokeReportStore.markError(
                    context = this,
                    javaMajor = selectedJavaMajor,
                    detail = error.message ?: error.javaClass.simpleName
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
                        append(report.log.ifBlank { "(sem saída capturada)" })
                        append("\n\nReabra Bridge test para iniciar outro JLI.")
                    }
                }.onFailure { error ->
                    statusView.text = buildString {
                        append("Project Astra • JVM Bridge falhou\n")
                        append("Processo isolado :game ✓\n\n")
                        append(error.message ?: error.javaClass.simpleName)
                        append("\n\nRelatório persistente salvo para a tela principal.")
                    }
                }
                launchRunning = false
            }
        }.start()
    }

    private fun runMinecraftBoot() {
        if (launchRunning) return
        launchRunning = true
        setLaunchButtons(false)

        if (graphicsStarted) {
            AstraNativeBridge.shutdownGraphics()
            graphicsStarted = false
        }

        statusView.text = buildString {
            append("Project Astra • Minecraft Boot alpha12\n")
            append("Graphics Bridge validado ✓\n")
            append("JVM/JLI validada no dispositivo ✓\n")
            append("Processo isolado :game ✓\n\n")
            append("Montando LaunchPlan real da instância…")
        }

        Thread {
            var selectedInstance: MinecraftInstance? = null
            var selectedVersion = ""
            var selectedJava = 0
            var selectedMainClass = ""

            val result = runCatching {
                val (instance, account) = resolveLaunchTarget()
                selectedInstance = instance
                selectedVersion = instance.minecraftVersion

                val vanillaPlan = LaunchPlanBuilder.build(this, instance, account)
                val plan = AstraExpansionBridge.prepareLaunch(this, instance, vanillaPlan)
                selectedJava = plan.javaMajorVersion
                selectedMainClass = plan.mainClass

                val runtime = RuntimeInstaller.installedRuntime(this, plan.javaMajorVersion)
                    ?: error("Java ${plan.javaMajorVersion} do LaunchPlan não está instalado.")
                val nativeStatus = NativeRuntimeValidator.validate(this, runtime)
                if (!nativeStatus.success || nativeStatus.jliPath.isNullOrBlank()) {
                    error(nativeStatus.detail)
                }

                val envFailure = plan.environment.entries.firstOrNull { (key, value) ->
                    AstraNativeBridge.setEnvironment(key, value) != 0
                }
                if (envFailure != null) {
                    error("Falha ao aplicar ambiente do LaunchPlan: ${envFailure.key}")
                }

                MinecraftBootReportStore.markStarted(
                    context = this,
                    instanceId = plan.instanceId,
                    minecraftVersion = plan.minecraftVersion,
                    javaMajor = plan.javaMajorVersion,
                    mainClass = plan.mainClass
                )
                val logFile = MinecraftBootReportStore.logFile(this)

                runOnUiThread {
                    statusView.text = buildString {
                        append("Project Astra • Minecraft Boot alpha12\n")
                        append("Instância: ${instance.name}\n")
                        append("Minecraft: ${plan.minecraftVersion}\n")
                        append("Java: ${plan.javaMajorVersion}\n")
                        append("Main: ${plan.mainClass}\n")
                        append("Classpath: ${plan.classpathCount} entradas\n")
                        append("JVM args: ${plan.jvmArguments.size} • Game args: ${plan.gameArguments.size}\n\n")
                        append("Entrando na main class real…")
                    }
                }

                val launch = AstraNativeBridge.launchMinecraft(
                    jliLibraryPath = nativeStatus.jliPath,
                    javaExecutable = plan.javaExecutable,
                    javaHome = runtime.homePath,
                    workingDirectory = plan.workingDirectory,
                    logPath = logFile.absolutePath,
                    jvmArguments = plan.jvmArguments,
                    mainClass = plan.mainClass,
                    gameArguments = plan.gameArguments
                )

                MinecraftBootReportStore.markReturned(
                    context = this,
                    instanceId = plan.instanceId,
                    minecraftVersion = plan.minecraftVersion,
                    javaMajor = plan.javaMajorVersion,
                    mainClass = plan.mainClass,
                    success = launch.success,
                    detail = launch.detail
                )

                MinecraftTestReport(
                    success = launch.success,
                    instanceName = instance.name,
                    version = plan.minecraftVersion,
                    javaMajor = plan.javaMajorVersion,
                    mainClass = plan.mainClass,
                    launchDetail = launch.detail,
                    log = logFile.takeIf { it.isFile }?.readText().orEmpty().trim().takeLast(10000)
                )
            }

            result.onFailure { error ->
                MinecraftBootReportStore.markError(
                    context = this,
                    instanceId = selectedInstance?.id.orEmpty(),
                    minecraftVersion = selectedVersion,
                    javaMajor = selectedJava,
                    mainClass = selectedMainClass,
                    detail = error.message ?: error.javaClass.simpleName
                )
            }

            runOnUiThread {
                result.onSuccess { report ->
                    statusView.text = buildString {
                        append(if (report.success) "Project Astra • Minecraft retornou ✓\n" else "Project Astra • Minecraft parou\n")
                        append("Instância: ${report.instanceName}\n")
                        append("Minecraft ${report.version} • Java ${report.javaMajor}\n")
                        append("Main: ${report.mainClass}\n")
                        append("${report.launchDetail}\n\n")
                        append("--- stdout/stderr Minecraft ---\n")
                        append(report.log.ifBlank { "(sem saída capturada)" })
                        append("\n\nFlight recorder salvo. Reabra Bridge test para outro boot.")
                    }
                }.onFailure { error ->
                    statusView.text = buildString {
                        append("Project Astra • Minecraft prelaunch falhou\n\n")
                        append(error.message ?: error.javaClass.simpleName)
                        append("\n\nO relatório também foi salvo para a tela principal.")
                    }
                }
                launchRunning = false
            }
        }.start()
    }

    private fun resolveLaunchTarget(): Pair<MinecraftInstance, AccountProfile> {
        val instances = InstanceStore(this).load()
        val instance = instances.firstOrNull()
            ?: error("Nenhuma instância criada. Crie/prepara uma instância antes do Minecraft boot.")

        val accounts = AccountStore(this).load()
        val account = instance.accountId
            ?.let { id -> accounts.firstOrNull { it.id == id } }
            ?: accounts.firstOrNull { it.isDefault }
            ?: accounts.firstOrNull()
            ?: error("Nenhuma conta disponível. Adicione uma conta offline ou Microsoft primeiro.")

        return instance to account
    }

    private fun setLaunchButtons(enabled: Boolean) {
        jvmButton.isEnabled = enabled
        minecraftButton.isEnabled = enabled
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class JvmTestReport(
        val success: Boolean,
        val javaMajor: Int,
        val nativeDetail: String,
        val launchDetail: String,
        val log: String
    )

    private data class MinecraftTestReport(
        val success: Boolean,
        val instanceName: String,
        val version: String,
        val javaMajor: Int,
        val mainClass: String,
        val launchDetail: String,
        val log: String
    )
}
