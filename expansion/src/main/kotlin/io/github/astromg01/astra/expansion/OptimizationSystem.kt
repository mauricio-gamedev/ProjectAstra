package io.github.astromg01.astra.expansion

import java.io.File

class CompatibilityEngine {
    fun evaluate(loader: LoaderType, modSlugs: Set<String>): List<CompatibilityIssue> {
        val mods = modSlugs.map { it.lowercase() }.toSet()
        val issues = mutableListOf<CompatibilityIssue>()

        if ("optifine" in mods && ("sodium" in mods || "embeddium" in mods)) {
            issues += CompatibilityIssue(
                code = "renderer_mod_conflict",
                severity = CompatibilityIssue.Severity.BLOCKER,
                message = "OptiFine must not share an Astra instance with Sodium/Embeddium unless a specifically validated compatibility layer is present.",
                relatedMods = setOf("optifine", "sodium", "embeddium").intersect(mods),
            )
        }
        if (loader == LoaderType.FABRIC && "embeddium" in mods) {
            issues += CompatibilityIssue("loader_mod_mismatch", CompatibilityIssue.Severity.BLOCKER, "Embeddium is not a Fabric optimization target.", setOf("embeddium"))
        }
        if ((loader == LoaderType.FORGE || loader == LoaderType.NEOFORGE) && "sodium" in mods) {
            issues += CompatibilityIssue("loader_mod_mismatch", CompatibilityIssue.Severity.WARNING, "Sodium is normally used with Fabric/Quilt; verify the exact port before launch.", setOf("sodium"))
        }
        if ("iris" in mods && "sodium" !in mods && loader == LoaderType.FABRIC) {
            issues += CompatibilityIssue("iris_without_sodium", CompatibilityIssue.Severity.WARNING, "Iris on Fabric is normally paired with Sodium; verify the selected Iris release dependencies.", setOf("iris"))
        }
        if (mods.any { it in RENDER_OWNERS }) {
            issues += CompatibilityIssue("external_renderer_owner", CompatibilityIssue.Severity.INFO, "A render optimization mod is installed; Astra will suppress invasive renderer tuning.", mods.intersect(RENDER_OWNERS))
        }
        return issues
    }

    fun ownsRenderer(modSlugs: Set<String>): Boolean = modSlugs.any { it.lowercase() in RENDER_OWNERS }
    fun ownsMemory(modSlugs: Set<String>): Boolean = modSlugs.any { it.lowercase() in MEMORY_OWNERS }
    fun ownsTickEngine(modSlugs: Set<String>): Boolean = modSlugs.any { it.lowercase() in TICK_OWNERS }

    companion object {
        private val RENDER_OWNERS = setOf("sodium", "embeddium", "optifine", "immediatelyfast")
        private val MEMORY_OWNERS = setOf("ferrite-core", "ferritecore", "modernfix")
        private val TICK_OWNERS = setOf("lithium", "modernfix")
    }
}

class OptimizationEngine(private val compatibility: CompatibilityEngine = CompatibilityEngine()) {
    fun buildPlan(
        profile: OptimizationProfile,
        device: DeviceProfile,
        loader: LoaderType,
        modSlugs: Set<String>,
    ): OptimizationPlan {
        val issues = compatibility.evaluate(loader, modSlugs)
        require(issues.none { it.severity == CompatibilityIssue.Severity.BLOCKER }) {
            issues.filter { it.severity == CompatibilityIssue.Severity.BLOCKER }.joinToString("; ") { it.message }
        }

        val actions = mutableListOf<OptimizationAction>()
        val suppressed = mutableListOf<String>()
        val warnings = issues.filter { it.severity != CompatibilityIssue.Severity.INFO }.map { it.message }.toMutableList()

        actions += OptimizationAction(
            id = "lwjgl_system_allocator",
            risk = Risk.SAFE,
            subsystem = "lwjgl",
            description = "Use the Android system allocator instead of unavailable desktop jemalloc natives.",
            jvmArgument = "-Dorg.lwjgl.system.allocator=system",
        )

        val activeCores = device.cpuCores.coerceIn(1, 16)
        actions += OptimizationAction(
            id = "active_processor_count",
            risk = Risk.SAFE,
            subsystem = "jvm",
            description = "Expose the Android-visible CPU count to HotSpot.",
            jvmArgument = "-XX:ActiveProcessorCount=$activeCores",
        )

        val maxHeap = recommendedHeap(device.totalRamMb, profile)
        actions += OptimizationAction(
            id = "heap_budget",
            risk = Risk.SAFE,
            subsystem = "jvm",
            description = "Keep Java heap inside an Android-safe memory budget.",
            jvmArgument = "-Xmx${maxHeap}M",
        )

        if (profile != OptimizationProfile.COMPATIBILITY) {
            val render = when {
                device.totalRamMb <= 4096 -> 6
                device.totalRamMb <= 6144 -> 8
                else -> 10
            }
            val simulation = when {
                device.totalRamMb <= 4096 -> 5
                device.totalRamMb <= 6144 -> 6
                else -> 8
            }
            actions += OptimizationAction("render_distance_budget", Risk.SAFE, "world", "Cap default render workload for Android.", optionKey = "renderDistance", optionValue = render.toString())
            actions += OptimizationAction("simulation_distance_budget", Risk.SAFE, "server", "Cap integrated-server simulation workload.", optionKey = "simulationDistance", optionValue = simulation.toString())
            actions += OptimizationAction("particles_minimal", Risk.SAFE, "render", "Reduce particle workload.", optionKey = "particles", optionValue = "2")
        }

        if (compatibility.ownsRenderer(modSlugs)) {
            val removed = actions.filter { it.subsystem == "render" }.map { it.id }
            actions.removeAll { it.subsystem == "render" }
            suppressed += removed.map { "$it: renderer owned by installed optimization mod" }
        }

        if (profile == OptimizationProfile.PERFORMANCE && !compatibility.ownsRenderer(modSlugs)) {
            actions += OptimizationAction("entity_distance", Risk.CONDITIONAL, "render", "Reduce entity draw distance on constrained devices.", optionKey = "entityDistanceScaling", optionValue = "0.75")
            actions += OptimizationAction("clouds_off", Risk.CONDITIONAL, "render", "Disable clouds to reduce fill-rate pressure.", optionKey = "renderClouds", optionValue = "false")
        }

        if (profile == OptimizationProfile.EXPERIMENTAL) {
            warnings += "Experimental mode intentionally does not auto-enable undocumented JVM or renderer flags. Experiments must be added as separately testable rules."
        }

        return OptimizationPlan(profile, actions.distinctBy { it.id }, suppressed, warnings)
    }

    private fun recommendedHeap(totalRamMb: Int, profile: OptimizationProfile): Int {
        val base = when {
            totalRamMb <= 3072 -> 1024
            totalRamMb <= 4096 -> 1536
            totalRamMb <= 6144 -> 2048
            totalRamMb <= 8192 -> 3072
            else -> 4096
        }
        return when (profile) {
            OptimizationProfile.COMPATIBILITY -> base.coerceAtMost(2048)
            OptimizationProfile.BALANCED -> base
            OptimizationProfile.PERFORMANCE -> (base + 256).coerceAtMost((totalRamMb * 0.55).toInt()).coerceAtLeast(1024)
            OptimizationProfile.EXPERIMENTAL -> base
        }
    }
}

class MinecraftOptionsTuner {
    fun apply(optionsFile: File, plan: OptimizationPlan): List<String> {
        val changes = plan.actions.mapNotNull { action ->
            val key = action.optionKey ?: return@mapNotNull null
            key to (action.optionValue ?: return@mapNotNull null)
        }.toMap()
        if (changes.isEmpty()) return emptyList()

        val lines = if (optionsFile.exists()) optionsFile.readLines().toMutableList() else mutableListOf()
        val seen = mutableSetOf<String>()
        for (i in lines.indices) {
            val key = lines[i].substringBefore(':', missingDelimiterValue = "")
            val value = changes[key] ?: continue
            lines[i] = "$key:$value"
            seen += key
        }
        changes.filterKeys { it !in seen }.forEach { (key, value) -> lines += "$key:$value" }
        optionsFile.parentFile?.mkdirs()
        val temp = File(optionsFile.parentFile, optionsFile.name + ".astra.tmp")
        temp.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        if (optionsFile.exists() && !optionsFile.delete()) error("Could not replace ${optionsFile.name}")
        require(temp.renameTo(optionsFile)) { "Could not finalize ${optionsFile.name}" }
        return changes.map { (k, v) -> "$k=$v" }
    }
}
