package com.jhony4lves.echo360.data.transfer

import android.content.Context
import android.net.Uri
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.RemoteTransferFile
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.domain.transfer.TransferCompareEngine
import com.jhony4lves.echo360.domain.transfer.TransferDiffKind
import com.jhony4lves.echo360.domain.transfer.TransferDiffItem
import com.jhony4lves.echo360.domain.transfer.TransferExecutionProgress
import com.jhony4lves.echo360.domain.transfer.TransferExecutionResult
import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRetryPolicy
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

class EchoTransferRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val remoteTreeScanner: RemoteTreeScanner = RemoteTreeScanner(),
    private val retryPolicy: FtpRetryPolicy = FtpRetryPolicy(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val localScanner = LocalDocumentTreeScanner(appContext)

    suspend fun analyze(
        localTreeUri: Uri,
        remoteRoot: String,
        requestedRoute: FtpRoute,
    ): TransferAnalysis {
        val profile = configuredProfile()
        val localTree = localScanner.scan(localTreeUri)
        val canonicalRoot = XboxPath.canonical(remoteRoot)

        val remoteResult = when (requestedRoute) {
            FtpRoute.Fast -> scanRemote(profile, localTree, canonicalRoot, FtpRoute.Fast)
            FtpRoute.Background -> scanRemote(profile, localTree, canonicalRoot, FtpRoute.Background)
            FtpRoute.Auto -> runCatching {
                scanRemote(profile, localTree, canonicalRoot, FtpRoute.Fast)
            }.getOrElse { fastError ->
                scanRemote(
                    profile = profile,
                    localTree = localTree,
                    canonicalRoot = canonicalRoot,
                    route = FtpRoute.Background,
                ).copy(
                    fallbackReason = fastError.message
                        ?: "Aurora FTP indisponível durante a análise.",
                )
            }
        }

        return TransferCompareEngine.compare(
            local = localTree,
            remoteRoot = canonicalRoot,
            requestedRoute = requestedRoute,
            usedRoute = remoteResult.route,
            fallbackReason = remoteResult.fallbackReason,
            remoteFiles = remoteResult.files,
        )
    }

    /**
     * Executes only Missing/Different items from a previously generated analysis.
     *
     * The same FTP session is reused while healthy. Transient transport failures
     * are retried on the same route with bounded backoff, restarting the current
     * file from byte zero. In Auto mode, exhausted Fast retries fall back to
     * FTPdll Background. Every successful STOR is verified with a remote SIZE.
     */
    suspend fun execute(
        analysis: TransferAnalysis,
        cancellationToken: TransferCancellationToken = TransferCancellationToken(),
        onProgress: (TransferExecutionProgress) -> Unit = {},
    ): TransferExecutionResult {
        val queue = analysis.items.filter { it.kind != TransferDiffKind.Same }
        if (queue.isEmpty()) {
            onProgress(
                TransferExecutionProgress(
                    status = TransferExecutionStatus.Completed,
                    route = analysis.usedRoute,
                    fileCount = 0,
                    verifiedFiles = 0,
                    totalBytes = 0L,
                    message = "Nada para enviar.",
                ),
            )
            return TransferExecutionResult(
                status = TransferExecutionStatus.Completed,
                route = analysis.usedRoute,
                uploadedFiles = 0,
                verifiedFiles = 0,
                transferredBytes = 0L,
                fallbackReason = analysis.fallbackReason,
                message = "Nada para enviar.",
            )
        }

        val profile = configuredProfile()
        val totalBytes = queue.sumOf { it.local.size }
        val startedAt = System.nanoTime()

        var session: XboxFtpSession? = null
        var activeRoute: FtpRoute? = null
        var desiredRoute = analysis.requestedRoute
        var fallbackReason = analysis.fallbackReason
        var completedBytes = 0L
        var verifiedFiles = 0
        var lastProgressAt = 0L
        var currentFile: String? = null

        fun emit(
            status: TransferExecutionStatus,
            item: TransferDiffItem? = null,
            fileIndex: Int = 0,
            currentBytes: Long = 0L,
            message: String? = null,
            force: Boolean = false,
        ) {
            val now = System.nanoTime()
            if (!force && status == TransferExecutionStatus.Uploading) {
                val elapsedSinceLast = now - lastProgressAt
                if (elapsedSinceLast < 120_000_000L && currentBytes < (item?.local?.size ?: Long.MAX_VALUE)) {
                    return
                }
            }
            lastProgressAt = now

            val elapsedSeconds = ((now - startedAt).coerceAtLeast(1L) / 1_000_000_000.0)
            val logicalBytes = (completedBytes + currentBytes).coerceAtMost(totalBytes)
            val speed = (logicalBytes / elapsedSeconds).roundToLong().coerceAtLeast(0L)
            val remaining = (totalBytes - logicalBytes).coerceAtLeast(0L)
            val eta = if (speed > 0L) (remaining / speed).coerceAtLeast(0L) else null

            onProgress(
                TransferExecutionProgress(
                    status = status,
                    route = activeRoute ?: desiredRoute,
                    currentFile = item?.relativePath,
                    fileIndex = fileIndex,
                    fileCount = queue.size,
                    currentFileBytes = currentBytes,
                    currentFileSize = item?.local?.size ?: 0L,
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                    verifiedFiles = verifiedFiles,
                    bytesPerSecond = speed,
                    etaSeconds = eta,
                    fallbackReason = fallbackReason,
                    message = message,
                ),
            )
        }

        suspend fun closeCurrentSession() {
            val current = session
            session = null
            activeRoute = null
            if (current != null) runCatching { current.close() }
        }

        suspend fun connect(route: FtpRoute) {
            val routed = sessionFactory.connect(profile, route)
            session = routed.session
            activeRoute = routed.route
            desiredRoute = routed.route
            routed.fallbackReason?.let { reason -> fallbackReason = reason }
        }

        fun cancelledResult(): TransferExecutionResult = TransferExecutionResult(
            status = TransferExecutionStatus.Cancelled,
            route = activeRoute ?: desiredRoute,
            uploadedFiles = verifiedFiles,
            verifiedFiles = verifiedFiles,
            transferredBytes = completedBytes,
            fallbackReason = fallbackReason,
            failedFile = currentFile,
            message = "Transferência cancelada.",
        )

        try {
            emit(
                status = TransferExecutionStatus.Preparing,
                message = "Abrindo conexão para ${queue.size} arquivo(s).",
                force = true,
            )

            if (cancellationToken.isCancelled()) return cancelledResult()

            queue.forEachIndexed { index, item ->
                currentFile = item.relativePath
                if (cancellationToken.isCancelled()) throw TransferCancelledSignal

                var retriedInBackground = false
                var sameRouteRetries = 0

                while (true) {
                    if (cancellationToken.isCancelled()) throw TransferCancelledSignal
                    val targetPath = remoteTarget(analysis.remoteRoot, item.relativePath)

                    try {
                        if (session == null) connect(desiredRoute)
                        val currentSession = checkNotNull(session) { "Sessão FTP não está aberta." }
                        activeRoute?.let { desiredRoute = it }

                        emit(
                            status = TransferExecutionStatus.Uploading,
                            item = item,
                            fileIndex = index + 1,
                            currentBytes = 0L,
                            message = "Enviando ${item.relativePath}",
                            force = true,
                        )

                        val input = appContext.contentResolver.openInputStream(Uri.parse(item.local.contentUri))
                            ?: error("Não foi possível abrir ${item.relativePath} no Android.")

                        currentSession.upload(targetPath, input) { sent ->
                            if (cancellationToken.isCancelled()) throw TransferCancelledSignal
                            emit(
                                status = TransferExecutionStatus.Uploading,
                                item = item,
                                fileIndex = index + 1,
                                currentBytes = sent,
                            )
                        }

                        emit(
                            status = TransferExecutionStatus.Verifying,
                            item = item,
                            fileIndex = index + 1,
                            currentBytes = item.local.size,
                            message = "Verificando SIZE remoto.",
                            force = true,
                        )

                        val remoteSize = currentSession.size(targetPath)
                        if (remoteSize != item.local.size) {
                            throw TransferVerificationException(
                                "SIZE inválido para ${item.relativePath}: esperado ${item.local.size}, recebido ${remoteSize ?: "indisponível"}.",
                            )
                        }

                        completedBytes += item.local.size
                        verifiedFiles += 1
                        emit(
                            status = TransferExecutionStatus.Verifying,
                            item = item,
                            fileIndex = index + 1,
                            currentBytes = 0L,
                            message = "Arquivo verificado.",
                            force = true,
                        )
                        break
                    } catch (cancelled: TransferCancelledSignal) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val failureRoute = activeRoute ?: desiredRoute
                        val transient = retryPolicy.isTransient(error)
                        val canRetrySameRoute = transient &&
                            sameRouteRetries < retryPolicy.maxSameRouteRetries

                        if (canRetrySameRoute) {
                            sameRouteRetries += 1
                            val waitMs = retryPolicy.delayMsForRetry(sameRouteRetries)
                            desiredRoute = failureRoute
                            emit(
                                status = TransferExecutionStatus.Preparing,
                                item = item,
                                fileIndex = index + 1,
                                currentBytes = 0L,
                                message = "Falha transitória em ${failureRoute.name}. Retry $sameRouteRetries/${retryPolicy.maxSameRouteRetries} em ${waitMs} ms; o arquivo será reiniciado do zero.",
                                force = true,
                            )
                            closeCurrentSession()
                            if (cancellationToken.isCancelled()) throw TransferCancelledSignal
                            delay(waitMs)
                            continue
                        }

                        val canFallback = transient &&
                            analysis.requestedRoute == FtpRoute.Auto &&
                            failureRoute == FtpRoute.Fast &&
                            !retriedInBackground

                        if (canFallback) {
                            retriedInBackground = true
                            sameRouteRetries = 0
                            val reason = error.message ?: "Aurora FTP falhou durante o envio."
                            fallbackReason = "Fast → Background após retries: $reason"
                            desiredRoute = FtpRoute.Background
                            emit(
                                status = TransferExecutionStatus.Preparing,
                                item = item,
                                fileIndex = index + 1,
                                currentBytes = 0L,
                                message = "Fast continuou instável. Mudando para FTPdll e reiniciando o arquivo.",
                                force = true,
                            )
                            closeCurrentSession()
                            continue
                        }

                        emit(
                            status = TransferExecutionStatus.Failed,
                            item = item,
                            fileIndex = index + 1,
                            message = error.message ?: "Falha durante a transferência.",
                            force = true,
                        )
                        return TransferExecutionResult(
                            status = TransferExecutionStatus.Failed,
                            route = activeRoute ?: failureRoute,
                            uploadedFiles = verifiedFiles,
                            verifiedFiles = verifiedFiles,
                            transferredBytes = completedBytes,
                            fallbackReason = fallbackReason,
                            failedFile = item.relativePath,
                            message = error.message ?: "Falha durante a transferência.",
                        )
                    }
                }
            }

            currentFile = null
            emit(
                status = TransferExecutionStatus.Completed,
                fileIndex = queue.size,
                message = "$verifiedFiles arquivo(s) enviados e verificados.",
                force = true,
            )
            return TransferExecutionResult(
                status = TransferExecutionStatus.Completed,
                route = activeRoute ?: desiredRoute,
                uploadedFiles = verifiedFiles,
                verifiedFiles = verifiedFiles,
                transferredBytes = completedBytes,
                fallbackReason = fallbackReason,
                message = "$verifiedFiles arquivo(s) enviados e verificados.",
            )
        } catch (_: TransferCancelledSignal) {
            emit(
                status = TransferExecutionStatus.Cancelled,
                message = "Cancelando e fechando a sessão FTP.",
                force = true,
            )
            return cancelledResult()
        } catch (error: Throwable) {
            emit(
                status = TransferExecutionStatus.Failed,
                message = error.message ?: "Não foi possível iniciar a transferência.",
                force = true,
            )
            return TransferExecutionResult(
                status = TransferExecutionStatus.Failed,
                route = activeRoute ?: desiredRoute,
                uploadedFiles = verifiedFiles,
                verifiedFiles = verifiedFiles,
                transferredBytes = completedBytes,
                fallbackReason = fallbackReason,
                failedFile = currentFile,
                message = error.message ?: "Não foi possível iniciar a transferência.",
            )
        } finally {
            closeCurrentSession()
        }
    }

    fun openRemoteBrowser(requestedRoute: FtpRoute): RemoteFolderBrowser =
        RemoteFolderBrowser(
            profile = configuredProfile(),
            requestedRoute = requestedRoute,
            sessionFactory = sessionFactory,
        )

    private fun configuredProfile(): XboxProfile = configStore.load()
        ?: error("Configure o Xbox na aba Xbox antes de usar o EchoTransfer.")

    private suspend fun scanRemote(
        profile: XboxProfile,
        localTree: LocalTransferTree,
        canonicalRoot: String,
        route: FtpRoute,
    ): RemoteScanResult {
        val routed = sessionFactory.connect(profile, route)
        return useSession(routed.session) { session ->
            RemoteScanResult(
                route = routed.route,
                files = remoteTreeScanner.scanForLocalTree(
                    session = session,
                    canonicalRemoteRoot = canonicalRoot,
                    localTree = localTree,
                ),
                fallbackReason = routed.fallbackReason,
            )
        }
    }

    private suspend fun <T> useSession(
        session: XboxFtpSession,
        block: suspend (XboxFtpSession) -> T,
    ): T = try {
        block(session)
    } finally {
        runCatching { session.close() }
    }
}

class RemoteFolderBrowser internal constructor(
    private val profile: XboxProfile,
    private val requestedRoute: FtpRoute,
    private val sessionFactory: XboxFtpSessionFactory,
) {
    private var session: XboxFtpSession? = null
    private var activeRoute: FtpRoute? = null
    private var fallbackReason: String? = null

    suspend fun list(canonicalPath: String): RemoteDirectoryListing {
        val canonical = XboxPath.canonical(canonicalPath)

        if (requestedRoute != FtpRoute.Auto) {
            return listUsing(canonical, requestedRoute)
        }

        if (activeRoute == FtpRoute.Background) {
            return listUsing(canonical, FtpRoute.Background)
        }

        return runCatching {
            listUsing(canonical, FtpRoute.Fast)
        }.getOrElse { fastError ->
            closeSession()
            fallbackReason = fastError.message ?: "Aurora FTP indisponível durante a navegação."
            listUsing(canonical, FtpRoute.Background)
        }
    }

    suspend fun close() {
        closeSession()
    }

    private suspend fun listUsing(
        canonicalPath: String,
        route: FtpRoute,
    ): RemoteDirectoryListing {
        val current = sessionFor(route)
        return RemoteDirectoryListing(
            canonicalPath = canonicalPath,
            route = activeRoute ?: route,
            directories = current.list(canonicalPath)
                .filter(RemoteEntry::isDirectory)
                .sortedBy { it.name.lowercase() },
            fallbackReason = fallbackReason,
        )
    }

    private suspend fun sessionFor(route: FtpRoute): XboxFtpSession {
        if (session != null && activeRoute == route) return checkNotNull(session)

        closeSession()
        val routed = sessionFactory.connect(profile, route)
        session = routed.session
        activeRoute = routed.route
        routed.fallbackReason?.let { fallbackReason = it }
        return routed.session
    }

    private suspend fun closeSession() {
        val current = session
        session = null
        activeRoute = null
        if (current != null) runCatching { current.close() }
    }
}

private fun remoteTarget(remoteRoot: String, relativePath: String): String =
    XboxPath.canonical(
        XboxPath.canonical(remoteRoot).trimEnd('/') + "/" + relativePath.replace('\\', '/').trim('/'),
    )

private class TransferVerificationException(message: String) : IllegalStateException(message)

private object TransferCancelledSignal : RuntimeException(null, null, false, false)

private data class RemoteScanResult(
    val route: FtpRoute,
    val files: List<RemoteTransferFile>,
    val fallbackReason: String? = null,
)

data class RemoteDirectoryListing(
    val canonicalPath: String,
    val route: FtpRoute,
    val directories: List<RemoteEntry>,
    val fallbackReason: String? = null,
)
