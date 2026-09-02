package com.jhony4lves.echo360.data.sync

import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityEngine
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityReport
import com.jhony4lves.echo360.domain.sync.SaveVaultLocalFileEvidence
import com.jhony4lves.echo360.domain.sync.SaveVaultPathPolicy
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class SaveVaultIntegrityProgress(
    val currentFile: String,
    val fileIndex: Int,
    val fileCount: Int,
)

class SaveVaultLocalIntegrityVerifier(
    private val maxObservedFiles: Int = 4_096,
    private val maxObservedDirectories: Int = 1_024,
) {
    init {
        require(maxObservedFiles > 0) { "maxObservedFiles deve ser positivo." }
        require(maxObservedDirectories > 0) { "maxObservedDirectories deve ser positivo." }
    }

    suspend fun verify(
        snapshot: StoredSaveVaultSnapshot,
        cancellationToken: TransferCancellationToken = TransferCancellationToken(),
        onProgress: (SaveVaultIntegrityProgress) -> Unit = {},
    ): SaveVaultIntegrityReport = withContext(Dispatchers.IO) {
        val payloadRoot = File(snapshot.directory, "payload").canonicalFile
        val actualFiles = if (payloadRoot.isDirectory) {
            enumerateFiles(payloadRoot, cancellationToken)
        } else {
            emptyList()
        }
        val actualByKey = actualFiles.associateBy { it.relativePath.lowercase() }
        val expectedKeys = snapshot.manifest.files.mapTo(hashSetOf()) { it.relativePath.lowercase() }

        val evidence = mutableListOf<SaveVaultLocalFileEvidence>()
        val wrongTypes = mutableListOf<String>()

        snapshot.manifest.files.forEachIndexed { index, expected ->
            coroutineContext.ensureActive()
            if (cancellationToken.isCancelled()) throw SaveVaultIntegrityCancelledException()

            val local = resolvePayloadPath(payloadRoot, expected.relativePath)
            if (!local.exists()) return@forEachIndexed
            if (!local.isFile) {
                wrongTypes += expected.relativePath
                return@forEachIndexed
            }

            onProgress(
                SaveVaultIntegrityProgress(
                    currentFile = expected.relativePath,
                    fileIndex = index + 1,
                    fileCount = snapshot.manifest.fileCount,
                ),
            )
            evidence += SaveVaultLocalFileEvidence(
                relativePath = expected.relativePath,
                size = local.length(),
                sha256 = sha256(local, cancellationToken),
            )
        }

        val extras = actualByKey.values
            .asSequence()
            .filter { it.relativePath.lowercase() !in expectedKeys }
            .map(LocalObservedFile::relativePath)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

        SaveVaultIntegrityEngine.verify(
            manifest = snapshot.manifest,
            localFiles = evidence,
            wrongObjectTypePaths = wrongTypes,
            extraRelativePaths = extras,
        )
    }

    private fun enumerateFiles(
        payloadRoot: File,
        cancellationToken: TransferCancellationToken,
    ): List<LocalObservedFile> {
        val files = mutableListOf<LocalObservedFile>()
        val seenDirectories = hashSetOf<String>()
        var directoryCount = 0

        fun walk(directory: File) {
            if (cancellationToken.isCancelled()) throw SaveVaultIntegrityCancelledException()
            val canonicalDirectory = directory.canonicalFile
            requireContained(payloadRoot, canonicalDirectory)
            if (!seenDirectories.add(canonicalDirectory.path)) return
            directoryCount += 1
            if (directoryCount > maxObservedDirectories) {
                throw IllegalStateException(
                    "Snapshot local excede $maxObservedDirectories diretórios durante a verificação.",
                )
            }

            canonicalDirectory.listFiles()
                .orEmpty()
                .sortedBy { it.name.lowercase() }
                .forEach { child ->
                    if (cancellationToken.isCancelled()) throw SaveVaultIntegrityCancelledException()
                    SaveVaultPathPolicy.validateSegment(child.name)
                    val canonicalChild = child.canonicalFile
                    requireContained(payloadRoot, canonicalChild)
                    val relative = canonicalChild.relativeTo(payloadRoot).path
                        .replace(File.separatorChar, '/')
                    SaveVaultPathPolicy.validateRelativePath(relative)

                    when {
                        canonicalChild.isDirectory -> walk(canonicalChild)
                        canonicalChild.isFile -> {
                            files += LocalObservedFile(relative)
                            if (files.size > maxObservedFiles) {
                                throw IllegalStateException(
                                    "Snapshot local excede $maxObservedFiles arquivos durante a verificação.",
                                )
                            }
                        }
                    }
                }
        }

        walk(payloadRoot)
        return files
    }

    private fun resolvePayloadPath(payloadRoot: File, relativePath: String): File {
        val relative = SaveVaultPathPolicy.validateRelativePath(relativePath)
        val candidate = File(payloadRoot, relative).canonicalFile
        requireContained(payloadRoot, candidate)
        return candidate
    }

    private fun requireContained(payloadRoot: File, candidate: File) {
        val rootPath = payloadRoot.canonicalFile.path
        val candidatePath = candidate.canonicalFile.path
        require(candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)) {
            "Path local escapou da raiz do payload do Vault."
        }
    }

    private fun sha256(
        file: File,
        cancellationToken: TransferCancellationToken,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).buffered().use { input ->
            while (true) {
                if (cancellationToken.isCancelled()) throw SaveVaultIntegrityCancelledException()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private data class LocalObservedFile(val relativePath: String)
}

class SaveVaultIntegrityCancelledException : RuntimeException(null, null, false, false)
