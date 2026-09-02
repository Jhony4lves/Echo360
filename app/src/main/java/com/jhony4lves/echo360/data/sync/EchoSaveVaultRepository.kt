package com.jhony4lves.echo360.data.sync

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.sync.SaveVaultExecutionStatus
import com.jhony4lves.echo360.domain.sync.SaveVaultInventory
import com.jhony4lves.echo360.domain.sync.SaveVaultManifest
import com.jhony4lves.echo360.domain.sync.SaveVaultManifestFile
import com.jhony4lves.echo360.domain.sync.SaveVaultProgress
import com.jhony4lves.echo360.domain.sync.SaveVaultResult
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.DigestOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class EchoSaveVaultRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val scanner: SaveVaultInventoryScanner = SaveVaultInventoryScanner(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val store = SaveVaultStore(appContext)

    init {
        store.cleanupStalePartials()
    }

    suspend fun preflight(
        sourceRoot: String,
        requestedRoute: FtpRoute,
    ): SaveVaultInventory {
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de usar o Save Vault.")
        val routed = sessionFactory.connect(profile, requestedRoute)
        return try {
            scanner.scan(
                session = routed.session,
                sourceRoot = sourceRoot,
                route = routed.route,
                fallbackReason = routed.fallbackReason,
            )
        } finally {
            withContext(NonCancellable) { runCatching { routed.session.close() } }
        }
    }

    suspend fun backup(
        sourceRoot: String,
        requestedRoute: FtpRoute,
        cancellationToken: TransferCancellationToken = TransferCancellationToken(),
        onProgress: (SaveVaultProgress) -> Unit = {},
    ): SaveVaultResult {
        val profile = configStore.load()
            ?: return SaveVaultResult(
                status = SaveVaultExecutionStatus.Failed,
                message = "Configure o Xbox na aba Xbox antes de usar o Save Vault.",
            )

        var session: XboxFtpSession? = null
        var partialDirectory: java.io.File? = null
        var currentFile: String? = null
        try {
            onProgress(
                SaveVaultProgress(
                    status = SaveVaultExecutionStatus.Preflight,
                    message = "Inventariando a pasta remota antes de baixar qualquer arquivo.",
                ),
            )

            val routed = sessionFactory.connect(profile, requestedRoute)
            session = routed.session
            val inventory = scanner.scan(
                session = routed.session,
                sourceRoot = sourceRoot,
                route = routed.route,
                fallbackReason = routed.fallbackReason,
            )
            if (cancellationToken.isCancelled()) throw VaultCancelledSignal

            val snapshotId = "vault-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            partialDirectory = store.createPartial(snapshotId)
            val manifestFiles = mutableListOf<SaveVaultManifestFile>()
            var completedBytes = 0L

            inventory.files.forEachIndexed { index, remoteFile ->
                if (cancellationToken.isCancelled()) throw VaultCancelledSignal
                currentFile = remoteFile.relativePath
                val destination = store.resolvePayloadFile(partialDirectory, remoteFile.relativePath)
                val parent = requireNotNull(destination.parentFile)
                check(parent.isDirectory || parent.mkdirs()) {
                    "Não foi possível criar a pasta local de ${remoteFile.relativePath}."
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var currentBytes = 0L

                DigestOutputStream(FileOutputStream(destination), digest).use { output ->
                    routed.session.download(remoteFile.canonicalRemotePath, output) { downloaded ->
                        if (cancellationToken.isCancelled()) throw VaultCancelledSignal
                        currentBytes = downloaded
                        onProgress(
                            SaveVaultProgress(
                                status = SaveVaultExecutionStatus.Downloading,
                                route = routed.route,
                                currentFile = remoteFile.relativePath,
                                fileIndex = index + 1,
                                fileCount = inventory.fileCount,
                                currentFileBytes = downloaded,
                                currentFileSize = remoteFile.size,
                                completedBytes = completedBytes,
                                totalBytes = inventory.totalBytes,
                                message = "Baixando snapshot read-only do Xbox.",
                            ),
                        )
                    }
                }

                if (currentBytes != remoteFile.size || destination.length() != remoteFile.size) {
                    throw IllegalStateException(
                        "Tamanho local divergiu de SIZE em ${remoteFile.relativePath}: " +
                            "esperado=${remoteFile.size}, recebido=${destination.length()}.",
                    )
                }

                onProgress(
                    SaveVaultProgress(
                        status = SaveVaultExecutionStatus.Verifying,
                        route = routed.route,
                        currentFile = remoteFile.relativePath,
                        fileIndex = index + 1,
                        fileCount = inventory.fileCount,
                        currentFileBytes = remoteFile.size,
                        currentFileSize = remoteFile.size,
                        completedBytes = completedBytes,
                        totalBytes = inventory.totalBytes,
                        message = "Fechando SHA-256 local.",
                    ),
                )

                manifestFiles += SaveVaultManifestFile(
                    relativePath = remoteFile.relativePath,
                    size = remoteFile.size,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) },
                )
                completedBytes += remoteFile.size
            }

            val manifest = SaveVaultManifest(
                id = snapshotId,
                createdAtEpochMs = System.currentTimeMillis(),
                sourceRoot = inventory.sourceRoot,
                route = inventory.route,
                fallbackReason = inventory.fallbackReason,
                files = manifestFiles,
            )
            store.writeManifest(partialDirectory, manifest)
            val committed = store.commitPartial(partialDirectory, snapshotId)
            partialDirectory = null

            onProgress(
                SaveVaultProgress(
                    status = SaveVaultExecutionStatus.Completed,
                    route = inventory.route,
                    fileCount = inventory.fileCount,
                    completedBytes = inventory.totalBytes,
                    totalBytes = inventory.totalBytes,
                    message = "Snapshot fechado com manifesto e SHA-256.",
                ),
            )
            return SaveVaultResult(
                status = SaveVaultExecutionStatus.Completed,
                manifest = manifest,
                localSnapshotDirectory = committed.absolutePath,
                message = "Backup local concluído. Nenhum arquivo foi escrito no Xbox.",
            )
        } catch (_: VaultCancelledSignal) {
            return SaveVaultResult(
                status = SaveVaultExecutionStatus.Cancelled,
                failedFile = currentFile,
                message = "Backup cancelado; snapshot parcial removido.",
            )
        } catch (error: Throwable) {
            return SaveVaultResult(
                status = SaveVaultExecutionStatus.Failed,
                failedFile = currentFile,
                message = error.message ?: "Falha ao criar o snapshot local.",
            )
        } finally {
            store.discardPartial(partialDirectory)
            val current = session
            if (current != null) {
                withContext(NonCancellable) { runCatching { current.close() } }
            }
        }
    }

    fun snapshots(): List<StoredSaveVaultSnapshot> = store.snapshots()
}

private object VaultCancelledSignal : RuntimeException(null, null, false, false)
