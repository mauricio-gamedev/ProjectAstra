package io.github.astromg01.astra.expansion

import java.io.File
import java.security.MessageDigest

data class OptionsSnapshot(val backup: File, val originalSha256: String?, val existed: Boolean)

class OptimizationRollbackStore {
    fun snapshot(optionsFile: File, snapshotRoot: File): OptionsSnapshot {
        snapshotRoot.mkdirs()
        val existed = optionsFile.exists()
        val digest = if (existed) sha256(optionsFile) else null
        val backup = File(snapshotRoot, "options.txt.before-astra")
        if (existed) optionsFile.copyTo(backup, overwrite = true) else backup.delete()
        return OptionsSnapshot(backup, digest, existed)
    }

    fun rollback(optionsFile: File, snapshot: OptionsSnapshot) {
        if (!snapshot.existed) {
            if (optionsFile.exists() && !optionsFile.delete()) error("Could not remove generated options.txt")
            return
        }
        require(snapshot.backup.exists()) { "Optimization rollback backup is missing" }
        val backupDigest = sha256(snapshot.backup)
        require(backupDigest == snapshot.originalSha256) { "Optimization rollback backup failed integrity validation" }
        optionsFile.parentFile?.mkdirs()
        snapshot.backup.copyTo(optionsFile, overwrite = true)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
