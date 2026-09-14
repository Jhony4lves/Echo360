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
import com.jhony4lves.echo360.domain.fix.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.min

class GodInstallerRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val verdictStore = GodInstallerVerdictStore(appContext)

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
        var hiddenKnownNonInstallers = 0

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
                val listingAttempt = runCatching { routed.session.list(godDirectory) }
                if (listingAttempt.isFailure) {
                    val error = listingAttempt.exceptionOrNull()
                    issues += RepairIssue(
                        severity = RepairSeverity.Warning,
                        message = "Não foi possível listar $godDirectory: ${error?.message ?: "erro FTP"}",
                        sourcePath = godDirectory,
                    )
                    continue
                }
                val listing = listingAttempt.getOrThrow()
                val dataDirectories = listing
                    .filter(RemoteEntry::isDirectory)
                    .associateBy { it.name.lowercase() }
                val packageFiles = listing
                    .filter { !it.isDirectory }
                    .sortedBy { it.name.lowercase() }

                for (packageFile in packageFiles) {
                    val dataDirectory = dataDirectories["${packageFile.name}.data".lowercase()] ?: continue
                    val partListingAttempt = runCatching {
                        routed.session.list(dataDirectory.canonicalPath)
                    }
                    if (partListingAttempt.isFailure) {
                        val error = partListingAttempt.exceptionOrNull()
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "GOD encontrado, mas não consegui listar ${dataDirectory.canonicalPath}: ${error?.message ?: "erro FTP"}",
                            sourcePath = packageFile.canonicalPath,
                        )
                        continue
                    }
                    val parts = toGodParts(partListingAttempt.getOrThrow())
                    if (parts.isEmpty()) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "${packageFile.name} possui pasta .data, mas nenhum DataNNNN válido.",
                            sourcePath = dataDirectory.canonicalPath,
                        )
                        continue
                    }

                    val prefixAttempt = runCatching {
                        readPrefixWithFallback(
                            profile = profile,
                            canonicalPath = packageFile.canonicalPath,
                            byteCount = StfsHeaderReader.REQUIRED_BYTES,
                            requestedRoute = requestedRoute,
                            preferredRoute = preferredRoute,
                        ).first
                    }
                    if (prefixAttempt.isFailure) {
                        val error = prefixAttempt.exceptionOrNull()
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "Não consegui ler o header GOD ${packageFile.name}: ${error?.message ?: "erro FTP"}",
                            sourcePath = packageFile.canonicalPath,
                        )
                        continue
                    }

                    val metadataAttempt = runCatching {
                        StfsHeaderReader.inspect(prefixAttempt.getOrThrow())
                    }
                    if (metadataAttempt.isFailure) {
                        val error = metadataAttempt.exceptionOrNull()
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "Container ignorado: ${error?.message ?: "STFS inválido"}",
                            sourcePath = packageFile.canonicalPath,
                        )
                        continue
                    }
                    val metadata = metadataAttempt.getOrThrow()
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

                    val candidate = GodPackageCandidate(
                        titleIdDirectory = titleDirectory,
                        packageName = packageFile.name,
                        headerPath = packageFile.canonicalPath,
                        dataDirectoryPath = dataDirectory.canonicalPath,
                        headerSize = packageFile.size.coerceAtLeast(0L),
                        metadata = metadata,
                        dataParts = parts,
                        estimatedIsoBytes = parts.sumOf(GodDataPart::payloadSize) +
                            GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES,
                    )

                    processed += 1
                    if (verdictStore.isKnownNonInstaller(candidate)) {
                        hiddenKnownNonInstallers += 1
                    } else {
                        candidates += candidate
                    }

                    onProgress(
                        GodRepairProgress(
                            stage = GodRepairStage.ReadingContainer,
                            message = buildString {
                                append(candidates.size)
                                append(" GOD(s) candidato(s)")
                                if (hiddenKnownNonInstallers > 0) {
                                    append(" • ")
                                    append(hiddenKnownNonInstallers)
                                    append(" já descartado(s)")
                                }
                                append("...")
                            },
                            completedBytes = processed,
                            totalBytes = maxOf(processed, godDirectories.size.toLong()),
                        ),
                    )
                }
            }
        } finally {
            runCatching { routed.session.close() }
        }

        if (hiddenKnownNonInstallers > 0) {
            issues += RepairIssue(
                severity = RepairSeverity.Info,
                message = "$hiddenKnownNonInstallers GOD(s) já analisado(s) e confirmado(s) como não instalador foram ocultados. Se os arquivos mudarem, eles reaparecem automaticamente.",
                sourcePath = root,
            )
        }
        if (candidates.isEmpty()) {
            issues += RepairIssue(
                severity = RepairSeverity.Info,
                message = if (hiddenKnownNonInstallers > 0) {
                    "Nenhum novo GOD candidato foi encontrado em $root."
                } else {
                    "Nenhum pacote GOD com arquivo .data foi encontrado em $root."
                },
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
    ): GodInstallerPlan = ResumableGodAnalysisRepository(appContext, sessionFactory)
        .analyze(candidate, requestedRoute, onProgress)

    suspend fun validatePlan(
        plan: GodInstallerPlan,
        requestedRoute: FtpRoute = FtpRoute.Auto,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerValidation = withContext(Dispatchers.IO) {
        val profile = requireProfile()
        val direct = plan.tempIsoPath == DIRECT_GOD_SOURCE_MARKER
        val temp = if (direct) null else File(plan.tempIsoPath)

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
                val sourceReady = if (direct) {
                    plan.candidate.dataParts.isNotEmpty() && expected > 0L
                } else {
                    temp?.isFile == true &&
                        payload.isoOffset >= 0L &&
                        payload.isoOffset + expected <= temp.length()
                }
                val destinationSize = routed.session.size(payload.action.destinationPath)
                val state = when {
                    !sourceReady -> GodPayloadState.TempSourceMissing
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
            "Instalação bloqueada: existe conflito no destino ou a fonte não está disponível."
        }
        val profile = requireProfile()
        val direct = plan.tempIsoPath == DIRECT_GOD_SOURCE_MARKER
        val tempIso = if (direct) null else File(plan.tempIsoPath)

        if (!direct) {
            require(tempIso?.isFile == true && tempIso.length() == plan.tempIsoBytes) {
                "A XISO temporária mudou ou foi removida. Analise o GOD novamente."
            }
        }

        val freshParts = if (direct) {
            refreshAndValidateParts(
                profile = profile,
                candidate = plan.candidate,
                requestedRoute = validation.requestedRoute,
                preferredRoute = validation.usedRoute,
            )
        } else {
            emptyList()
        }

        val totalMissingBytes = validation.checks
            .filter { it.state == GodPayloadState.Missing }
            .sumOf { it.payload.action.source.size }
        val totalWorkBytes = if (direct) totalMissingBytes * 2L else totalMissingBytes
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
                    val result = if (direct) {
                        installDirectEmbeddedPayload(
                            profile = profile,
                            plan = plan,
                            parts = freshParts,
                            payload = check.payload,
                            requestedRoute = validation.requestedRoute,
                            preferredRoute = validation.usedRoute,
                            baseWorkBytes = completedPayloadBytes * 2L,
                            totalWorkBytes = totalWorkBytes,
                            onProgress = onProgress,
                        )
                    } else {
                        installEmbeddedPayload(
                            profile = profile,
                            tempIso = requireNotNull(tempIso),
                            payload = check.payload,
                            requestedRoute = validation.requestedRoute,
                            preferredRoute = validation.usedRoute,
                            baseCompletedBytes = completedPayloadBytes,
                            totalBytes = totalMissingBytes,
                            onProgress = onProgress,
                        )
                    }
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
                    message = if (direct) {
                        "Pacotes verificados. Limpando apenas o cache de extração direta..."
                    } else {
                        "Pacotes verificados. Limpando a XISO temporária do celular..."
                    },
                    completedBytes = totalWorkBytes,
                    totalBytes = totalWorkBytes,
                ),
            )
            if (direct) {
                cleanupDirectPayloadTemps(plan)
            } else {
                runCatching { tempIso == null || !tempIso.exists() || tempIso.delete() }.getOrDefault(false)
            }
        } else {
            false
        }

        GodInstallerExecution(
            results = results,
            tempIsoDeleted = deleted,
        )
    }

    fun discardTemp(plan: GodInstallerPlan): Boolean = if (plan.tempIsoPath == DIRECT_GOD_SOURCE_MARKER) {
        cleanupDirectPayloadTemps(plan)
    } else {
        val file = File(plan.tempIsoPath)
        !file.exists() || file.delete()
    }

    private suspend fun installDirectEmbeddedPayload(
        profile: XboxProfile,
        plan: GodInstallerPlan,
        parts: List<GodDataPart>,
        payload: GodEmbeddedPayload,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
        baseWorkBytes: Long,
        totalWorkBytes: Long,
        onProgress: (GodRepairProgress) -> Unit,
    ): GodInstallResult {
        val expectedSize = payload.action.source.size
        val staged = directPayloadTempFile(plan, payload)
        ensurePayloadTempSpace(staged.parentFile!!, expectedSize, staged.length())

        if (!staged.isFile || staged.length() != expectedSize) {
            runCatching { staged.delete() }
            extractDirectPayload(
                profile = profile,
                plan = plan,
                parts = parts,
                payload = payload,
                destination = staged,
                requestedRoute = requestedRoute,
                preferredRoute = preferredRoute,
                baseWorkBytes = baseWorkBytes,
                totalWorkBytes = totalWorkBytes,
                onProgress = onProgress,
            )
        }

        require(staged.isFile && staged.length() == expectedSize) {
            "Extração direta ficou com ${staged.length()} bytes; esperado $expectedSize."
        }

        val result = uploadStagedPayload(
            profile = profile,
            staged = staged,
            payload = payload,
            requestedRoute = requestedRoute,
            preferredRoute = preferredRoute,
            baseWorkBytes = baseWorkBytes + expectedSize,
            totalWorkBytes = totalWorkBytes,
            onProgress = onProgress,
        )
        if (result.status == GodInstallStatus.Installed || result.status == GodInstallStatus.AlreadyCorrect) {
            runCatching { staged.delete() }
        }
        return result
    }

    private suspend fun extractDirectPayload(
        profile: XboxProfile,
        plan: GodInstallerPlan,
        parts: List<GodDataPart>,
        payload: GodEmbeddedPayload,
        destination: File,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
        baseWorkBytes: Long,
        totalWorkBytes: Long,
        onProgress: (GodRepairProgress) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination, false).use { }
        val slices = GodPayloadSlicePlanner.plan(
            parts = parts,
            hasXsfHeader = plan.hasXsfHeader,
            isoOffset = payload.isoOffset,
            length = payload.action.source.size,
        )
        var extractedBeforeSlice = 0L

        for (slice in slices) {
            val sliceStart = destination.length()
            var lastFailure: Throwable? = null
            var completed = false

            for (route in routeOrder(requestedRoute, preferredRoute)) {
                RandomAccessFile(destination, "rw").use { it.setLength(sliceStart) }
                val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
                if (routedAttempt.isFailure) {
                    val error = routedAttempt.exceptionOrNull()
                    if (error is CancellationException) throw error
                    lastFailure = error
                    continue
                }
                val session = routedAttempt.getOrThrow().session
                try {
                    val rawOffset = GodContainerFormat.rawOffsetForPayloadOffset(slice.payloadOffsetInPart)
                    val attempt = runCatching {
                        FileOutputStream(destination, true).use { fileOutput ->
                            val bounded = SliceBoundedOutputStream(
                                delegate = fileOutput,
                                limit = slice.payloadBytes,
                            )
                            val decoder = GodDataPartPayloadOutputStream(
                                delegate = bounded,
                                initialRawPosition = rawOffset,
                            )
                            try {
                                session.downloadFromOffset(
                                    canonicalPath = slice.part.canonicalPath,
                                    offset = rawOffset,
                                    destination = decoder,
                                ) {
                                    onProgress(
                                        GodRepairProgress(
                                            stage = GodRepairStage.InstallingPayloads,
                                            message = "Extraindo ${payload.action.source.fileName} do GOD • ${route.name.uppercase()} • Data${slice.partIndex.toString().padStart(4, '0')}",
                                            completedBytes = baseWorkBytes + extractedBeforeSlice + bounded.written,
                                            totalBytes = totalWorkBytes,
                                        ),
                                    )
                                }
                            } catch (complete: PayloadSliceCompleteException) {
                                if (bounded.written != slice.payloadBytes) throw complete
                            }
                            fileOutput.fd.sync()
                            require(bounded.written == slice.payloadBytes) {
                                "${slice.part.name}: extraídos ${bounded.written} bytes; esperado ${slice.payloadBytes}."
                            }
                        }
                    }
                    if (attempt.isSuccess) {
                        completed = true
                        break
                    }
                    val error = attempt.exceptionOrNull()
                    if (error is CancellationException) throw error
                    lastFailure = error
                } finally {
                    runCatching { session.close() }
                }
            }

            if (!completed) {
                RandomAccessFile(destination, "rw").use { it.setLength(sliceStart) }
                throw IllegalStateException(
                    "Não foi possível extrair ${slice.part.name} por nenhuma rota FTP.",
                    lastFailure,
                )
            }
            extractedBeforeSlice += slice.payloadBytes
        }

        require(destination.length() == payload.action.source.size) {
            "Payload direto ficou com ${destination.length()} bytes; esperado ${payload.action.source.size}."
        }
        onProgress(
            GodRepairProgress(
                stage = GodRepairStage.InstallingPayloads,
                message = "${payload.action.source.fileName} extraído do GOD sem reconstruir a XISO completa.",
                completedBytes = baseWorkBytes + payload.action.source.size,
                totalBytes = totalWorkBytes,
            ),
        )
    }

    private suspend fun uploadStagedPayload(
        profile: XboxProfile,
        staged: File,
        payload: GodEmbeddedPayload,
        requestedRoute: FtpRoute,
        preferredRoute: FtpRoute,
        baseWorkBytes: Long,
        totalWorkBytes: Long,
        onProgress: (GodRepairProgress) -> Unit,
    ): GodInstallResult {
        val destination = payload.action.destinationPath
        val expectedSize = payload.action.source.size
        var lastFailure: Throwable? = null

        for (route in routeOrder(requestedRoute, preferredRoute)) {
            val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
            if (routedAttempt.isFailure) {
                val error = routedAttempt.exceptionOrNull()
                if (error is CancellationException) throw error
                lastFailure = error
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

                val uploadAttempt = runCatching {
                    session.upload(
                        canonicalPath = destination,
                        source = FileInputStream(staged),
                    ) { sent ->
                        onProgress(
                            GodRepairProgress(
                                stage = GodRepairStage.InstallingPayloads,
                                message = "Enviando ${payload.action.source.fileName} • ${route.name.uppercase()}",
                                completedBytes = baseWorkBytes + sent,
                                totalBytes = totalWorkBytes,
                            ),
                        )
                    }
                }
                val uploadError = uploadAttempt.exceptionOrNull()
                if (uploadError is CancellationException) throw uploadError

                val after = runCatching { session.size(destination) }.getOrNull()
                if (after == expectedSize) {
                    onProgress(
                        GodRepairProgress(
                            stage = GodRepairStage.Verifying,
                            message = "${payload.action.source.fileName} verificado por SIZE.",
                            completedBytes = baseWorkBytes + expectedSize,
                            totalBytes = totalWorkBytes,
                        ),
                    )
                    return GodInstallResult(
                        payload = payload,
                        status = GodInstallStatus.Installed,
                        route = route,
                        message = "Extraído diretamente do GOD, enviado ao destino correto e verificado por SIZE.",
                    )
                }

                lastFailure = uploadError
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
                val error = routedAttempt.exceptionOrNull()
                if (error is CancellationException) throw error
                lastFailure = error
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
                val uploadError = uploadAttempt.exceptionOrNull()
                if (uploadError is CancellationException) throw uploadError
                val after = runCatching { session.size(destination) }.getOrNull()
                if (after == expectedSize) {
                    return GodInstallResult(
                        payload = payload,
                        status = GodInstallStatus.Installed,
                        route = route,
                        message = "Extraído do GOD, enviado ao destino correto e verificado por SIZE.",
                    )
                }
                lastFailure = uploadError
                    ?: IllegalStateException("SIZE pós-upload foi ${after ?: "ausente"}; esperado $expectedSize.")
                if (after != null) {
                    val cleaned = runCatching {
                        session.delete(destination)
                        session.size(destination) == null
                    }.getOrDefault(false)
                    if (!cleaned) break
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
                val error = routedAttempt.exceptionOrNull()
                if (error is CancellationException) throw error
                lastFailure = error
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
                    "Os DataNNNN mudaram desde o scan. Escaneie novamente antes de instalar."
                }
                return freshParts
            } catch (cancelled: CancellationException) {
                throw cancelled
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
                val error = routedAttempt.exceptionOrNull()
                if (error is CancellationException) throw error
                lastFailure = error
                continue
            }
            val session = routedAttempt.getOrThrow().session
            try {
                return session.readPrefixAndClose(canonicalPath, byteCount) to route
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    private fun ensurePayloadTempSpace(directory: File, requiredBytes: Long, alreadyPresent: Long) {
        val additional = (requiredBytes - alreadyPresent).coerceAtLeast(0L)
        if (additional == 0L) return
        val available = StatFs(directory.absolutePath).availableBytes
        val requiredWithReserve = additional + TEMP_FREE_RESERVE_BYTES
        require(available >= requiredWithReserve) {
            "Espaço insuficiente no celular para extrair o pacote necessário. Livre: ${formatBytes(available)}; necessário: aproximadamente ${formatBytes(requiredWithReserve)}. A XISO completa não será criada."
        }
    }

    private fun directPayloadTempFile(plan: GodInstallerPlan, payload: GodEmbeddedPayload): File {
        val key = Integer.toHexString(
            (plan.candidate.headerPath.lowercase() + "|" + payload.action.source.fileName.lowercase()).hashCode(),
        )
        return File(
            tempDirectory(),
            "${plan.candidate.metadata.titleId}_${safeTempName(payload.action.source.fileName)}_${payload.action.source.size}_$key.payload.tmp",
        )
    }

    private fun cleanupDirectPayloadTemps(plan: GodInstallerPlan): Boolean {
        var ok = true
        for (payload in plan.payloads) {
            val file = directPayloadTempFile(plan, payload)
            if (file.exists() && !file.delete()) ok = false
        }
        return ok
    }

    private fun tempDirectory(): File =
        File(appContext.externalCacheDir ?: appContext.cacheDir, TEMP_DIRECTORY_NAME).apply {
            require(mkdirs() || isDirectory) { "Não foi possível criar o cache temporário do EchoFix." }
        }

    private fun cleanupStaleTemps() {
        val directory = tempDirectory()
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() >= cutoff) return@forEach
            if (file.name.endsWith(".xiso.tmp") || file.name.endsWith(".payload.tmp")) {
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

    private class SliceBoundedOutputStream(
        private val delegate: OutputStream,
        private val limit: Long,
    ) : OutputStream() {
        var written: Long = 0L
            private set

        override fun write(value: Int) {
            val one = byteArrayOf(value.toByte())
            write(one, 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (written >= limit) throw PayloadSliceCompleteException()
            val count = min(length.toLong(), limit - written).toInt()
            if (count > 0) {
                delegate.write(buffer, offset, count)
                written += count
            }
            if (count < length || written >= limit) throw PayloadSliceCompleteException()
        }

        override fun flush() = delegate.flush()
    }

    private class PayloadSliceCompleteException : IOException("requested GOD payload slice complete")

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

        override fun close() = input.close()
    }

    companion object {
        const val DEFAULT_GOD_ROOT: String = "/Hdd1/Content/0000000000000000"
        private const val CONTENT_TYPE_GOD = 0x00007000L
        private const val TEMP_DIRECTORY_NAME = "echofix-god"
        private const val TEMP_FREE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val TITLE_ID_REGEX = Regex("^[0-9A-Fa-f]{8}$")
        private val DATA_PART_REGEX = Regex("^Data\\d{4}$", RegexOption.IGNORE_CASE)
    }
}
