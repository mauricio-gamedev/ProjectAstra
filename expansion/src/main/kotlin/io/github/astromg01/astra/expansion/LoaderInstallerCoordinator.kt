package io.github.astromg01.astra.expansion

import java.io.File

data class LoaderInstallationResult(
    val selection: LoaderSelection,
    val installedVersionId: String,
    val profileFile: File?,
    val notes: List<String>,
)

class LoaderArtifactDownloader(private val http: SimpleHttpClient = SimpleHttpClient()) {
    fun download(root: File, artifact: RemoteArtifact): File {
        require(artifact.url.startsWith("https://")) { "Refusing non-HTTPS loader installer" }
        require(artifact.sha1 != null || artifact.sha512 != null) { "Loader installer has no integrity hash: ${artifact.url}" }
        val target = File(root, artifact.destinationRelativePath)
        target.parentFile?.mkdirs()
        if (target.isFile && matches(target, artifact)) return target
        val temp = File(target.parentFile, target.name + ".astra.part")
        temp.delete()
        http.download(artifact.url, temp, maxBytes = 128L * 1024 * 1024)
        require(matches(temp, artifact)) { "Loader installer integrity mismatch" }
        if (target.exists() && !target.delete()) error("Could not replace loader installer")
        require(temp.renameTo(target)) { "Could not finalize loader installer" }
        return target
    }

    private fun matches(file: File, artifact: RemoteArtifact): Boolean {
        artifact.size?.let { if (file.length() != it) return false }
        artifact.sha512?.let { if (!Hashing.sha512(file).equals(it, ignoreCase = true)) return false }
        artifact.sha1?.let { if (!Hashing.sha1(file).equals(it, ignoreCase = true)) return false }
        return true
    }
}

class LoaderInstallerCoordinator(
    private val registry: LoaderRegistry = LoaderRegistry(),
    private val profileMaterializer: LoaderProfileMaterializer = LoaderProfileMaterializer(),
    private val artifactDownloader: LoaderArtifactDownloader = LoaderArtifactDownloader(),
    private val modernForgeInstaller: ModernForgeInstaller = ModernForgeInstaller(),
) {
    fun install(
        minecraftHome: File,
        minecraftVersion: String,
        selection: LoaderSelection,
        javaExecutable: File,
    ): LoaderInstallationResult {
        val plan = registry.provider(selection.type).resolve(minecraftVersion, selection.version)
        return when (selection.type) {
            LoaderType.VANILLA -> LoaderInstallationResult(selection, minecraftVersion, null, plan.notes)
            LoaderType.FABRIC, LoaderType.QUILT -> {
                val profile = profileMaterializer.materialize(minecraftHome, plan)
                LoaderInstallationResult(plan.loader, plan.profileId ?: profile.parentFile.name, profile, plan.notes)
            }
            LoaderType.FORGE, LoaderType.NEOFORGE -> {
                require(plan.artifacts.size == 1) { "Modern Forge/NeoForge plan must contain exactly one installer" }
                val installer = artifactDownloader.download(File(minecraftHome, ".astra"), plan.artifacts.single())
                val result = modernForgeInstaller.install(installer, minecraftHome, javaExecutable)
                LoaderInstallationResult(
                    selection = plan.loader,
                    installedVersionId = result.versionId,
                    profileFile = result.profileFile,
                    notes = plan.notes + "processorsRun=${result.processorsRun}" + "processorsSkipped=${result.processorsSkipped}",
                )
            }
        }
    }
}
