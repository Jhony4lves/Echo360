package com.jhony4lves.echo360.data.tu

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.tu.EchoTuTitleId
import com.jhony4lves.echo360.domain.tu.RuntimeTitleUpdateObservation
import com.jhony4lves.echo360.domain.tu.TitleUpdateCandidate
import com.jhony4lves.echo360.domain.tu.TitleUpdateInventory
import com.jhony4lves.echo360.domain.tu.TitleUpdateLocation
import com.jhony4lves.echo360.domain.tu.TitleUpdateSourceResult
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
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
 * v1 only observes the two classic Hdd1 locations:
 * - /Hdd1/Content/0000000000000000/<TitleID>/000B0000 for lowercase tu* packages;
 * - /Hdd1/Cache for legacy uppercase TU_* packages.
 *
 * It never installs, moves, renames or deletes a package and deliberately does
 * not label any candidate as "latest" without a trustworthy external catalog.
 */
class EchoTuRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun inspect(titleId: String): TitleUpdateInventory = withContext(Dispatchers.IO) {
        val requestedTitleId = EchoTuTitleId.normalize(titleId)
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de inspecionar Title Updates.")

        var session: XboxFtpSession? = null
        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        session = routed.session
        val contentDirectory = contentDirectory(requestedTitleId)
        try {
            val content = inspectDirectory(
                session = routed.session,
                canonicalDirectory = contentDirectory,
                location = TitleUpdateLocation.ContentFolder,
                titleIdHex = requestedTitleId,
                candidateFilter = { it.startsWith("tu") },
            )
            val cache = inspectDirectory(
                session = routed.session,
                canonicalDirectory = LEGACY_CACHE_DIRECTORY,
                location = TitleUpdateLocation.LegacyCache,
                titleIdHex = null,
                candidateFilter = { it.startsWith("TU_") },
            )

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

    private suspend fun inspectDirectory(
        session: XboxFtpSession,
        canonicalDirectory: String,
        location: TitleUpdateLocation,
        titleIdHex: String?,
        candidateFilter: (String) -> Boolean,
    ): TitleUpdateSourceResult {
        return try {
            val raw = session.list(canonicalDirectory)
            val filtered = raw
                .asSequence()
                .filterNot(RemoteEntry::isDirectory)
                .filter { candidateFilter(it.name) }
                .sortedBy { it.name.lowercase() }
                .toList()
            val retained = filtered.take(MAX_CANDIDATES_PER_SOURCE)
            val candidates = retained.map { entry ->
                val size = runCatching { session.size(entry.canonicalPath) }.getOrNull()
                TitleUpdateCandidate(
                    location = location,
                    remotePath = entry.canonicalPath,
                    fileName = entry.name,
                    sizeBytes = size,
                    titleIdHex = titleIdHex,
                )
            }
            TitleUpdateSourceResult(
                canonicalDirectory = canonicalDirectory,
                available = true,
                candidates = candidates,
                limitReached = filtered.size > MAX_CANDIDATES_PER_SOURCE,
                detail = when {
                    filtered.isEmpty() -> "Nenhum candidato com o padrão esperado foi encontrado."
                    else -> null
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            TitleUpdateSourceResult(
                canonicalDirectory = canonicalDirectory,
                available = false,
                candidates = emptyList(),
                detail = safeError(error),
            )
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

    private fun safeError(error: Throwable): String =
        error.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(220)
            ?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "fonte indisponível" }

    companion object {
        private const val MAX_CANDIDATES_PER_SOURCE = 128
        private const val LEGACY_CACHE_DIRECTORY = "/Hdd1/Cache"

        fun contentDirectory(titleIdHex: String): String =
            "/Hdd1/Content/0000000000000000/${EchoTuTitleId.normalize(titleIdHex)}/000B0000"
    }
}
