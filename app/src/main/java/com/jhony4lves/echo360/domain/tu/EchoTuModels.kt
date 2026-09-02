package com.jhony4lves.echo360.domain.tu

import com.jhony4lves.echo360.network.ftp.FtpRoute

enum class TitleUpdateLocation {
    ContentFolder,
    LegacyCache,
}

data class TitleUpdateCandidate(
    val location: TitleUpdateLocation,
    val remotePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val titleIdHex: String? = null,
) {
    init {
        require(remotePath.startsWith('/')) { "TU remotePath precisa ser canônico." }
        require(fileName.isNotBlank()) { "TU fileName não pode ser vazio." }
        require(sizeBytes >= 0L) { "TU sizeBytes inválido." }
        titleIdHex?.let {
            require(it.matches(Regex("^[0-9A-F]{8}$"))) { "Title ID da TU inválido." }
        }
    }
}

data class TitleUpdateSourceResult(
    val canonicalDirectory: String,
    val available: Boolean,
    val candidates: List<TitleUpdateCandidate>,
    val limitReached: Boolean = false,
    val detail: String? = null,
)

data class RuntimeTitleUpdateObservation(
    val titleIdHex: String,
    val mediaIdHex: String?,
    val reportedTuVersion: Int,
) {
    init {
        require(titleIdHex.matches(Regex("^[0-9A-F]{8}$"))) { "Runtime Title ID inválido." }
        mediaIdHex?.let { require(it.matches(Regex("^[0-9A-F]{8}$"))) { "Runtime Media ID inválido." } }
        require(reportedTuVersion >= 0) { "Runtime TU version inválida." }
    }
}

data class TitleUpdateInventory(
    val requestedTitleIdHex: String,
    val actualRoute: FtpRoute,
    val contentFolder: TitleUpdateSourceResult,
    val legacyCache: TitleUpdateSourceResult,
    val runtime: RuntimeTitleUpdateObservation? = null,
    val runtimeMatchesRequestedTitle: Boolean = false,
    val checkedAtEpochMs: Long,
) {
    init {
        require(requestedTitleIdHex.matches(Regex("^[0-9A-F]{8}$"))) { "Requested Title ID inválido." }
        require(actualRoute != FtpRoute.Auto) { "EchoTU precisa registrar a rota FTP já resolvida." }
    }

    val titleScopedCandidates: List<TitleUpdateCandidate>
        get() = contentFolder.candidates

    val legacyUnassignedCandidates: List<TitleUpdateCandidate>
        get() = legacyCache.candidates
}

object EchoTuTitleId {
    fun normalize(value: String): String {
        val clean = value.trim().removePrefix("0x").removePrefix("0X").uppercase()
        require(clean.matches(Regex("^[0-9A-F]{8}$"))) {
            "Informe um Title ID hexadecimal com 8 caracteres."
        }
        return clean
    }
}
