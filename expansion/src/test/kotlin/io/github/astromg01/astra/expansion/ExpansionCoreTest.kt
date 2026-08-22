package io.github.astromg01.astra.expansion

import java.nio.file.Files
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
