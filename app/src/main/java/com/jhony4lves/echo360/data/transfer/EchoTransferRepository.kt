package com.jhony4lves.echo360.data.transfer

import android.content.Context
import android.net.Uri
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.RemoteTransferFile
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCompareEngine
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory

class EchoTransferRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val remoteTreeScanner: RemoteTreeScanner = RemoteTreeScanner(),
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
