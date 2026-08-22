package io.github.astromg01.astra.expansion

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RollbackAndPresetTest {
    @Test
    fun latestRollbackSurvivesStoreRecreation() {
        val root = Files.createTempDirectory("astra-rollback-latest").toFile()
        val options = root.resolve("options.txt")
        val snapshots = root.resolve(".astra/rollback")
        options.writeText("renderDistance:12\ncustom:keep\n")

        val firstStore = OptimizationRollbackStore()
        val first = firstStore.snapshot(options, snapshots.resolve("100"))
        assertTrue(first.backup.isFile)
        options.writeText("renderDistance:6\n")

        val recreatedStore = OptimizationRollbackStore()
        val latest = assertNotNull(recreatedStore.latest(snapshots))
        recreatedStore.rollback(options, latest)

        assertEquals("renderDistance:12\ncustom:keep\n", options.readText())
        assertTrue(snapshots.resolve("100/snapshot.meta").isFile)
    }

    @Test
    fun latestRollbackChoosesNewestValidSnapshot() {
        val root = Files.createTempDirectory("astra-rollback-order").toFile()
        val options = root.resolve("options.txt")
        val snapshots = root.resolve("snapshots")
        val store = OptimizationRollbackStore()

        options.writeText("value:old\n")
        store.snapshot(options, snapshots.resolve("100"))
        options.writeText("value:new\n")
        store.snapshot(options, snapshots.resolve("200"))
        options.writeText("value:changed\n")

        store.rollback(options, assertNotNull(store.latest(snapshots)))
        assertEquals("value:new\n", options.readText())
    }

    @Test
    fun performancePresetIsExplicitAndNeverTargetsVanilla() {
        assertTrue(AstraPerformancePreset.targets(LoaderType.VANILLA).isEmpty())
        assertEquals(
            listOf("sodium", "lithium", "ferrite-core", "immediatelyfast"),
            AstraPerformancePreset.targets(LoaderType.FABRIC),
        )
        assertFalse(AstraPerformancePreset.targets(LoaderType.NEOFORGE).isEmpty())
    }
}
