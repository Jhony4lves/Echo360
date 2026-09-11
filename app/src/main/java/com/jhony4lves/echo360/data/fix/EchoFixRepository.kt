package com.jhony4lves.echo360.data.fix

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.fix.RemoteMoveResult
import com.jhony4lves.echo360.domain.fix.RemoteMoveStatus
import com.jhony4lves.echo360.domain.fix.RemoteRepairCheck
import com.jhony4lves.echo360.domain.fix.RemoteRepairExecution
import com.jhony4lves.echo360.domain.fix.RemoteRepairState
import com.jhony4lves.echo360.domain.fix.RemoteRepairValidation
import com.jhony4lves.echo360.domain.fix.RepairAction
import com.jhony4lves.echo360.domain.fix.RepairIssue
import com.jhony4lves.echo360.domain.fix.RepairPlan
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.domain.fix.RepairSource
import com.jhony4lves.echo360.domain.fix.RepairSourceKind
import com.jhony4lves.echo360.domain.fix.StockDlcInstallerRule
import com.jhony4lves.echo360.domain.transfer.LocalTransferFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.RemoteTransferFile
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCompareEngine
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
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
                kind = RepairSourceKind.Android,
            )

            val metadataAttempt = runCatching {
                appContext.contentResolver.openInputStream(file.uri)?.use(StfsHeaderReader::inspect)
                    ?: error("Android não conseguiu abrir o arquivo.")
            }
            if (metadataAttempt.isFailure) {
                val error = metadataAttempt.exceptionOrNull()
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = "Ignorado: ${error?.message ?: "header STFS inválido"}",
                    sourcePath = sourcePath,
                )
                continue
            }

            val actionAttempt = runCatching {
                StockDlcInstallerRule.plan(source, metadataAttempt.getOrThrow())
            }
            if (actionAttempt.isFailure) {
                val error = actionAttempt.exceptionOrNull()
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = error?.message ?: "Pacote não é compatível com esta regra.",
                    sourcePath = sourcePath,
                )
                continue
            }

            actions += actionAttempt.getOrThrow()
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
            sourceKind = RepairSourceKind.Android,
        )
    }

    /**
     * Primary EchoFix flow: scans content already present on the Xbox. Only
     * directory listings and tiny STFS header prefixes cross the LAN.
     */
    suspend fun scanXboxStockDlcInstaller(
        rootCanonicalPath: String = DEFAULT_XBOX_SCAN_ROOT,
        requestedRoute: FtpRoute = FtpRoute.Auto,
    ): RepairPlan = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de usar o EchoFix.")
        val canonicalRoot = XboxPath.canonical(rootCanonicalPath)
        val issues = mutableListOf<RepairIssue>()
        val remoteFiles = mutableListOf<RemotePayloadFile>()
        val payloadPaths = linkedSetOf<String>()

        val routed = sessionFactory.connect(profile, requestedRoute)
        val preferredRoute = routed.route
        try {
            collectRemoteInstallerPayloads(
                session = routed.session,
                root = canonicalRoot,
                files = remoteFiles,
                payloadPaths = payloadPaths,
                issues = issues,
            )
        } finally {
            runCatching { routed.session.close() }
        }

        if (remoteFiles.isEmpty()) {
            issues += RepairIssue(
                severity = RepairSeverity.Error,
                message = if (payloadPaths.isEmpty()) {
                    "Nenhum content/0000000000000000/FFED2000/FFFFFFFF foi encontrado em $canonicalRoot."
                } else {
                    "O instalador foi encontrado, mas a pasta FFFFFFFF não contém arquivos."
                },
                sourcePath = canonicalRoot,
            )
        }

        val actions = mutableListOf<RepairAction>()
        for (remoteFile in remoteFiles.distinctBy { it.canonicalPath.lowercase() }) {
            val prefixAttempt = runCatching {
                readRemotePrefix(
                    profile = profile,
                    preferredRoute = preferredRoute,
                    requestedRoute = requestedRoute,
                    canonicalPath = remoteFile.canonicalPath,
                )
            }
            if (prefixAttempt.isFailure) {
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = "Não consegui ler o header remoto: ${prefixAttempt.exceptionOrNull()?.message ?: "erro FTP"}",
                    sourcePath = remoteFile.canonicalPath,
                )
                continue
            }

            val metadataAttempt = runCatching { StfsHeaderReader.inspect(prefixAttempt.getOrThrow()) }
            if (metadataAttempt.isFailure) {
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = "Ignorado: ${metadataAttempt.exceptionOrNull()?.message ?: "header STFS inválido"}",
                    sourcePath = remoteFile.canonicalPath,
                )
                continue
            }

            val source = RepairSource(
                relativePath = remoteFile.canonicalPath.removePrefix("$canonicalRoot/"),
                fileName = remoteFile.name,
                size = remoteFile.size,
                remotePath = remoteFile.canonicalPath,
                kind = RepairSourceKind.Xbox,
            )
            val actionAttempt = runCatching {
                StockDlcInstallerRule.plan(source, metadataAttempt.getOrThrow())
            }
            if (actionAttempt.isFailure) {
                issues += RepairIssue(
                    severity = RepairSeverity.Warning,
                    message = actionAttempt.exceptionOrNull()?.message ?: "Pacote não é compatível com esta regra.",
                    sourcePath = remoteFile.canonicalPath,
                )
                continue
            }
            actions += actionAttempt.getOrThrow()
        }

        if (actions.isEmpty() && issues.none { it.severity == RepairSeverity.Error }) {
            issues += RepairIssue(
                severity = RepairSeverity.Error,
                message = "Nenhum pacote STFS DLC válido foi encontrado nos instaladores detectados.",
                sourcePath = canonicalRoot,
            )
        }

        RepairPlan(
            selectedRootName = canonicalRoot.substringAfterLast('/').ifBlank { canonicalRoot },
            selectedRootUri = "xbox:$canonicalRoot",
            selectedRootPath = canonicalRoot,
            detectedPayloadPath = payloadPaths.joinToString(separator = "\n").ifBlank { null },
            actions = actions,
            issues = issues,
            sourceKind = RepairSourceKind.Xbox,
        )
    }

    /**
     * Android-source validation. This remains as a secondary compatibility
     * mode; the primary Xbox-resident flow uses [validateRemotePlan].
     */
    suspend fun prepareTransferAnalyses(
        plan: RepairPlan,
        requestedRoute: FtpRoute = FtpRoute.Auto,
    ): List<TransferAnalysis> = withContext(Dispatchers.IO) {
        require(plan.sourceKind == RepairSourceKind.Android) {
            "Esse plano usa arquivos que já estão no Xbox; use a validação remota do EchoFix."
        }
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
                            contentUri = requireNotNull(action.source.contentUri) {
                                "Pacote Android sem URI local: ${action.source.fileName}."
                            },
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

    suspend fun validateRemotePlan(
        plan: RepairPlan,
        requestedRoute: FtpRoute = FtpRoute.Auto,
    ): RemoteRepairValidation = withContext(Dispatchers.IO) {
        require(plan.sourceKind == RepairSourceKind.Xbox) {
            "Esse plano não usa origem remota do Xbox."
        }
        require(plan.canExecute) { "O plano EchoFix possui erros e não pode ser validado." }
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de executar o EchoFix.")

        val routed = sessionFactory.connect(profile, requestedRoute)
        try {
            val checks = plan.actions.map { action ->
                val sourcePath = requireNotNull(action.source.remotePath) {
                    "Ação remota sem caminho de origem: ${action.source.fileName}."
                }
                val sourceSize = routed.session.size(sourcePath)
                val destinationSize = routed.session.size(action.destinationPath)
                val expectedSize = action.source.size

                val state = when {
                    sourceSize == null && destinationSize == expectedSize -> RemoteRepairState.AlreadyCorrect
                    sourceSize == null -> RemoteRepairState.SourceMissing
                    sourceSize != expectedSize -> RemoteRepairState.Conflict
                    destinationSize == null -> RemoteRepairState.Missing
                    destinationSize == expectedSize -> RemoteRepairState.AlreadyCorrect
                    else -> RemoteRepairState.Conflict
                }

                RemoteRepairCheck(
                    action = action,
                    state = state,
                    sourceSize = sourceSize,
                    destinationSize = destinationSize,
                )
            }

            RemoteRepairValidation(
                requestedRoute = requestedRoute,
                usedRoute = routed.route,
                checks = checks,
                fallbackReason = routed.fallbackReason,
            )
        } finally {
            runCatching { routed.session.close() }
        }
    }

    /**
     * Moves missing installer packages inside the same Xbox volume using
     * RNFR/RNTO. It never overwrites an existing destination. If verification
     * after the move is ambiguous, EchoFix attempts to rename the package back
     * to its original path before returning a failure.
     */
    suspend fun executeRemoteMoves(
        plan: RepairPlan,
        validation: RemoteRepairValidation,
    ): RemoteRepairExecution = withContext(Dispatchers.IO) {
        require(plan.sourceKind == RepairSourceKind.Xbox) {
            "O plano não é um reparo residente no Xbox."
        }
        require(validation.canMove) {
            "Reparo bloqueado: existem conflitos ou arquivos de origem ausentes."
        }
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de executar o EchoFix.")

        val results = mutableListOf<RemoteMoveResult>()
        for (check in validation.checks) {
            when (check.state) {
                RemoteRepairState.AlreadyCorrect -> {
                    results += RemoteMoveResult(
                        action = check.action,
                        status = RemoteMoveStatus.AlreadyCorrect,
                        route = validation.usedRoute,
                        message = "O pacote já está no destino correto.",
                    )
                }

                RemoteRepairState.Missing -> {
                    results += moveRemoteAction(
                        profile = profile,
                        action = check.action,
                        requestedRoute = validation.requestedRoute,
                        preferredRoute = validation.usedRoute,
                    )
                }

                RemoteRepairState.Conflict,
                RemoteRepairState.SourceMissing,
                -> {
                    results += RemoteMoveResult(
                        action = check.action,
                        status = RemoteMoveStatus.Failed,
                        route = validation.usedRoute,
                        message = "Estado mudou desde a validação; nenhuma alteração foi feita.",
                    )
                }
            }
        }

        RemoteRepairExecution(results)
    }

    private suspend fun moveRemoteAction(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
        action: RepairAction,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
    ): RemoteMoveResult {
        val sourcePath = requireNotNull(action.source.remotePath)
        val destinationPath = action.destinationPath
        val expectedSize = action.source.size

        require(volumeOf(sourcePath) == volumeOf(destinationPath)) {
            "EchoFix não move automaticamente entre volumes diferentes: $sourcePath → $destinationPath"
        }

        var lastFailure: Throwable? = null
        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                lastFailure = routedAttempt.exceptionOrNull()
                continue
            }
            val routed = routedAttempt.getOrThrow()
            val session = routed.session
            try {
                val beforeSource = session.size(sourcePath)
                val beforeDestination = session.size(destinationPath)

                if (beforeDestination == expectedSize) {
                    return RemoteMoveResult(
                        action = action,
                        status = RemoteMoveStatus.AlreadyCorrect,
                        route = route,
                        message = "O destino já contém um pacote com o tamanho esperado; origem não foi alterada.",
                    )
                }
                if (beforeSource != expectedSize) {
                    return RemoteMoveResult(
                        action = action,
                        status = RemoteMoveStatus.Failed,
                        route = route,
                        message = "Origem mudou desde a análise (esperado $expectedSize, atual ${beforeSource ?: "ausente"}).",
                    )
                }
                if (beforeDestination != null) {
                    return RemoteMoveResult(
                        action = action,
                        status = RemoteMoveStatus.Failed,
                        route = route,
                        message = "Destino passou a existir; EchoFix não sobrescreveu o arquivo.",
                    )
                }

                val renameAttempt = runCatching { session.rename(sourcePath, destinationPath) }
                if (renameAttempt.isFailure) {
                    lastFailure = renameAttempt.exceptionOrNull()
                    val sourceAfterFailure = runCatching { session.size(sourcePath) }.getOrNull()
                    val destinationAfterFailure = runCatching { session.size(destinationPath) }.getOrNull()
                    if (sourceAfterFailure == expectedSize && destinationAfterFailure == null) {
                        continue
                    }
                    return RemoteMoveResult(
                        action = action,
                        status = RemoteMoveStatus.Failed,
                        route = route,
                        message = "Rename falhou e o estado ficou ambíguo; EchoFix interrompeu sem tentar outra rota.",
                    )
                }

                val destinationAfter = session.size(destinationPath)
                val sourceAfter = session.size(sourcePath)
                if (destinationAfter == expectedSize && sourceAfter == null) {
                    return RemoteMoveResult(
                        action = action,
                        status = RemoteMoveStatus.Moved,
                        route = route,
                        message = "Movido no próprio HDD e verificado por SIZE.",
                    )
                }

                if (sourceAfter == null && destinationAfter != null) {
                    val rollback = runCatching {
                        session.rename(destinationPath, sourcePath)
                        session.size(sourcePath) == expectedSize && session.size(destinationPath) == null
                    }.getOrDefault(false)
                    if (rollback) {
                        return RemoteMoveResult(
                            action = action,
                            status = RemoteMoveStatus.RolledBack,
                            route = route,
                            message = "A verificação pós-move falhou; o pacote foi devolvido à origem.",
                        )
                    }
                }

                return RemoteMoveResult(
                    action = action,
                    status = RemoteMoveStatus.Failed,
                    route = route,
                    message = "Não foi possível confirmar o move com segurança. Verifique origem e destino antes de tentar novamente.",
                )
            } finally {
                runCatching { session.close() }
            }
        }

        return RemoteMoveResult(
            action = action,
            status = RemoteMoveStatus.Failed,
            route = null,
            message = "Aurora e FTPdll recusaram ou não conseguiram o move server-side: ${lastFailure?.message ?: "sem detalhe"}",
        )
    }

    private suspend fun readRemotePrefix(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
        preferredRoute: FtpRoute,
        requestedRoute: FtpRoute,
        canonicalPath: String,
    ): ByteArray {
        var lastFailure: Throwable? = null
        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                lastFailure = routedAttempt.exceptionOrNull()
                continue
            }
            val session = routedAttempt.getOrThrow().session
            try {
                return session.readPrefixAndClose(canonicalPath, StfsHeaderReader.REQUIRED_BYTES)
            } catch (error: Throwable) {
                lastFailure = error
            } finally {
                runCatching { session.close() }
            }
        }
        throw IllegalStateException(
            "Nenhum FTP conseguiu ler ${StfsHeaderReader.REQUIRED_BYTES} bytes de $canonicalPath.",
            lastFailure,
        )
    }

    private suspend fun collectRemoteInstallerPayloads(
        session: XboxFtpSession,
        root: String,
        files: MutableList<RemotePayloadFile>,
        payloadPaths: MutableSet<String>,
        issues: MutableList<RepairIssue>,
    ) {
        val probedPayloads = linkedSetOf<String>()

        suspend fun probe(candidate: String): Boolean {
            val canonical = XboxPath.canonical(candidate)
            if (!probedPayloads.add(canonical.lowercase())) return false
            val listing = runCatching { session.list(canonical) }.getOrNull() ?: return false
            payloadPaths += canonical
            listing.filter { !it.isDirectory }.forEach { entry ->
                files += RemotePayloadFile(
                    name = entry.name,
                    canonicalPath = entry.canonicalPath,
                    size = entry.size.coerceAtLeast(0L),
                    payloadPath = canonical,
                )
            }
            return true
        }

        directPayloadCandidate(root)?.let { probe(it) }
        if (payloadPaths.isNotEmpty()) return

        data class Pending(val path: String, val depth: Int)
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, 0))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_REMOTE_DIRECTORIES) {
            val current = queue.removeFirst()
            visited += 1

            val listingAttempt = runCatching { session.list(current.path) }
            if (listingAttempt.isFailure) {
                if (current.depth == 0) {
                    throw listingAttempt.exceptionOrNull() ?: IllegalStateException("Falha ao listar $root.")
                }
                continue
            }

            val directories = listingAttempt.getOrThrow().filter { it.isDirectory }
            for (directory in directories) {
                if (directory.name.equals("content", ignoreCase = true)) {
                    val payload = joinCanonical(
                        directory.canonicalPath,
                        "0000000000000000/FFED2000/FFFFFFFF",
                    )
                    if (probe(payload)) continue
                }

                if (current.depth < MAX_REMOTE_DEPTH && shouldTraverse(directory.name)) {
                    queue.add(Pending(directory.canonicalPath, current.depth + 1))
                }
            }
        }

        if (visited >= MAX_REMOTE_DIRECTORIES && payloadPaths.isEmpty()) {
            issues += RepairIssue(
                severity = RepairSeverity.Warning,
                message = "O limite de $MAX_REMOTE_DIRECTORIES pastas foi atingido. Selecione uma raiz mais específica para uma busca completa.",
                sourcePath = root,
            )
        }
    }

    private fun directPayloadCandidate(root: String): String? {
        val canonical = XboxPath.canonical(root)
        val last = canonical.substringAfterLast('/')
        return when {
            last.equals("FFFFFFFF", ignoreCase = true) -> canonical
            last.equals("FFED2000", ignoreCase = true) -> joinCanonical(canonical, "FFFFFFFF")
            last.equals("0000000000000000", ignoreCase = true) -> joinCanonical(canonical, "FFED2000/FFFFFFFF")
            last.equals("content", ignoreCase = true) -> joinCanonical(canonical, "0000000000000000/FFED2000/FFFFFFFF")
            else -> null
        }
    }

    private fun shouldTraverse(name: String): Boolean {
        if (name.startsWith('.')) return false
        return !name.equals("Content", ignoreCase = true) &&
            !name.equals("Cache", ignoreCase = true) &&
            !name.equals("System", ignoreCase = true)
    }

    private fun routeOrder(requestedRoute: FtpRoute, preferredRoute: FtpRoute): List<FtpRoute> {
        if (requestedRoute != FtpRoute.Auto) return listOf(requestedRoute)
        val preferred = when (preferredRoute) {
            FtpRoute.Auto -> FtpRoute.Fast
            else -> preferredRoute
        }
        val alternate = when (preferred) {
            FtpRoute.Fast -> FtpRoute.Background
            FtpRoute.Background -> FtpRoute.Fast
            FtpRoute.Auto -> FtpRoute.Background
        }
        return listOf(preferred, alternate).distinct()
    }

    private fun volumeOf(path: String): String =
        XboxPath.canonical(path).removePrefix("/").substringBefore('/').lowercase()

    private fun joinCanonical(base: String, suffix: String): String =
        XboxPath.canonical(base.trimEnd('/') + "/" + suffix.trimStart('/'))

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

    private data class RemotePayloadFile(
        val name: String,
        val canonicalPath: String,
        val size: Long,
        val payloadPath: String,
    )

    companion object {
        const val DEFAULT_XBOX_SCAN_ROOT: String = "/Hdd1/Games"
        private const val MAX_DIRECTORY_SCAN = 4096
        private const val MAX_REMOTE_DIRECTORIES = 2048
        private const val MAX_REMOTE_DEPTH = 8
    }
}
