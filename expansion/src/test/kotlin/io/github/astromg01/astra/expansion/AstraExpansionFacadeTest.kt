package io.github.astromg01.astra.expansion

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AstraExpansionFacadeTest {
    private val device = DeviceProfile(
        androidApi = 36,
        architecture = "arm64-v8a",
        totalRamMb = 4096,
        cpuCores = 8,
        gpuRenderer = "Mali-G52",
    )

    @Test
    fun preparesAlpha38LaunchLocallyAndReplacesUnsafeJvmDuplicates() {
        val root = Files.createTempDirectory("astra-alpha38").toFile()
        val instance = Alpha38InstanceContract(
            id = "test",
            name = "Test",
            minecraftVersion = "1.21.1",
            loader = "vanilla",
            accountId = "account",
            performanceMode = Alpha38PerformanceMode.PERFORMANCE,
            renderer = Alpha38RendererKind.MOBILEGLUES,
            memoryMb = 1536,
        )
        val base = Alpha38LaunchPlanContract(
            instanceId = "test",
            minecraftVersion = "1.21.1",
            javaMajorVersion = 21,
            javaExecutable = "/runtime/bin/java",
            workingDirectory = root.absolutePath,
            mainClass = "net.minecraft.client.main.Main",
            classpath = emptyList(),
            jvmArguments = listOf("-Xmx512M", "-Dorg.lwjgl.system.allocator=jemalloc"),
            gameArguments = emptyList(),
            environment = emptyMap(),
            warnings = emptyList(),
        )

        val result = AstraExpansionFacade().prepareAlpha38Launch(instance, base, root, device)

        assertEquals(1, result.jvmArguments.count { it.startsWith("-Xmx") })
        assertEquals(1, result.jvmArguments.count { it.startsWith("-Dorg.lwjgl.system.allocator") })
        assertTrue(result.jvmArguments.contains("-Dorg.lwjgl.system.allocator=system"))
        assertEquals("net.minecraft.client.main.Main", result.mainClass)
    }

    @Test
    fun optimizationApplyAndRollbackPreserveUnknownModOptions() {
        val root = Files.createTempDirectory("astra-opt").toFile()
        val options = root.resolve("options.txt")
        val original = "renderDistance:12\nsimulationDistance:12\ncustomModOption:keep\n"
        options.writeText(original)
        val instance = MinecraftInstance("test", "1.21.1", root, LoaderSelection(LoaderType.FABRIC, "0.16.0"))
        val facade = AstraExpansionFacade()

        val applied = facade.applyOptimization(instance, OptimizationProfile.BALANCED, device)
        val changed = options.readText()

        assertTrue("renderDistance:6" in changed)
        assertTrue("simulationDistance:5" in changed)
        assertTrue("customModOption:keep" in changed)

        facade.rollbackOptimization(instance, applied)
        assertEquals(original, options.readText())
    }

    @Test
    fun adaptiveUsesRealServerLagEvidenceAndChangesOneVariable() {
        val root = Files.createTempDirectory("astra-adaptive").toFile()
        root.resolve("options.txt").writeText("renderDistance:12\nsimulationDistance:12\n")
        val instance = MinecraftInstance("world", "1.21.1", root, LoaderSelection(LoaderType.VANILLA, null))
        val log = """
            [Server thread/INFO]: Changing view distance to 12, from 10
            [Server thread/INFO]: Changing simulation distance to 12, from 0
            [Server thread/INFO]: damnbro joined the game
            [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2051ms or 41 ticks behind
        """.trimIndent()
        val facade = AstraExpansionFacade()

        val preview = facade.previewAdaptive(
            instance = instance,
            log = log,
            averageFps = 10.0,
            p95FrameTimeMs = 100.0,
            usedMemoryMb = 900,
            memoryLimitMb = 1536,
        )

        assertEquals(2051, preview.metrics.lastServerBehindMs)
        assertTrue(preview.metrics.joinedWorld)
        assertEquals("simulationDistance", preview.decision.action?.optionKey)
        assertEquals("11", preview.decision.action?.optionValue)

        val applied = facade.applyAdaptive(instance, preview)
        val text = root.resolve("options.txt").readText()
        assertTrue("simulationDistance:11" in text)
        assertTrue("renderDistance:12" in text)
        assertEquals(1, applied.changedOptions.size)

        facade.rollbackAdaptive(instance, applied)
        assertTrue("simulationDistance:12" in root.resolve("options.txt").readText())
    }

    @Test
    fun controllerSurfacesFailureWithoutStayingBusy() {
        var last = ExpansionUiState()
        val controller = AstraExpansionController(stateListener = { last = it })
        val root = Files.createTempDirectory("astra-controller").toFile()
        val instance = MinecraftInstance("test", "1.21.1", root)

        val result = controller.searchMods(instance, "   ")

        assertTrue(result.isFailure)
        assertFalse(last.busy)
        assertTrue(last.error?.contains("blank", ignoreCase = true) == true)
    }
}
