package com.jhony4lves.echo360.data.sync

import com.jhony4lves.echo360.domain.sync.SaveVaultInventory
import com.jhony4lves.echo360.domain.sync.SaveVaultInventoryFile
import com.jhony4lves.echo360.domain.sync.SaveVaultLimits
import com.jhony4lves.echo360.domain.sync.SaveVaultPathPolicy
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSession

class SaveVaultInventoryScanner(
    private val limits: SaveVaultLimits = SaveVaultLimits(),
) {
    suspend fun scan(
        session: XboxFtpSession,
        sourceRoot: String,
        route: FtpRoute,
        fallbackReason: String? = null,
    ): SaveVaultInventory {
        val root = SaveVaultPathPolicy.canonicalSourceRoot(sourceRoot)
        val files = mutableListOf<SaveVaultInventoryFile>()
        val seenDirectories = hashSetOf("")
        val seenFiles = hashSetOf<String>()
        var directoryCount = 1
        var totalBytes = 0L

        suspend fun walk(remoteDirectory: String, relativeDirectory: String) {
            val entries = session.list(remoteDirectory)
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (entry in entries) {
                val relative = SaveVaultPathPolicy.childRelativePath(relativeDirectory, entry.name)
                val key = relative.lowercase()
                val remotePath = SaveVaultPathPolicy.canonicalRemoteFile(root, relative)
                val depth = SaveVaultPathPolicy.depth(relative)
                if (depth > limits.maxDepth) {
                    throw SaveVaultLimitExceededException("A árvore excede ${limits.maxDepth} níveis.")
                }

                if (entry.isDirectory) {
                    if (!seenDirectories.add(key)) {
                        throw IllegalStateException("Diretório duplicado no inventário: $relative")
                    }
                    directoryCount += 1
                    if (directoryCount > limits.maxDirectories) {
                        throw SaveVaultLimitExceededException(
                            "O Vault excede ${limits.maxDirectories} diretórios; escolha uma pasta menor.",
                        )
                    }
                    walk(remotePath, relative)
                } else {
                    if (!seenFiles.add(key) || key in seenDirectories) {
                        throw IllegalStateException("Path duplicado no inventário: $relative")
                    }
                    if (files.size + 1 > limits.maxFiles) {
                        throw SaveVaultLimitExceededException(
                            "O Vault excede ${limits.maxFiles} arquivos; escolha uma pasta menor.",
                        )
                    }

                    val size = session.size(remotePath)
                        ?: throw IllegalStateException(
                            "FTP não confirmou SIZE de $relative; o Vault não vai estimar o tamanho.",
                        )
                    require(size >= 0L) { "SIZE remoto inválido em $relative." }
                    if (Long.MAX_VALUE - totalBytes < size) {
                        throw SaveVaultLimitExceededException("Soma de tamanhos excedeu o limite numérico.")
                    }
                    totalBytes += size
                    if (totalBytes > limits.maxBytes) {
                        throw SaveVaultLimitExceededException(
                            "O Vault excede ${limits.maxBytes} bytes; escolha uma pasta menor.",
                        )
                    }

                    files += SaveVaultInventoryFile(
                        relativePath = relative,
                        canonicalRemotePath = remotePath,
                        size = size,
                    )
                }
            }
        }

        walk(root, "")
        return SaveVaultInventory(
            sourceRoot = root,
            route = route,
            fallbackReason = fallbackReason,
            directoryCount = directoryCount,
            files = files.sortedBy { it.relativePath.lowercase() },
        )
    }
}

class SaveVaultLimitExceededException(message: String) : IllegalStateException(message)
