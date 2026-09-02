package com.jhony4lves.echo360.data.tu

import com.jhony4lves.echo360.domain.tu.EchoTuTitleId
import com.jhony4lves.echo360.domain.tu.TitleUpdateCandidate
import com.jhony4lves.echo360.domain.tu.TitleUpdateLocation
import com.jhony4lves.echo360.domain.tu.TitleUpdateSourceResult
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.CancellationException

class EchoTuScanner(
    private val maxCandidatesPerSource: Int = 128,
) {
    init {
        require(maxCandidatesPerSource > 0) { "maxCandidatesPerSource deve ser positivo." }
    }

    suspend fun scanContentFolder(
        session: XboxFtpSession,
        titleIdHex: String,
    ): TitleUpdateSourceResult {
        val titleId = EchoTuTitleId.normalize(titleIdHex)
        return scanDirectory(
            session = session,
            canonicalDirectory = contentDirectory(titleId),
            location = TitleUpdateLocation.ContentFolder,
            titleIdHex = titleId,
            candidateFilter = { it.startsWith("tu") },
        )
    }

    suspend fun scanLegacyCache(session: XboxFtpSession): TitleUpdateSourceResult = scanDirectory(
        session = session,
        canonicalDirectory = LEGACY_CACHE_DIRECTORY,
        location = TitleUpdateLocation.LegacyCache,
        titleIdHex = null,
        candidateFilter = { it.startsWith("TU_") },
    )

    private suspend fun scanDirectory(
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
            val retained = filtered.take(maxCandidatesPerSource)
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
                limitReached = filtered.size > maxCandidatesPerSource,
                detail = if (filtered.isEmpty()) {
                    "Nenhum candidato com o padrão esperado foi encontrado."
                } else {
                    null
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

    private fun safeError(error: Throwable): String =
        error.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(220)
            ?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "fonte indisponível" }

    companion object {
        const val LEGACY_CACHE_DIRECTORY = "/Hdd1/Cache"

        fun contentDirectory(titleIdHex: String): String =
            "/Hdd1/Content/0000000000000000/${EchoTuTitleId.normalize(titleIdHex)}/000B0000"
    }
}
