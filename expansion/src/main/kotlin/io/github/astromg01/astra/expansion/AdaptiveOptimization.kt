package io.github.astromg01.astra.expansion

/**
 * A single observation window. Values are intentionally generic so the Android UI,
 * Minecraft log parser and future native frametime sampler can all feed the same engine.
 */
data class PerformanceSample(
    val averageFps: Double,
    val p95FrameTimeMs: Double,
    val serverBehindMs: Long,
    val usedMemoryMb: Long,
    val memoryLimitMb: Long,
    val renderDistance: Int,
    val simulationDistance: Int,
    val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
)

enum class ThermalStatus { UNKNOWN, NOMINAL, WARM, HOT, CRITICAL }

data class AdaptiveDecision(
    val action: OptimizationAction?,
    val reason: String,
    val confidence: Double,
    val cooldownWindows: Int,
)

/**
 * Conservative one-variable-at-a-time controller. It never patches Minecraft code and
 * never changes renderer-owned options when a known render mod is installed.
 */
class AdaptiveOptimizationEngine(
    private val compatibility: CompatibilityEngine = CompatibilityEngine(),
) {
    fun evaluate(sample: PerformanceSample, loader: LoaderType, modSlugs: Set<String>): AdaptiveDecision {
        val issues = compatibility.evaluate(loader, modSlugs)
        if (issues.any { it.severity == CompatibilityIssue.Severity.BLOCKER }) {
            return AdaptiveDecision(null, "Compatibility blocker present; adaptive changes disabled.", 1.0, 3)
        }

        if (sample.thermalStatus == ThermalStatus.CRITICAL || sample.thermalStatus == ThermalStatus.HOT) {
            val target = (sample.simulationDistance - 1).coerceAtLeast(4)
            if (target < sample.simulationDistance) {
                return AdaptiveDecision(
                    OptimizationAction("adaptive_thermal_simulation", Risk.SAFE, "server", "Reduce simulation distance under thermal pressure.", optionKey = "simulationDistance", optionValue = target.toString()),
                    "Thermal pressure has priority over small FPS gains.",
                    0.95,
                    4,
                )
            }
        }

        if (sample.serverBehindMs >= 750 && sample.simulationDistance > 4) {
            val target = (sample.simulationDistance - 1).coerceAtLeast(4)
            return AdaptiveDecision(
                OptimizationAction("adaptive_server_simulation", Risk.SAFE, "server", "Reduce integrated-server workload.", optionKey = "simulationDistance", optionValue = target.toString()),
                "Integrated server is ${sample.serverBehindMs}ms behind; simulation workload is the strongest measured bottleneck.",
                0.92,
                3,
            )
        }

        val memoryRatio = if (sample.memoryLimitMb > 0) sample.usedMemoryMb.toDouble() / sample.memoryLimitMb else 0.0
        if (memoryRatio >= 0.88 && sample.renderDistance > 4) {
            val target = (sample.renderDistance - 1).coerceAtLeast(4)
            return AdaptiveDecision(
                OptimizationAction("adaptive_memory_render", Risk.SAFE, "world", "Reduce chunk residency under memory pressure.", optionKey = "renderDistance", optionValue = target.toString()),
                "Memory usage is ${(memoryRatio * 100).toInt()}% of the configured budget.",
                0.88,
                3,
            )
        }

        if ((sample.averageFps < 25.0 || sample.p95FrameTimeMs > 50.0) && sample.renderDistance > 4) {
            if (compatibility.ownsRenderer(modSlugs)) {
                return AdaptiveDecision(
                    OptimizationAction("adaptive_world_render_distance", Risk.SAFE, "world", "Reduce chunk draw workload without touching renderer-mod settings.", optionKey = "renderDistance", optionValue = (sample.renderDistance - 1).coerceAtLeast(4).toString()),
                    "Low frame rate detected; renderer-specific tuning is suppressed because a mod owns the renderer.",
                    0.72,
                    2,
                )
            }
            return AdaptiveDecision(
                OptimizationAction("adaptive_render_distance", Risk.SAFE, "world", "Reduce render distance after sustained frame-time pressure.", optionKey = "renderDistance", optionValue = (sample.renderDistance - 1).coerceAtLeast(4).toString()),
                "Average FPS/frame time indicates sustained GPU/CPU render pressure.",
                0.80,
                2,
            )
        }

        return AdaptiveDecision(null, "No measured pressure justifies a configuration change.", 0.85, 1)
    }
}
