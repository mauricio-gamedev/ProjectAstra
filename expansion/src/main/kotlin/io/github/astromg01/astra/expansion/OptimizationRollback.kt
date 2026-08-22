package io.github.astromg01.astra.expansion

import java.io.File
import java.security.MessageDigest

data class OptionsSnapshot(val backup: File, val originalSha256: String?, val existed: Boolean)

class OptimizationRollbackStore {
    fun snapshot(optionsFile: File, snapshotRoot: File): OptionsSnapshot {
        snapshotRoot.mkdirs()
        val existed = optionsFile.exists()
        val digest = if (existed) sha256(optionsFile) else null
        val backup = File(snapshotRoot, BACKUP_NAME)
        if (existed) optionsFile.copyTo(backup, overwrite = true) else backup.delete()

        File(snapshotRoot, META_NAME).writeText(
            buildString {
                append("version=1\n")
                append("existed=").append(existed).append('\n')
                append("sha256=").append(digest.orEmpty()).append('\n')
            }
        )
        return OptionsSnapshot(backup, digest, existed)
    }

    fun latest(snapshotBaseRoot: File): OptionsSnapshot? {
        val roots = snapshotBaseRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.sortedWith(compareByDescending<File> { it.name.toLongOrNull() ?: Long.MIN_VALUE }.thenByDescending { it.lastModified() })
            .orEmpty()

        return roots.firstNotNullOfOrNull(::readSnapshot)
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

    private fun readSnapshot(root: File): OptionsSnapshot? {
        val backup = File(root, BACKUP_NAME)
        val meta = File(root, META_NAME)

        if (meta.isFile) {
            val values = meta.readLines()
                .mapNotNull { line ->
                    val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                    if (key.isEmpty()) return@mapNotNull null
                    key to line.substringAfter('=', missingDelimiterValue = "").trim()
                }
                .toMap()
            if (values["version"] != "1") return null
            val existed = values["existed"]?.toBooleanStrictOrNull() ?: return null
            val digest = values["sha256"]?.takeIf(String::isNotBlank)
            if (existed && (!backup.isFile || digest == null || sha256(backup) != digest)) return null
            return OptionsSnapshot(backup, digest, existed)
        }

        // Backward-compatible recovery for snapshots created before metadata existed.
        if (backup.isFile) {
            val digest = sha256(backup)
            return OptionsSnapshot(backup, digest, existed = true)
        }
        return null
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

    private companion object {
        const val BACKUP_NAME = "options.txt.before-astra"
        const val META_NAME = "snapshot.meta"
    }
}
