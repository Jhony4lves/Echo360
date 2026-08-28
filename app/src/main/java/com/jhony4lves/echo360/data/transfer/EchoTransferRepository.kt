package com.jhony4lves.echo360.data.transfer

import android.content.Context
import android.net.Uri
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCompareEngine
import com.jhony4lves.echo360.domain.xbox.XboxPath
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
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de analisar.")
        val localTree = localScanner.scan(localTreeUri)
        val canonicalRoot = XboxPath.canonical(remoteRoot)

        val remoteResult = when (requestedRoute) {
            FtpRoute.Fast -> scanRemote(profile, localTree, canonicalRoot, FtpRoute.Fast)
            FtpRoute.Background -> scanRemote(profile, localTree, canonicalRoot, FtpRoute.Background)
            FtpRoute.Auto -> {
                runCatching {
                    scanRemote(profile, localTree, canonicalRoot, FtpRoute.Fast)
                }.getOrElse { fastError ->
                    val background = scanRemote(
                        profile,
                        localTree,
                        canonicalRoot,
                        FtpRoute.Background,
                    )
                    background.copy(
                        fallbackReason = fastError.message ?: "Aurora FTP indisponível durante a análise.",
                    )
                }
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

    suspend fun listRemoteDirectories(
        canonicalPath: String,
        requestedRoute: FtpRoute,
    ): RemoteDirectoryListing {
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox antes de navegar.")
        val canonical = XboxPath.canonical(canonicalPath)

        return when (requestedRoute) {
            FtpRoute.Fast -> listRemote(profile, canonical, FtpRoute.Fast)
            FtpRoute.Background -> listRemote(profile, canonical, FtpRoute.Background)
            FtpRoute.Auto -> runCatching {
                listRemote(profile, canonical, FtpRoute.Fast)
            }.getOrElse { fastError ->
                listRemote(profile, canonical, FtpRoute.Background).copy(
                    fallbackReason = fastError.message ?: "Aurora FTP indisponível durante a navegação.",
                )
            }
        }
    }

    private suspend fun scanRemote(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
        localTree: com.jhony4lves.echo360.domain.transfer.LocalTransferTree,
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

    private suspend fun listRemote(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
        canonicalPath: String,
        route: FtpRoute,
    ): RemoteDirectoryListing {
        val routed = sessionFactory.connect(profile, route)
        return useSession(routed.session) { session ->
            RemoteDirectoryListing(
                canonicalPath = canonicalPath,
                route = routed.route,
                directories = session.list(canonicalPath)
                    .filter(RemoteEntry::isDirectory)
                    .sortedBy { it.name.lowercase() },
                fallbackReason = routed.fallbackReason,
            )
        }
    }

    private suspend fun <T> useSession(
        session: XboxFtpSession,
        block: suspend (XboxFtpSession) -> T,
    ): T {
        return try {
            block(session)
        } finally {
            runCatching { session.close() }
        }
    }
}

private data class RemoteScanResult(
    val route: FtpRoute,
    val files: List<com.jhony4lves.echo360.domain.transfer.RemoteTransferFile>,
    val fallbackReason: String? = null,
)

data class RemoteDirectoryListing(
    val canonicalPath: String,
    val route: FtpRoute,
    val directories: List<RemoteEntry>,
    val fallbackReason: String? = null,
)
