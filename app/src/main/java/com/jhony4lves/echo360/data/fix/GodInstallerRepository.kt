package com.jhony4lves.echo360.data.fix

import android.content.Context
import android.os.StatFs
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.fix.GodDataPart
import com.jhony4lves.echo360.domain.fix.GodEmbeddedPayload
import com.jhony4lves.echo360.domain.fix.GodInstallResult
import com.jhony4lves.echo360.domain.fix.GodInstallStatus
import com.jhony4lves.echo360.domain.fix.GodInstallerExecution
import com.jhony4lves.echo360.domain.fix.GodInstallerPlan
import com.jhony4lves.echo360.domain.fix.GodInstallerScanResult
import com.jhony4lves.echo360.domain.fix.GodInstallerValidation
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.GodPayloadCheck
import com.jhony4lves.echo360.domain.fix.GodPayloadState
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.domain.fix.GodRepairStage
import com.jhony4lves.echo360.domain.fix.RepairIssue
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.domain.fix.RepairSource
import com.jhony4lves.echo360.domain.fix.RepairSourceKind
import com.jhony4lves.echo360.domain.fix.StockDlcInstallerRule
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

class GodInstallerRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)

    suspend fun scanGodPackages(
        rootCanonicalPath: String = DEFAULT_GOD_ROOT,
        requestedRoute: FtpRoute = FtpRoute.Auto,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerScanResult = withContext(Dispatchers.IO) {
        cleanupStaleTemps()
        val profile = requireProfile()
        val root = XboxPath.canonical(rootCanonicalPath)
        val issues = mutableListOf<RepairIssue>()
        val candidates = mutableListOf<GodPackageCandidate>()

        onProgress(
            GodRepairProgress(
                stage = GodRepairStage.ReadingContainer,
                message = "Procurando pacotes GOD em $root...",
            ),
        )

        val routed = sessionFactory.connect(profile, requestedRoute)
        val preferredRoute = routed.route
        try {
            val godDirectories = discoverGodDirectories(routed.session, root)
            var processed = 0L
            for (godDirectory in godDirectories) {
                val listing = runCatching { routed.session.list(godDirectory) }.getOrElse { error ->
                    issues += RepairIssue(
                        severity = RepairSeverity.Warning,
                        message = "Não foi possível listar $godDirectory: ${error.message ?: "erro FTP"}",
                        sourcePath = godDirectory,
                    )
                    continue
                }

                val dataDirectories = listing
                    .filter(RemoteEntry::isDirectory)
                    .associateBy { it.name.lowercase() }

                val packageFiles = listing
                    .filter { !it.isDirectory }
                    .sortedBy { it.name.lowercase() }

                for (packageFile in packageFiles) {
                    val dataDirectory = dataDirectories["${packageFile.name}.data".lowercase()] ?: continue
                    val partListing = runCatching { routed.session.list(dataDirectory.canonicalPath) }
                        .getOrElse { error ->
                            issues += RepairIssue(
                                severity = RepairSeverity.Warning,
                                message = "GOD encontrado, mas não consegui listar ${dataDirectory.canonicalPath}: ${error.message ?: "erro FTP"}",
                                sourcePath = packageFile.canonicalPath,
                            )
                            continue
                        }
                    val parts = toGodParts(partListing)
                    if (parts.isEmpty()) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "${packageFile.name} possui pasta .data, mas nenhum DataNNNN válido.",
                            sourcePath = dataDirectory.canonicalPath,
                        )
                        continue
                    }

                    val prefix = runCatching {
                        readPrefixWithFallback(
                            profile = profile,
                            canonicalPath = packageFile.canonicalPath,
                            byteCount = StfsHeaderReader.REQUIRED_BYTES,
                            requestedRoute = requestedRoute,
                            preferredRoute = preferredRoute,
                        ).first
                    }.getOrElse { error ->
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "Não consegui ler o header GOD ${packageFile.name}: ${error.message ?: "erro FTP"}",
                            sourcePath = packageFile.canonicalPath,
                        )
                        continue
                    }

                    val metadata = runCatching { StfsHeaderReader.inspect(prefix) }.getOrElse { error ->
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "Container ignorado: ${error.message ?: "STFS inválido"}",
                            sourcePath = packageFile.canonicalPath,
                        )
                        continue
                    }
                    if (metadata.contentType != CONTENT_TYPE_GOD) continue

                    val titleDirectory = godDirectory
                        .substringBeforeLast("/00007000", missingDelimiterValue = "")
                        .substringAfterLast('/')
                        .ifBlank { metadata.titleId }

                    if (!titleDirectory.equals(metadata.titleId, ignoreCase = true)) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "Title ID do header (${metadata.titleId}) difere da pasta ($titleDirectory). O EchoFix não moverá o GOD; apenas analisará seu conteúdo.",
                            sourcePath = packageFile.canonicalPath,
                        )
                    }

                    candidates += GodPackageCandidate(
                        titleIdDirectory = titleDirectory,
                        packageName = packageFile.name,
                        headerPath = packageFile.canonicalPath,
                        dataDirectoryPath = dataDirectory.canonicalPath,
                        headerSize = packageFile.size.coerceAtLeast(0L),
                        metadata = metadata,
                        dataParts = parts,
                        // Worst-case estimate before the XSF probe. Deep analysis
                        // replaces this with the exact value.
                        estimatedIsoBytes = parts.sumOf(GodDataPart::payloadSize) +
                            GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES,
                    )
                    processed += 1
                    onProgress(
                        GodRepairProgress(
                            stage = GodRepairStage.ReadingContainer,
                            message = "${candidates.size} GOD(s) encontrado(s)...",
                            completedBytes = processed,
                            totalBytes = maxOf(processed, godDirectories.size.toLong()),
                        ),
                    )
                }
            }
        } finally {
            runCatching { routed.session.close() }
        }

        if (candidates.isEmpty()) {
            issues += RepairIssue(
                severity = RepairSeverity.Info,
                message = "Nenhum pacote GOD com arquivo .data foi encontrado em $root.",
                sourcePath = root,
            )
        }

        GodInstallerScanResult(
            rootPath = root,
            candidates = candidates.sortedWith(
                compareByDescending<GodPackageCandidate> { it.metadata.discNumber }
                    .thenBy { it.metadata.titleId }
                    .thenBy { it.packageName.lowercase() },
            ),
            issues = issues,
        )
    }

    suspend fun analyzeInstallerGod(
        candidate: GodPackageCandidate,
        requestedRoute: FtpRoute = FtpRoute.Auto,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerPlan = withContext(Dispatchers.IO) {
        val profile = requireProfile()
        val preferred = sessionFactory.connect(profile, requestedRoute).let { routed ->
            try {
                routed.route
            } finally {
                runCatching { routed.session.close() }
            }
        }

        onProgress(
            GodRepairProgress(
                stage = GodRepairStage.ReadingContainer,
                message = "Validando estrutura do GOD ${candidate.packageName}...",
            ),
        )

        val currentParts = refreshAndValidateParts(profile, candidate, requestedRoute, preferred)
        val extendedHeader = readPrefixWithFallback(
            profile = profile,
            canonicalPath = candidate.headerPath,
            byteCount = GodContainerFormat.EXTENDED_CONTAINER_HEADER_BYTES,
            requestedRoute = requestedRoute,
            preferredRoute = preferred,
        ).first
        val freshMetadata = StfsHeaderReader.inspect(extendedHeader)
        require(freshMetadata.contentType == CONTENT_TYPE_GOD) {
            "O arquivo deixou de ser um container GOD (Content Type ${freshMetadata.contentTypeHex})."
        }

        val data0000 = currentParts.firstOrNull { it.name.equals("Data0000", ignoreCase = true) }
            ?: error("GOD sem Data0000.")
        val data0000Prefix = readPrefixWithFallback(
            profile = profile,
            canonicalPath = data0000.canonicalPath,
            byteCount = GodContainerFormat.XSF_PROBE_BYTES,
            requestedRoute = requestedRoute,
            preferredRoute = preferred,
        ).first
        val hasXsfHeader = GodContainerFormat.hasXsfHeader(data0000Prefix)
        val sectorCorrection = GodContainerFormat.sectorCorrection(extendedHeader, hasXsfHeader)
        val exactIsoBytes = GodContainerFormat.estimatedIsoBytes(currentParts, hasXsfHeader)

        val tempDir = tempDirectory()
        ensureTempSpace(tempDir, exactIsoBytes)
        val tempIso = File(
            tempDir,
            "${freshMetadata.titleId}_${safeTempName(candidate.packageName)}_${System.currentTimeMillis()}.xiso.tmp",
        )

        try {
            reconstructIso(
                profile = profile,
                parts = currentParts,
                output = tempIso,
                hasXsfHeader = hasXsfHeader,
                requestedRoute = requestedRoute,
                preferredRoute = preferred,
                onProgress = onProgress,
            )
            require(tempIso.length() == exactIsoBytes) {
                "Imagem reconstruída ficou com ${tempIso.length()} bytes; esperado $exactIsoBytes."
            }

            onProgress(
                GodRepairProgress(
                    stage = GodRepairStage.InspectingXdvdfs,
                    message = "Abrindo XDVDFS e procurando FFED2000/FFFFFFFF...",
                    completedBytes = exactIsoBytes,
                    totalBytes = exactIsoBytes,
                ),
            )

            val issues = mutableListOf<RepairIssue>()
            val payloads = mutableListOf<GodEmbeddedPayload>()
            XdvdfsImageReader(tempIso, sectorCorrection).use { image ->
                val payloadDirectory = image.findDirectoryPath(INSTALLER_PATH_SEGMENTS)
                    ?: throw IllegalArgumentException(
                        "Esse GOD não contém content/0000000000000000/FFED2000/FFFFFFFF. Ele parece ser um disco/jogo normal, não um instalador desse tipo.",
                    )

                val entries = image.listDirectory(payloadDirectory)
                    .filter { !it.isDirectory }
                    .sortedBy { it.name.lowercase() }
                if (entries.isEmpty()) {
                    throw IllegalArgumentException("FFFFFFFF existe dentro do GOD, mas está vazia.")
                }

                for (entry in entries) {
                    if (entry.size < StfsHeaderReader.REQUIRED_BYTES) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "${entry.name} é pequeno demais para STFS e foi ignorado.",
                            sourcePath = INSTALLER_PATH + "/${entry.name}",
                        )
                        continue
                    }

                    val metadataAttempt = runCatching {
                        StfsHeaderReader.inspect(
                            image.readEntryPrefix(entry, StfsHeaderReader.REQUIRED_BYTES),
                        )
                    }
                    if (metadataAttempt.isFailure) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "${entry.name} ignorado: ${metadataAttempt.exceptionOrNull()?.message ?: "STFS inválido"}",
                            sourcePath = INSTALLER_PATH + "/${entry.name}",
                        )
                        continue
                    }

                    val source = RepairSource(
                        relativePath = INSTALLER_PATH + "/${entry.name}",
                        fileName = entry.name,
                        size = entry.size,
                        kind = RepairSourceKind.GodEmbedded,
                    )
                    val actionAttempt = runCatching {
                        StockDlcInstallerRule.plan(source, metadataAttempt.getOrThrow())
                    }
                    if (actionAttempt.isFailure) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = actionAttempt.exceptionOrNull()?.message
                                ?: "Pacote interno não é DLC compatível.",
                            sourcePath = source.relativePath,
                        )
                        continue
                    }

                    payloads += GodEmbeddedPayload(
                        internalPath = source.relativePath,
                        isoOffset = entry.byteOffset,
                        action = actionAttempt.getOrThrow(),
                    )
                }
            }

            if (payloads.isEmpty()) {
                throw IllegalArgumentException(
                    "O instalador foi encontrado dentro do GOD, mas nenhum pacote DLC STFS válido pôde ser roteado.",
                )
            }

            val exactCandidate = candidate.copy(
                metadata = freshMetadata,
                dataParts = currentParts,
                estimatedIsoBytes = exactIsoBytes,
            )
            GodInstallerPlan(
                candidate = exactCandidate,
                tempIsoPath = tempIso.absolutePath,
                tempIsoBytes = tempIso.length(),
                hasXsfHeader = hasXsfHeader,
                sectorCorrection = sectorCorrection,
                detectedPayloadPath = INSTALLER_PATH,
                payloads = payloads,
                issues = issues,
            )
        } catch (error: Throwable) {
            runCatching { tempIso.delete() }
            throw error
        }
    }

    suspend fun validatePlan(
        plan: GodInstallerPlan,
        requestedRoute: FtpRoute = FtpRoute.Auto,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerValidation = withContext(Dispatchers.IO) {
        val profile = requireProfile()
        val temp = File(plan.tempIsoPath)

        onProgress(
            GodRepairProgress(
                stage = GodRepairStage.ValidatingDestinations,
                message = "Validando ${plan.payloads.size} destino(s) no Xbox...",
            ),
        )

        val routed = sessionFactory.connect(profile, requestedRoute)
        try {
            val checks = plan.payloads.mapIndexed { index, payload ->
                val expected = payload.action.source.size
                val localRegionExists = temp.isFile &&
                    payload.isoOffset >= 0L &&
                    payload.isoOffset + expected <= temp.length()
                val destinationSize = routed.session.size(payload.action.destinationPath)
                val state = when {
                    !localRegionExists -> GodPayloadState.TempSourceMissing
                    destinationSize == expected -> GodPayloadState.AlreadyCorrect
                    destinationSize == null -> GodPayloadState.Missing
                    else -> GodPayloadState.Conflict
                }

                onProgress(
                    GodRepairProgress(
                        stage = GodRepairStage.ValidatingDestinations,
                        message = "Validando ${index + 1}/${plan.payloads.size}: ${payload.action.source.fileName}",
                        completedBytes = (index + 1).toLong(),
                        totalBytes = plan.payloads.size.toLong(),
                    ),
                )
                GodPayloadCheck(
                    payload = payload,
                    state = state,
                    destinationSize = destinationSize,
                )
            }
            GodInstallerValidation(
                requestedRoute = requestedRoute,
                usedRoute = routed.route,
                checks = checks,
                fallbackReason = routed.fallbackReason,
            )
        } finally {
            runCatching { routed.session.close() }
        }
    }

    suspend fun executePlan(
        plan: GodInstallerPlan,
        validation: GodInstallerValidation,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerExecution = withContext(Dispatchers.IO) {
        require(validation.canInstall) {
            "Instalação bloqueada: existe conflito no destino ou a XISO temporária não está disponível."
        }
        val profile = requireProfile()
        val temp = File(plan.tempIsoPath)
        require(temp.isFile && temp.length() == plan.tempIsoBytes) {
            "A XISO temporária mudou ou foi removida. Analise o GOD novamente."
        }

        val totalMissingBytes = validation.checks
            .filter { it.state == GodPayloadState.Missing }
            .sumOf { it.payload.action.source.size }
        var completedPayloadBytes = 0L
        val results = mutableListOf<GodInstallResult>()

        for (check in validation.checks) {
            when (check.state) {
                GodPayloadState.AlreadyCorrect -> {
                    results += GodInstallResult(
                        payload = check.payload,
                        status = GodInstallStatus.AlreadyCorrect,
                        route = validation.usedRoute,
                        message = "Destino já contém o pacote com o tamanho esperado.",
                    )
                }

                GodPayloadState.Missing -> {
                    val result = installEmbeddedPayload(
                        profile = profile,
                        tempIso = temp,
                        payload = check.payload,
                        requestedRoute = validation.requestedRoute,
                        preferredRoute = validation.usedRoute,
                        baseCompletedBytes = completedPayloadBytes,
                        totalBytes = totalMissingBytes,
                        onProgress = onProgress,
                    )
                    results += result
                    if (result.status == GodInstallStatus.Installed) {
                        completedPayloadBytes += check.payload.action.source.size
                    } else {
                        break
                    }
                }

                GodPayloadState.Conflict,
                GodPayloadState.TempSourceMissing,
                -> {
                    results += GodInstallResult(
                        payload = check.payload,
                        status = GodInstallStatus.Failed,
                        route = validation.usedRoute,
                        message = "Estado mudou desde a validação; nenhuma sobrescrita foi feita.",
                    )
                    break
                }
            }
        }

        val succeeded = results.isNotEmpty() && results.none { it.status == GodInstallStatus.Failed }
        val deleted = if (succeeded) {
            onProgress(
                GodRepairProgress(
                    stage = GodRepairStage.CleaningTemp,
                    message = "Pacotes verificados. Limpando a XISO temporária do celular...",
                    completedBytes = totalMissingBytes,
                    totalBytes = totalMissingBytes,
                ),
            )
            runCatching { !temp.exists() || temp.delete() }.getOrDefault(false)
        } else {
            false
        }

        GodInstallerExecution(
            results = results,
            tempIsoDeleted = deleted,
        )
    }

    fun discardTemp(plan: GodInstallerPlan): Boolean {
        val file = File(plan.tempIsoPath)
        return !file.exists() || file.delete()
    }

    private suspend fun installEmbeddedPayload(
        profile: XboxProfile,
        tempIso: File,
        payload: GodEmbeddedPayload,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
        baseCompletedBytes: Long,
        totalBytes: Long,
        onProgress: (GodRepairProgress) -> Unit,
    ): GodInstallResult {
        val destination = payload.action.destinationPath
        val expectedSize = payload.action.source.size
        var lastFailure: Throwable? = null

        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                lastFailure = routedAttempt.exceptionOrNull()
                continue
            }
            val session = routedAttempt.getOrThrow().session
            try {
                val before = session.size(destination)
                if (before == expectedSize) {
                    return GodInstallResult(
                        payload = payload,
                        status = GodInstallStatus.AlreadyCorrect,
                        route = route,
                        message = "O destino ficou correto antes do upload; nada foi sobrescrito.",
                    )
                }
                if (before != null) {
                    return GodInstallResult(
                        payload = payload,
                        status = GodInstallStatus.Failed,
                        route = route,
                        message = "Destino passou a existir com tamanho diferente; upload bloqueado.",
                    )
                }

                onProgress(
                    GodRepairProgress(
                        stage = GodRepairStage.InstallingPayloads,
                        message = "Instalando ${payload.action.source.fileName} via ${route.name.uppercase()}...",
                        completedBytes = baseCompletedBytes,
                        totalBytes = totalBytes,
                    ),
                )

                val uploadAttempt = runCatching {
                    session.upload(
                        canonicalPath = destination,
                        source = FileRegionInputStream(
                            file = tempIso,
                            offset = payload.isoOffset,
                            length = expectedSize,
                        ),
                    ) { sent ->
                        onProgress(
                            GodRepairProgress(
                                stage = GodRepairStage.InstallingPayloads,
                                message = "${payload.action.source.fileName} • ${route.name.uppercase()}",
                                completedBytes = baseCompletedBytes + sent,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }

                val after = runCatching { session.size(destination) }.getOrNull()
                if (after == expectedSize) {
                    onProgress(
                        GodRepairProgress(
                            stage = GodRepairStage.Verifying,
                            message = "${payload.action.source.fileName} verificado por SIZE.",
                            completedBytes = baseCompletedBytes + expectedSize,
                            totalBytes = totalBytes,
                        ),
                    )
                    return GodInstallResult(
                        payload = payload,
                        status = GodInstallStatus.Installed,
                        route = route,
                        message = "Extraído do GOD, enviado ao destino correto e verificado por SIZE.",
                    )
                }

                lastFailure = uploadAttempt.exceptionOrNull()
                    ?: IllegalStateException("SIZE pós-upload foi ${after ?: "ausente"}; esperado $expectedSize.")

                if (after != null) {
                    val cleaned = runCatching {
                        session.delete(destination)
                        session.size(destination) == null
                    }.getOrDefault(false)
                    if (!cleaned) {
                        return GodInstallResult(
                            payload = payload,
                            status = GodInstallStatus.Failed,
                            route = route,
                            message = "Upload incompleto deixou um destino ambíguo e não foi possível removê-lo. EchoFix interrompeu antes de tentar outra rota.",
                        )
                    }
                }
            } finally {
                runCatching { session.close() }
            }
        }

        return GodInstallResult(
            payload = payload,
            status = GodInstallStatus.Failed,
            route = null,
            message = "Aurora e FTPdll não conseguiram instalar o pacote: ${lastFailure?.message ?: "sem detalhe"}",
        )
    }

    private suspend fun reconstructIso(
        profile: XboxProfile,
        parts: List<GodDataPart>,
        output: File,
        hasXsfHeader: Boolean,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
        onProgress: (GodRepairProgress) -> Unit,
    ) {
        output.parentFile?.mkdirs()
        FileOutputStream(output, false).use { stream ->
            if (!hasXsfHeader) {
                stream.write(ByteArray(GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES))
            }
        }

        val totalRawBytes = parts.sumOf(GodDataPart::rawSize)
        var completedRawBytes = 0L

        for ((index, part) in parts.withIndex()) {
            val partStart = output.length()
            var lastFailure: Throwable? = null
            var completed = false

            for (route in routeOrder(requestedRoute, preferredRoute)) {
                RandomAccessFile(output, "rw").use { it.setLength(partStart) }
                val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
                if (routedAttempt.isFailure) {
                    lastFailure = routedAttempt.exceptionOrNull()
                    continue
                }
                val session = routedAttempt.getOrThrow().session
                try {
                    val partAttempt = runCatching {
                        FileOutputStream(output, true).use { fileOutput ->
                            val payloadOutput = GodDataPartPayloadOutputStream(fileOutput)
                            session.download(part.canonicalPath, payloadOutput) { received ->
                                onProgress(
                                    GodRepairProgress(
                                        stage = GodRepairStage.ReconstructingIso,
                                        message = "GOD → XISO ${index + 1}/${parts.size} • ${part.name} • ${route.name.uppercase()}",
                                        completedBytes = completedRawBytes + received,
                                        totalBytes = totalRawBytes,
                                    ),
                                )
                            }
                            payloadOutput.flush()
                            require(payloadOutput.payloadBytesWritten == part.payloadSize) {
                                "${part.name}: payload ${payloadOutput.payloadBytesWritten}, esperado ${part.payloadSize}."
                            }
                        }
                    }
                    if (partAttempt.isSuccess) {
                        completed = true
                        break
                    }
                    lastFailure = partAttempt.exceptionOrNull()
                } finally {
                    runCatching { session.close() }
                }
            }

            if (!completed) {
                RandomAccessFile(output, "rw").use { it.setLength(partStart) }
                throw IllegalStateException(
                    "Não foi possível reconstruir ${part.name} por Aurora nem FTPdll.",
                    lastFailure,
                )
            }
            completedRawBytes += part.rawSize
        }
    }

    private suspend fun refreshAndValidateParts(
        profile: XboxProfile,
        candidate: GodPackageCandidate,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
    ): List<GodDataPart> {
        var lastFailure: Throwable? = null
        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                lastFailure = routedAttempt.exceptionOrNull()
                continue
            }
            val session = routedAttempt.getOrThrow().session
            try {
                val currentHeaderSize = session.size(candidate.headerPath)
                require(currentHeaderSize == candidate.headerSize) {
                    "O header GOD mudou desde o scan. Escaneie novamente."
                }
                val freshParts = toGodParts(session.list(candidate.dataDirectoryPath))
                require(freshParts.isNotEmpty()) { "A pasta .data ficou sem DataNNNN." }
                val expected = candidate.dataParts.associate { it.name.lowercase() to it.rawSize }
                val actual = freshParts.associate { it.name.lowercase() to it.rawSize }
                require(expected == actual) {
                    "Os DataNNNN mudaram desde o scan. Escaneie novamente antes de analisar."
                }
                return freshParts
            } catch (error: Throwable) {
                lastFailure = error
            } finally {
                runCatching { session.close() }
            }
        }
        throw IllegalStateException("Não foi possível revalidar o GOD no Xbox.", lastFailure)
    }

    private suspend fun discoverGodDirectories(
        session: XboxFtpSession,
        root: String,
    ): List<String> {
        val last = root.substringAfterLast('/')
        if (last.equals("00007000", ignoreCase = true)) {
            session.list(root)
            return listOf(root)
        }

        val rootListing = session.list(root)
        if (TITLE_ID_REGEX.matches(last)) {
            val direct = rootListing.firstOrNull {
                it.isDirectory && it.name.equals("00007000", ignoreCase = true)
            }
            return direct?.let { listOf(it.canonicalPath) }.orEmpty()
        }

        val result = mutableListOf<String>()
        for (entry in rootListing.filter(RemoteEntry::isDirectory)) {
            if (!TITLE_ID_REGEX.matches(entry.name)) continue
            val contentPath = joinCanonical(entry.canonicalPath, "00007000")
            if (runCatching { session.list(contentPath) }.isSuccess) {
                result += contentPath
            }
        }
        return result
    }

    private fun toGodParts(entries: List<RemoteEntry>): List<GodDataPart> =
        entries.asSequence()
            .filter { !it.isDirectory && DATA_PART_REGEX.matches(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { entry ->
                val raw = entry.size.coerceAtLeast(0L)
                GodDataPart(
                    name = entry.name,
                    canonicalPath = entry.canonicalPath,
                    rawSize = raw,
                    payloadSize = GodContainerFormat.payloadBytes(raw),
                )
            }
            .toList()

    private suspend fun readPrefixWithFallback(
        profile: XboxProfile,
        canonicalPath: String,
        byteCount: Int,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
    ): Pair<ByteArray, FtpRoute> {
        var lastFailure: Throwable? = null
        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                lastFailure = routedAttempt.exceptionOrNull()
                continue
            }
            val session = routedAttempt.getOrThrow().session
            try {
                return session.readPrefixAndClose(canonicalPath, byteCount) to route
            } catch (error: Throwable) {
                lastFailure = error
            } finally {
                runCatching { session.close() }
            }
        }
        throw IllegalStateException(
            "Nenhum FTP conseguiu ler $byteCount bytes de $canonicalPath.",
            lastFailure,
        )
    }

    private fun ensureTempSpace(directory: File, requiredBytes: Long) {
        require(requiredBytes > 0L) { "Tamanho reconstruído do GOD inválido." }
        val available = StatFs(directory.absolutePath).availableBytes
        val requiredWithReserve = requiredBytes + TEMP_FREE_RESERVE_BYTES
        require(available >= requiredWithReserve) {
            "Espaço insuficiente no celular para analisar esse GOD. Livre: ${formatBytes(available)}; necessário: aproximadamente ${formatBytes(requiredWithReserve)}. O arquivo temporário é apagado após um reparo concluído."
        }
    }

    private fun tempDirectory(): File =
        File(appContext.externalCacheDir ?: appContext.cacheDir, TEMP_DIRECTORY_NAME).apply {
            require(mkdirs() || isDirectory) { "Não foi possível criar o cache temporário do EchoFix." }
        }

    private fun cleanupStaleTemps() {
        val directory = tempDirectory()
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".xiso.tmp") && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
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

    private fun requireProfile(): XboxProfile = configStore.load()
        ?: error("Configure o Xbox na aba Xbox antes de usar o EchoFix.")

    private fun joinCanonical(base: String, suffix: String): String =
        XboxPath.canonical(base.trimEnd('/') + "/" + suffix.trimStart('/'))

    private fun safeTempName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "god" }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private class FileRegionInputStream(
        file: File,
        offset: Long,
        length: Long,
    ) : InputStream() {
        private val input = RandomAccessFile(file, "r")
        private var remaining = length

        init {
            require(offset >= 0L && length >= 0L)
            require(offset + length <= input.length()) {
                "Região fora da XISO temporária: offset=$offset, length=$length, file=${input.length()}."
            }
            input.seek(offset)
        }

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = input.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val wanted = min(length.toLong(), remaining).toInt()
            val read = input.read(buffer, offset, wanted)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            input.close()
        }
    }

    companion object {
        const val DEFAULT_GOD_ROOT: String = "/Hdd1/Content/0000000000000000"
        private const val CONTENT_TYPE_GOD = 0x00007000L
        private const val TEMP_DIRECTORY_NAME = "echofix-god"
        private const val TEMP_FREE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val TITLE_ID_REGEX = Regex("^[0-9A-Fa-f]{8}$")
        private val DATA_PART_REGEX = Regex("^Data\\d{4}$", RegexOption.IGNORE_CASE)
        private val INSTALLER_PATH_SEGMENTS = listOf(
            "content",
            "0000000000000000",
            "FFED2000",
            "FFFFFFFF",
        )
        private const val INSTALLER_PATH = "content/0000000000000000/FFED2000/FFFFFFFF"
    }
}
