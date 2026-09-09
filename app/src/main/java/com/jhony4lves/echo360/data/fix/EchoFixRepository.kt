package com.jhony4lves.echo360.data.fix

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.fix.RepairAction
import com.jhony4lves.echo360.domain.fix.RepairIssue
import com.jhony4lves.echo360.domain.fix.RepairPlan
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.domain.fix.RepairSource
import com.jhony4lves.echo360.domain.fix.StockDlcInstallerRule
import com.jhony4lves.echo360.domain.transfer.LocalTransferFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.RemoteTransferFile
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCompareEngine
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class EchoFixRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)

    suspend fun scanStockDlcInstaller(treeUri: Uri): RepairPlan = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("Não foi possível abrir a pasta selecionada.")
        require(root.exists() && root.isDirectory) {
            "A origem selecionada não é uma pasta válida."
        }

        val issues = mutableListOf<RepairIssue>()
        val actions = mutableListOf<RepairAction>()
        val payload = findStockInstallerPayload(root)

        if (payload == null) {
            return@withContext RepairPlan(
                selectedRootName = root.name ?: "Pasta Android",
                selectedRootUri = treeUri.toString(),
                issues = listOf(
                    RepairIssue(
                        severity = RepairSeverity.Error,
                        message = "Não encontrei content/0000000000000000/FFED2000/FFFFFFFF nessa pasta.",
                    ),
                ),
            )
        }

        val payloadFiles = payload.directory.listFiles()
            .filter { it.isFile }
            .sortedBy { it.name?.lowercase().orEmpty() }

        if (payloadFiles.isEmpty()) {
            issues += RepairIssue(
                severity = RepairSeverity.Error,
                message = "A pasta FFFFFFFF foi encontrada, mas não contém arquivos.",
                sourcePath = payload.relativePath,
            )
        }

        for (file in payloadFiles) {
            val fileName = file.name?.trim().orEmpty()
            if (fileName.isBlank()) {
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = "Um arquivo sem nome foi ignorado.",
                    sourcePath = payload.relativePath,
                )
                continue
            }

            val sourcePath = listOf(payload.relativePath, fileName)
                .filter(String::isNotBlank)
                .joinToString("/")
            val source = RepairSource(
                relativePath = sourcePath,
                fileName = fileName,
                size = file.length().coerceAtLeast(0L),
                contentUri = file.uri.toString(),
            )

            val metadataResult = runCatching {
                appContext.contentResolver.openInputStream(file.uri)?.use(StfsHeaderReader::inspect)
                    ?: error("Android não conseguiu abrir o arquivo.")
            }
            if (metadataResult.isFailure) {
                val error = metadataResult.exceptionOrNull()
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = "Ignorado: ${error?.message ?: "header STFS inválido"}",
                    sourcePath = sourcePath,
                )
                continue
            }
            val metadata = metadataResult.getOrThrow()

            val actionResult = runCatching {
                StockDlcInstallerRule.plan(source, metadata)
            }
            if (actionResult.isFailure) {
                val error = actionResult.exceptionOrNull()
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = error?.message ?: "Pacote não é compatível com esta regra.",
                    sourcePath = sourcePath,
                )
                continue
            }

            actions += actionResult.getOrThrow()
        }

        if (actions.isEmpty() && issues.none { it.severity == RepairSeverity.Error }) {
            issues += RepairIssue(
                severity = RepairSeverity.Error,
                message = "Nenhum pacote STFS DLC válido foi encontrado dentro de FFFFFFFF.",
                sourcePath = payload.relativePath,
            )
        }

        RepairPlan(
            selectedRootName = root.name ?: "Pasta Android",
            selectedRootUri = treeUri.toString(),
            detectedPayloadPath = payload.relativePath,
            actions = actions,
            issues = issues,
        )
    }

    /**
     * Checks the exact EchoFix destinations on the Xbox and converts each
     * destination group into the normal EchoTransfer analysis model. No game
     * file is written here; Auto may only create its bounded benchmark probe.
     */
    suspend fun prepareTransferAnalyses(
        plan: RepairPlan,
        requestedRoute: FtpRoute = FtpRoute.Auto,
    ): List<TransferAnalysis> = withContext(Dispatchers.IO) {
        require(plan.canExecute) { "O plano EchoFix possui erros e não pode ser preparado para envio." }
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de executar o EchoFix.")

        val routed = sessionFactory.connect(profile, requestedRoute)
        try {
            plan.actions
                .groupBy(RepairAction::destinationRoot)
                .toSortedMap()
                .map { (destinationRoot, actions) ->
                    val localFiles = actions.map { action ->
                        LocalTransferFile(
                            relativePath = destinationFileName(action),
                            size = action.source.size,
                            contentUri = action.source.contentUri,
                        )
                    }
                    require(localFiles.map { it.relativePath.lowercase() }.toSet().size == localFiles.size) {
                        "O plano gera dois pacotes com o mesmo destino em $destinationRoot."
                    }

                    val remoteFiles = actions.mapNotNull { action ->
                        val remoteSize = routed.session.size(action.destinationPath) ?: return@mapNotNull null
                        RemoteTransferFile(
                            relativePath = destinationFileName(action),
                            size = remoteSize,
                            canonicalPath = action.destinationPath,
                        )
                    }

                    TransferCompareEngine.compare(
                        local = LocalTransferTree(
                            rootUri = plan.selectedRootUri,
                            rootName = "EchoFix",
                            files = localFiles,
                            directories = setOf(""),
                        ),
                        remoteRoot = destinationRoot,
                        requestedRoute = requestedRoute,
                        usedRoute = routed.route,
                        fallbackReason = routed.fallbackReason,
                        remoteFiles = remoteFiles,
                    )
                }
        } finally {
            runCatching { routed.session.close() }
        }
    }

    private fun destinationFileName(action: RepairAction): String =
        action.destinationPath.substringAfterLast('/')

    private fun findStockInstallerPayload(root: DocumentFile): PayloadDirectory? {
        val rootName = root.name.orEmpty()
        when {
            rootName.equals("FFFFFFFF", ignoreCase = true) -> {
                return PayloadDirectory(root, "FFFFFFFF")
            }

            rootName.equals("FFED2000", ignoreCase = true) -> {
                findChildDirectory(root, "FFFFFFFF")?.let {
                    return PayloadDirectory(it, "FFED2000/FFFFFFFF")
                }
            }

            rootName.equals("0000000000000000", ignoreCase = true) -> {
                findPath(root, listOf("FFED2000", "FFFFFFFF"))?.let {
                    return PayloadDirectory(it, "0000000000000000/FFED2000/FFFFFFFF")
                }
            }

            rootName.equals("content", ignoreCase = true) -> {
                findPath(root, listOf("0000000000000000", "FFED2000", "FFFFFFFF"))?.let {
                    return PayloadDirectory(it, "content/0000000000000000/FFED2000/FFFFFFFF")
                }
            }
        }

        data class Pending(val directory: DocumentFile, val relativePath: String)

        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, ""))
        var visitedDirectories = 0

        while (queue.isNotEmpty() && visitedDirectories < MAX_DIRECTORY_SCAN) {
            val current = queue.removeFirst()
            visitedDirectories += 1

            current.directory.listFiles()
                .filter { it.isDirectory }
                .forEach { child ->
                    val name = child.name.orEmpty()
                    val relative = listOf(current.relativePath, name)
                        .filter(String::isNotBlank)
                        .joinToString("/")

                    if (name.equals("content", ignoreCase = true)) {
                        findPath(
                            child,
                            listOf("0000000000000000", "FFED2000", "FFFFFFFF"),
                        )?.let { payload ->
                            val prefix = listOf(relative, "0000000000000000/FFED2000/FFFFFFFF")
                                .filter(String::isNotBlank)
                                .joinToString("/")
                            return PayloadDirectory(payload, prefix)
                        }
                    }

                    queue.add(Pending(child, relative))
                }
        }

        return null
    }

    private fun findPath(start: DocumentFile, segments: List<String>): DocumentFile? {
        var current = start
        for (segment in segments) {
            current = findChildDirectory(current, segment) ?: return null
        }
        return current
    }

    private fun findChildDirectory(parent: DocumentFile, name: String): DocumentFile? =
        parent.listFiles().firstOrNull {
            it.isDirectory && it.name.equals(name, ignoreCase = true)
        }

    private data class PayloadDirectory(
        val directory: DocumentFile,
        val relativePath: String,
    )

    companion object {
        private const val MAX_DIRECTORY_SCAN = 4096
    }
}
