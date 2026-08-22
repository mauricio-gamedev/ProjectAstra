package io.github.astromg01.astra.expansion

class LaunchExtensionPipeline {
    fun apply(base: LaunchPlan, loader: LoaderInstallPlan?, optimization: OptimizationPlan?): LaunchExtensionResult {
        val jvm = base.jvmArgs.toMutableList()
        val game = base.gameArgs.toMutableList()
        val classpath = base.classpath.toMutableList()
        var mainClass = base.mainClass
        val applied = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        loader?.let { plan ->
            plan.mainClass?.takeIf { it.isNotBlank() }?.let {
                mainClass = it
                applied += "loader.mainClass=$it"
            }
            plan.jvmArgs.forEach { addJvmArgReplacingSameKey(jvm, it) }
            plan.gameArgs.forEach { if (it !in game) game += it }
            if (plan.installerRequired) warnings += "${plan.loader.type} still requires its validated installer/materialization step before launch."
            applied += "loader=${plan.loader.type}:${plan.loader.version ?: "vanilla"}"
        }

        optimization?.let { plan ->
            plan.actions.mapNotNull { it.jvmArgument }.forEach { arg ->
                addJvmArgReplacingSameKey(jvm, arg)
                applied += "optimization.jvm:$arg"
            }
            warnings += plan.warnings
            warnings += plan.suppressedActions
        }

        return LaunchExtensionResult(
            plan = base.copy(jvmArgs = jvm, gameArgs = game, mainClass = mainClass, classpath = classpath),
            applied = applied,
            warnings = warnings.distinct(),
        )
    }

    private fun addJvmArgReplacingSameKey(args: MutableList<String>, candidate: String) {
        val key = when {
            candidate.startsWith("-D") -> candidate.substringBefore('=')
            candidate.startsWith("-Xmx") -> "-Xmx"
            candidate.startsWith("-Xms") -> "-Xms"
            candidate.startsWith("-XX:ActiveProcessorCount=") -> "-XX:ActiveProcessorCount="
            else -> candidate.substringBefore('=')
        }
        args.removeAll { existing ->
            when (key) {
                "-Xmx", "-Xms" -> existing.startsWith(key)
                "-XX:ActiveProcessorCount=" -> existing.startsWith(key)
                else -> existing == key || existing.startsWith("$key=")
            }
        }
        args += candidate
    }
}
