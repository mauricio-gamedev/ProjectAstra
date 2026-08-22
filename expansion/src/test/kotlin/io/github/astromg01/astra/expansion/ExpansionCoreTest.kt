package io.github.astromg01.astra.expansion

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ExpansionCoreTest {
    private val device = DeviceProfile(36, "arm64-v8a", 4096, 8, "Mali-G52")

    @Test fun parsesJsonAndBuildsSafeProfile() {
        val obj = AstraJson.parse("""{"name":"astra","items":[1,true,null]}""") as JsonValue.Obj
        assertEquals("astra", obj.string("name"))
        val plan = OptimizationEngine().buildPlan(OptimizationProfile.PERFORMANCE, device, LoaderType.FABRIC, emptySet())
        assertTrue(plan.actions.any { it.jvmArgument == "-Dorg.lwjgl.system.allocator=system" })
        assertTrue(plan.actions.any { it.jvmArgument == "-Xmx1792M" })
        assertTrue(plan.actions.any { it.optionKey == "renderDistance" && it.optionValue == "6" })
        assertTrue(plan.actions.any { it.optionKey == "simulationDistance" && it.optionValue == "5" })
    }

    @Test fun failsClosedOnRendererConflict() {
        assertFailsWith<IllegalArgumentException> {
            OptimizationEngine().buildPlan(OptimizationProfile.BALANCED, device, LoaderType.FABRIC, setOf("optifine", "sodium"))
        }
    }

    @Test fun renderModOwnsRendererTuning() {
        val plan = OptimizationEngine().buildPlan(OptimizationProfile.PERFORMANCE, device, LoaderType.FABRIC, setOf("sodium"))
        assertFalse(plan.actions.any { it.subsystem == "render" })
        assertTrue(plan.suppressedActions.isNotEmpty())
    }

    @Test fun launchPipelineReplacesDuplicateJvmProperties() {
        val base = LaunchPlan(listOf("-Xmx512M", "-Dorg.lwjgl.system.allocator=jemalloc"), emptyList(), emptyMap(), "Main", emptyList())
        val opt = OptimizationEngine().buildPlan(OptimizationProfile.BALANCED, device, LoaderType.FABRIC, emptySet())
        val result = LaunchExtensionPipeline().apply(base, null, opt).plan
        assertEquals(1, result.jvmArgs.count { it.startsWith("-Xmx") })
        assertEquals(1, result.jvmArgs.count { it.startsWith("-Dorg.lwjgl.system.allocator") })
    }

    @Test fun adaptiveEngineChangesOneMeasuredBottleneck() {
        val decision = AdaptiveOptimizationEngine().evaluate(
            PerformanceSample(averageFps = 12.0, p95FrameTimeMs = 90.0, serverBehindMs = 2051, usedMemoryMb = 900, memoryLimitMb = 1536, renderDistance = 12, simulationDistance = 12),
            LoaderType.VANILLA,
            emptySet(),
        )
        assertEquals("simulationDistance", decision.action?.optionKey)
        assertEquals("11", decision.action?.optionValue)
        assertTrue(decision.confidence >= 0.9)
    }

    @Test fun parsesWorldPerformanceEvidenceFromLog() {
        val metrics = MinecraftLogPerformanceAnalyzer.analyze(
            """
            [Server thread/INFO]: Changing view distance to 12, from 10
            [Server thread/INFO]: Changing simulation distance to 12, from 0
            [Server thread/INFO]: damnbro joined the game
            [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2051ms or 41 ticks behind
            """.trimIndent()
        )
        assertEquals(2051, metrics.maxServerBehindMs)
        assertEquals(41, metrics.maxTicksBehind)
        assertEquals(12, metrics.renderDistance)
        assertEquals(12, metrics.simulationDistance)
        assertTrue(metrics.joinedWorld)
    }

    @Test fun scansFabricMetadataFromInstalledJar() {
        val dir = Files.createTempDirectory("astra-modscan").toFile()
        val jar = dir.resolve("sodium.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"sodium","name":"Sodium","version":"1.0"}""".toByteArray())
            zip.closeEntry()
        }
        val detected = InstalledModScanner().scanJar(jar)
        assertEquals("sodium", detected.single().id)
    }

    @Test fun optimizationOptionsCanRollbackExactly() {
        val dir = Files.createTempDirectory("astra-rollback").toFile()
        val options = dir.resolve("options.txt")
        options.writeText("renderDistance:12\ncustom:keep\n")
        val store = OptimizationRollbackStore()
        val snapshot = store.snapshot(options, dir.resolve("snapshots"))
        options.writeText("renderDistance:6\n")
        store.rollback(options, snapshot)
        assertEquals("renderDistance:12\ncustom:keep\n", options.readText())
    }

    @Test fun mavenCoordinateUsesMinecraftLibraryLayout() {
        assertEquals(
            "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar",
            MavenCoordinate.parse("net.fabricmc:fabric-loader:0.16.9").relativePath(),
        )
    }

    @Test fun alpha38AdapterPreservesProtectedLaunchFields() {
        val core = Alpha38LaunchPlanContract(
            instanceId = "i", minecraftVersion = "1.21.1", javaMajorVersion = 21,
            javaExecutable = "/java", workingDirectory = "/game", mainClass = "Main",
            classpath = emptyList(), jvmArguments = listOf("-Xmx512M"), gameArguments = emptyList(),
            environment = mapOf("KEEP" to "1"), warnings = listOf("baseline"),
        )
        val opt = OptimizationEngine().buildPlan(OptimizationProfile.BALANCED, device, LoaderType.VANILLA, emptySet())
        val extended = LaunchExtensionPipeline().apply(Alpha38CoreAdapter.launchPlan(core), null, opt)
        val merged = Alpha38CoreAdapter.merge(core, extended)
        assertEquals(21, merged.javaMajorVersion)
        assertEquals("/java", merged.javaExecutable)
        assertEquals("1", merged.environment["KEEP"])
        assertEquals(1, merged.jvmArguments.count { it.startsWith("-Xmx") })
    }

    @Test fun optionsTunerPreservesUnknownKeys() {
        val dir = Files.createTempDirectory("astra-options").toFile()
        val file = dir.resolve("options.txt")
        file.writeText("renderDistance:12\ncustomModOption:keep\n")
        val plan = OptimizationEngine().buildPlan(OptimizationProfile.BALANCED, device, LoaderType.FABRIC, emptySet())
        MinecraftOptionsTuner().apply(file, plan)
        val text = file.readText()
        assertTrue("renderDistance:6" in text)
        assertTrue("customModOption:keep" in text)
    }
}
