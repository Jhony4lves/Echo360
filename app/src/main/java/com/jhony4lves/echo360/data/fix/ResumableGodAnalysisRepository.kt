package com.jhony4lves.echo360.data.fix

import android.content.Context
import android.os.StatFs
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.fix.GodDataPart
import com.jhony4lves.echo360.domain.fix.GodEmbeddedPayload
import com.jhony4lves.echo360.domain.fix.GodInstallerPlan
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.domain.fix.GodRepairStage
import com.jhony4lves.echo360.domain.fix.RepairIssue
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.domain.fix.RepairSource
import com.jhony4lves.echo360.domain.fix.RepairSourceKind
import com.jhony4lves.echo360.domain.fix.StockDlcInstallerRule
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Long-running GOD analysis path used by the foreground service.
 *
 * It intentionally shares the same output models as [GodInstallerRepository]
 * so validation/install remain unchanged, but reconstruction is delegated to
 * [GodResumableIsoBuilder]. The temporary image has a deterministic path, which
 * lets a new process discover and continue a previous checkpoint.
 */
class ResumableGodAnalysisRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val builder = GodResumableIsoBuilder(sessionFactory)

    suspend fun analyze(
        candidate: GodPackageCandidate,
        requestedRoute: FtpRoute = FtpRoute.Auto,
        onProgress: (GodRepairProgress) -> Unit = {},
    ): GodInstallerPlan = withContext(Dispatchers.IO) {
        cleanupStaleTemps()
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
                message = "Revalidando GOD ${candidate.packageName} antes de continuar...",
            ),
        )

        val currentParts = refreshAndValidateParts(
            profile = profile,
            candidate = candidate,
            requestedRoute = requestedRoute,
            preferredRoute = preferred,
        )
        val extendedHeader = readPrefixWithFallback(
            profile = profile,
            canonicalPath = candidate.headerPath,
            byteCount = GodContainerFormat.EXTENDED_CONTAINER_HEADER_BYTES,
            requestedRoute = requestedRoute,
            preferredRoute = preferred,
        )
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
        )
        val hasXsfHeader = GodContainerFormat.hasXsfHeader(data0000Prefix)
        val sectorCorrection = GodContainerFormat.sectorCorrection(extendedHeader, hasXsfHeader)
        val exactIsoBytes = GodContainerFormat.estimatedIsoBytes(currentParts, hasXsfHeader)
        val exactCandidate = candidate.copy(
            metadata = freshMetadata,
            dataParts = currentParts,
            estimatedIsoBytes = exactIsoBytes,
        )
        val tempIso = tempIsoFile(exactCandidate)

        try {
            builder.reconstruct(
                profile = profile,
                parts = currentParts,
                output = tempIso,
                hasXsfHeader = hasXsfHeader,
                expectedIsoBytes = exactIsoBytes,
                routeOrder = routeOrder(requestedRoute, preferred),
                ensureAdditionalSpace = { additional -> ensureAdditionalTempSpace(tempIso.parentFile!!, additional) },
                onProgress = onProgress,
            )

            onProgress(
                GodRepairProgress(
                    stage = GodRepairStage.InspectingXdvdfs,
                    message = "XISO pronta. Procurando FFED2000/FFFFFFFF...",
                    completedBytes = exactIsoBytes,
                    totalBytes = exactIsoBytes,
                ),
            )

            val issues = mutableListOf<RepairIssue>()
            val payloads = mutableListOf<GodEmbeddedPayload>()
            XdvdfsImageReader(tempIso, sectorCorrection).use { image ->
                val payloadDirectory = image.findDirectoryPath(INSTALLER_PATH_SEGMENTS)
                    ?: throw NotInstallerGodException(
                        "Esse GOD não contém content/0000000000000000/FFED2000/FFFFFFFF. Ele parece ser um disco/jogo normal, não um instalador desse tipo.",
                    )

                val entries = image.listDirectory(payloadDirectory)
                    .filter { !it.isDirectory }
                    .sortedBy { it.name.lowercase() }
                if (entries.isEmpty()) {
                    throw NotInstallerGodException("FFFFFFFF existe dentro do GOD, mas está vazia.")
                }

                for (entry in entries) {
                    if (entry.size < StfsHeaderReader.REQUIRED_BYTES) {
                        issues += RepairIssue(
                            severity = RepairSeverity.Warning,
                            message = "${entry.name} é pequeno demais para STFS e foi ignorado.",
                            sourcePath = "$INSTALLER_PATH/${entry.name}",
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
                            sourcePath = "$INSTALLER_PATH/${entry.name}",
                        )
                        continue
                    }

                    val source = RepairSource(
                        relativePath = "$INSTALLER_PATH/${entry.name}",
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
                throw NotInstallerGodException(
                    "O instalador foi encontrado dentro do GOD, mas nenhum pacote DLC STFS válido pôde ser roteado.",
                )
            }

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
        } catch (cancelled: CancellationException) {
            // Preserve XISO + checkpoint. A later foreground-service run resumes.
            throw cancelled
        } catch (notInstaller: NotInstallerGodException) {
            // Full image was valid but this recipe does not apply. Keeping many
            // GB of cache offers no value, so clean it deterministically.
            builder.discard(tempIso)
            throw notInstaller
        } catch (error: Throwable) {
            // Network/process/storage failures are resumable. Keep the durable
            // checkpoint instead of deleting hours of completed work.
            throw error
        }
    }

    fun discard(candidate: GodPackageCandidate): Boolean = builder.discard(tempIsoFile(candidate))

    fun discard(plan: GodInstallerPlan): Boolean = builder.discard(File(plan.tempIsoPath))

    fun tempIsoFile(candidate: GodPackageCandidate): File {
        val key = Integer.toHexString(candidate.headerPath.lowercase().hashCode())
        return File(
            tempDirectory(),
            "${candidate.metadata.titleId}_${safeTempName(candidate.packageName)}_$key.xiso.partial",
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
                return session.readPrefixAndClose(canonicalPath, byteCount)
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

    private fun ensureAdditionalTempSpace(directory: File, additionalBytes: Long) {
        if (additionalBytes <= 0L) return
        val available = StatFs(directory.absolutePath).availableBytes
        val requiredWithReserve = additionalBytes + TEMP_FREE_RESERVE_BYTES
        require(available >= requiredWithReserve) {
            "Espaço insuficiente para continuar o GOD. Livre: ${formatBytes(available)}; ainda necessário: aproximadamente ${formatBytes(requiredWithReserve)}. O progresso já concluído foi preservado."
        }
    }

    private fun cleanupStaleTemps() {
        val cutoff = System.currentTimeMillis() - RESUME_MAX_AGE_MS
        tempDirectory().listFiles()?.forEach { file ->
            if (file.lastModified() >= cutoff) return@forEach
            if (file.name.endsWith(".xiso.partial") || file.name.endsWith(".xiso.partial.resume") || file.name.endsWith(".xiso.partial.resume.tmp")) {
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

    private fun tempDirectory(): File =
        File(appContext.externalCacheDir ?: appContext.cacheDir, TEMP_DIRECTORY_NAME).apply {
            require(mkdirs() || isDirectory) { "Não foi possível criar o cache retomável do EchoFix." }
        }

    private fun safeTempName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "god" }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private class NotInstallerGodException(message: String) : IllegalArgumentException(message)

    companion object {
        private const val CONTENT_TYPE_GOD = 0x00007000L
        private const val TEMP_DIRECTORY_NAME = "echofix-god-resume"
        private const val TEMP_FREE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val RESUME_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
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
