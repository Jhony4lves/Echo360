package com.jhony4lves.echo360.data.sync

import android.content.Context
import com.jhony4lves.echo360.domain.sync.SaveVaultManifest
import com.jhony4lves.echo360.domain.sync.SaveVaultManifestCodec
import com.jhony4lves.echo360.domain.sync.SaveVaultPathPolicy
import java.io.File

data class StoredSaveVaultSnapshot(
    val manifest: SaveVaultManifest,
    val directory: File,
)

class SaveVaultStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, ROOT_DIRECTORY).apply { mkdirs() }

    fun createPartial(snapshotId: String): File {
        require(snapshotId.matches(Regex("^[A-Za-z0-9._-]{1,96}$"))) { "ID de snapshot inválido." }
        val partial = File(root, ".partial-$snapshotId")
        partial.deleteRecursively()
        check(partial.mkdirs()) { "Não foi possível criar o diretório temporário do Vault." }
        return partial
    }

    fun resolvePayloadFile(snapshotDirectory: File, relativePath: String): File {
        val safeRelative = SaveVaultPathPolicy.validateRelativePath(relativePath)
        val candidate = File(snapshotDirectory, "payload/$safeRelative")
        val payloadRoot = File(snapshotDirectory, "payload").canonicalFile
        val canonical = candidate.canonicalFile
        require(canonical.path == payloadRoot.path || canonical.path.startsWith(payloadRoot.path + File.separator)) {
            "Destino local escapou da raiz do Vault."
        }
        return canonical
    }

    fun writeManifest(snapshotDirectory: File, manifest: SaveVaultManifest) {
        File(snapshotDirectory, MANIFEST_FILE).writeText(SaveVaultManifestCodec.encode(manifest), Charsets.UTF_8)
    }

    fun commitPartial(partial: File, snapshotId: String): File {
        require(partial.parentFile?.canonicalFile == root.canonicalFile) { "Diretório temporário fora do Vault." }
        val destination = File(root, snapshotId)
        check(!destination.exists()) { "Já existe um snapshot com esse ID." }
        check(partial.renameTo(destination)) { "Não foi possível finalizar atomicamente o snapshot local." }
        return destination
    }

    fun discardPartial(partial: File?) {
        if (partial == null) return
        runCatching {
            if (partial.parentFile?.canonicalFile == root.canonicalFile && partial.name.startsWith(".partial-")) {
                partial.deleteRecursively()
            }
        }
    }

    fun snapshots(): List<StoredSaveVaultSnapshot> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory && !it.name.startsWith(".partial-") }
        .mapNotNull { directory ->
            runCatching {
                val manifestFile = File(directory, MANIFEST_FILE)
                val manifest = SaveVaultManifestCodec.decode(manifestFile.readText(Charsets.UTF_8))
                require(manifest.id == directory.name) { "ID do manifesto diverge do diretório." }
                StoredSaveVaultSnapshot(manifest, directory)
            }.getOrNull()
        }
        .sortedByDescending { it.manifest.createdAtEpochMs }
        .toList()

    fun cleanupStalePartials() {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".partial-") }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    companion object {
        private const val ROOT_DIRECTORY = "echo-save-vault"
        private const val MANIFEST_FILE = "manifest.echovault"
    }
}
