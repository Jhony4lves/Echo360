package com.jhony4lves.echo360.data.tu

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.tu.EchoTuTitleId
import com.jhony4lves.echo360.domain.tu.RuntimeTitleUpdateObservation
import com.jhony4lves.echo360.domain.tu.TitleUpdateInventory
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Evidence-first Title Update inventory.
 *
 * v1 observes classic Hdd1 TU locations only. It never installs, moves,
 * renames or deletes a package and deliberately does not label any candidate
 * as "latest" without a trustworthy external catalog.
 */
class EchoTuRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
    private val scanner: EchoTuScanner = EchoTuScanner(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun currentRuntime(): RuntimeTitleUpdateObservation? = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de ler o título atual.")
        observeRuntime(profile)
    }

    suspend fun inspect(titleId: String): TitleUpdateInventory = withContext(Dispatchers.IO) {
        val requestedTitleId = EchoTuTitleId.normalize(titleId)
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de inspecionar Title Updates.")

        var session: XboxFtpSession? = null
        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        session = routed.session
        try {
            val content = scanner.scanContentFolder(routed.session, requestedTitleId)
            val cache = scanner.scanLegacyCache(routed.session)
            val runtime = observeRuntime(profile)
            TitleUpdateInventory(
                requestedTitleIdHex = requestedTitleId,
                actualRoute = routed.route,
                contentFolder = content,
                legacyCache = cache,
                runtime = runtime,
                runtimeMatchesRequestedTitle = runtime?.titleIdHex == requestedTitleId,
                checkedAtEpochMs = System.currentTimeMillis(),
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.close() }
            }
        }
    }

    private suspend fun observeRuntime(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
    ): RuntimeTitleUpdateObservation? = try {
        val now = novaClient.nowPlaying(profile)
        if (now.titleId == 0L) return null
        RuntimeTitleUpdateObservation(
            titleIdHex = now.titleIdHex,
            mediaIdHex = now.mediaIdHex.takeUnless { now.mediaId == 0L },
            reportedTuVersion = now.titleUpdateVersion.coerceAtLeast(0),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
