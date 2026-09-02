package com.jhony4lves.echo360.data.integrity

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.integrity.EchoIntegrityAnalyzer
import com.jhony4lves.echo360.domain.integrity.EchoIntegrityReport
import com.jhony4lves.echo360.domain.integrity.IntegrityRemoteRoute
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class EchoIntegrityRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val analyzer = EchoIntegrityAnalyzer()
    private val remoteProbe = RemoteGameIntegrityProbe()

    fun analyze(
        snapshot: LibrarySnapshot,
        game: GameEntry? = null,
        checkedAtEpochMs: Long = System.currentTimeMillis(),
    ): EchoIntegrityReport = analyzer.analyze(snapshot, game, checkedAtEpochMs)

    suspend fun verifyGame(
        snapshot: LibrarySnapshot,
        game: GameEntry,
        baseline: EchoIntegrityReport = analyzer.analyze(snapshot, game),
    ): EchoIntegrityReport = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: return@withContext baseline.mergeRemote(
                remoteFindings = emptyList(),
                verified = false,
                route = null,
                message = "Configure o Xbox antes de fazer a verificação remota.",
                checkedAtEpochMs = System.currentTimeMillis(),
            )

        val routed = try {
            sessionFactory.connect(profile, FtpRoute.Auto)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext baseline.mergeRemote(
                remoteFindings = emptyList(),
                verified = false,
                route = null,
                message = "Não foi possível abrir uma sessão FTP: ${safeError(error)}",
                checkedAtEpochMs = System.currentTimeMillis(),
            )
        }

        try {
            val filesystem = FtpReadOnlyFilesystem(routed.session)
            val result = remoteProbe.verify(filesystem, game)
            baseline.mergeRemote(
                remoteFindings = result.findings,
                verified = result.verified,
                route = routed.route.toIntegrityRoute(),
                message = buildString {
                    append(result.message)
                    routed.fallbackReason?.takeIf(String::isNotBlank)?.let { reason ->
                        append(" Rota Background usada após falha da Fast: ")
                        append(reason.replace('\n', ' ').replace('\r', ' ').take(180))
                    }
                },
                checkedAtEpochMs = System.currentTimeMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            baseline.mergeRemote(
                remoteFindings = emptyList(),
                verified = false,
                route = routed.route.toIntegrityRoute(),
                message = "Verificação remota inconclusiva: ${safeError(error)}",
                checkedAtEpochMs = System.currentTimeMillis(),
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { routed.session.close() }
            }
        }
    }

    private fun FtpRoute.toIntegrityRoute(): IntegrityRemoteRoute? = when (this) {
        FtpRoute.Fast -> IntegrityRemoteRoute.Fast
        FtpRoute.Background -> IntegrityRemoteRoute.Background
        FtpRoute.Auto -> null
    }

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(240)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "erro de transporte" }
}
