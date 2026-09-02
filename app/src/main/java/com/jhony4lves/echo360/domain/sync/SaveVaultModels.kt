package com.jhony4lves.echo360.domain.sync

import com.jhony4lves.echo360.network.ftp.FtpRoute

data class SaveVaultLimits(
    val maxFiles: Int = 2_048,
    val maxDirectories: Int = 512,
    val maxBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxDepth: Int = 24,
) {
    init {
        require(maxFiles > 0) { "maxFiles deve ser positivo." }
        require(maxDirectories > 0) { "maxDirectories deve ser positivo." }
        require(maxBytes > 0L) { "maxBytes deve ser positivo." }
        require(maxDepth > 0) { "maxDepth deve ser positivo." }
    }
}

data class SaveVaultInventoryFile(
    val relativePath: String,
    val canonicalRemotePath: String,
    val size: Long,
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath não pode ser vazio." }
        require(canonicalRemotePath.startsWith('/')) { "Path remoto precisa ser canônico." }
        require(size >= 0L) { "Tamanho remoto inválido." }
    }
}

data class SaveVaultInventory(
    val sourceRoot: String,
    val route: FtpRoute,
    val fallbackReason: String? = null,
    val directoryCount: Int,
    val files: List<SaveVaultInventoryFile>,
) {
    init {
        require(sourceRoot.startsWith('/')) { "sourceRoot precisa ser canônico." }
        require(directoryCount > 0) { "Inventário precisa incluir ao menos a raiz." }
    }

    val fileCount: Int get() = files.size
    val totalBytes: Long get() = files.sumOf(SaveVaultInventoryFile::size)
}

data class SaveVaultManifestFile(
    val relativePath: String,
    val size: Long,
    val sha256: String,
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath não pode ser vazio." }
        require(size >= 0L) { "Tamanho inválido." }
        require(SHA256_REGEX.matches(sha256)) { "SHA-256 inválido." }
    }

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

data class SaveVaultManifest(
    val id: String,
    val createdAtEpochMs: Long,
    val sourceRoot: String,
    val route: FtpRoute,
    val fallbackReason: String? = null,
    val files: List<SaveVaultManifestFile>,
) {
    init {
        require(id.isNotBlank()) { "ID do snapshot não pode ser vazio." }
        require(createdAtEpochMs >= 0L) { "Timestamp inválido." }
        require(sourceRoot.startsWith('/')) { "sourceRoot precisa ser canônico." }
        require(files.distinctBy { it.relativePath.lowercase() }.size == files.size) {
            "Manifesto contém paths duplicados."
        }
    }

    val totalBytes: Long get() = files.sumOf(SaveVaultManifestFile::size)
    val fileCount: Int get() = files.size
}

enum class SaveVaultExecutionStatus {
    Preflight,
    Downloading,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

data class SaveVaultProgress(
    val status: SaveVaultExecutionStatus,
    val route: FtpRoute? = null,
    val currentFile: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val currentFileBytes: Long = 0L,
    val currentFileSize: Long = 0L,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
) {
    val logicalBytes: Long
        get() = (completedBytes + currentFileBytes).coerceAtMost(totalBytes)

    val overallFraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (logicalBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

data class SaveVaultResult(
    val status: SaveVaultExecutionStatus,
    val manifest: SaveVaultManifest? = null,
    val localSnapshotDirectory: String? = null,
    val failedFile: String? = null,
    val message: String? = null,
) {
    val succeeded: Boolean get() = status == SaveVaultExecutionStatus.Completed
}
