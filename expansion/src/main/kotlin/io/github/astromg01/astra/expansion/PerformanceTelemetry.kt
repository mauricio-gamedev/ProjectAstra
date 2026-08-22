package io.github.astromg01.astra.expansion

data class MinecraftLogMetrics(
    val maxServerBehindMs: Long,
    val lastServerBehindMs: Long,
    val maxTicksBehind: Long,
    val renderDistance: Int?,
    val simulationDistance: Int?,
    val joinedWorld: Boolean,
)

object MinecraftLogPerformanceAnalyzer {
    private val behind = Regex("Running\\s+(\\d+)ms\\s+or\\s+(\\d+)\\s+ticks behind", RegexOption.IGNORE_CASE)
    private val render = Regex("Changing view distance to\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val simulation = Regex("Changing simulation distance to\\s+(\\d+)", RegexOption.IGNORE_CASE)

    fun analyze(log: String): MinecraftLogMetrics {
        val behindMatches = behind.findAll(log).toList()
        val renderMatches = render.findAll(log).toList()
        val simulationMatches = simulation.findAll(log).toList()
        val delays = behindMatches.map { it.groupValues[1].toLong() }
        val ticks = behindMatches.map { it.groupValues[2].toLong() }
        return MinecraftLogMetrics(
            maxServerBehindMs = delays.maxOrNull() ?: 0,
            lastServerBehindMs = delays.lastOrNull() ?: 0,
            maxTicksBehind = ticks.maxOrNull() ?: 0,
            renderDistance = renderMatches.lastOrNull()?.groupValues?.get(1)?.toInt(),
            simulationDistance = simulationMatches.lastOrNull()?.groupValues?.get(1)?.toInt(),
            joinedWorld = " joined the game" in log,
        )
    }
}
