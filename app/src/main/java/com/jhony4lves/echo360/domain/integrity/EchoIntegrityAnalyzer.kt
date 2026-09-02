package com.jhony4lves.echo360.domain.integrity

import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot

/**
 * Offline-first integrity analysis based only on facts already present in the
 * cached Aurora snapshot. Rules intentionally avoid title-name heuristics and
 * do not treat optional/unknown metadata (for example Media ID 0) as corruption.
 */
class EchoIntegrityAnalyzer {
    fun analyze(
        snapshot: LibrarySnapshot,
        game: GameEntry? = null,
        checkedAtEpochMs: Long = System.currentTimeMillis(),
    ): EchoIntegrityReport {
        val findings = buildList {
            addAll(collectionFindings(snapshot))
            val targets = if (game == null) snapshot.games else listOf(game)
            targets.forEach { target -> addAll(gameFindings(snapshot, target)) }
        }
            .distinctBy { finding ->
                "${finding.source}:${finding.code}:${finding.gameStableKey}:${finding.evidence}"
            }
            .sortedWith(
                compareByDescending<IntegrityFinding> { it.severity.rank }
                    .thenBy { it.code }
                    .thenBy { it.gameStableKey.orEmpty() },
            )

        return EchoIntegrityReport(
            findings = findings,
            checkedAtEpochMs = checkedAtEpochMs,
        )
    }

    private fun collectionFindings(snapshot: LibrarySnapshot): List<IntegrityFinding> = buildList {
        if (snapshot.databaseBytes <= 0L) {
            add(
                finding(
                    code = CODE_DATABASE_EMPTY,
                    severity = IntegritySeverity.Error,
                    title = "Snapshot do Aurora sem conteúdo",
                    evidence = "O content.db cacheado informa ${snapshot.databaseBytes} bytes.",
                    action = "Sincronize novamente a biblioteca antes de confiar no catálogo.",
                ),
            )
        }

        if (snapshot.databaseRemotePath.isBlank()) {
            add(
                finding(
                    code = CODE_DATABASE_PATH_MISSING,
                    severity = IntegritySeverity.Error,
                    title = "Origem do content.db desconhecida",
                    evidence = "O snapshot não contém o caminho remoto do banco do Aurora.",
                    action = "Refaça a descoberta do Aurora e a sincronização da biblioteca.",
                ),
            )
        }

        if (snapshot.auroraRoot.isBlank()) {
            add(
                finding(
                    code = CODE_AURORA_ROOT_MISSING,
                    severity = IntegritySeverity.Error,
                    title = "Raiz do Aurora desconhecida",
                    evidence = "O snapshot não contém a raiz usada para dados do Aurora.",
                    action = "Refaça a descoberta do Aurora antes de verificar artwork ou arquivos do jogo.",
                ),
            )
        }

        snapshot.games
            .groupBy(GameEntry::stableKey)
            .filterValues { it.size > 1 }
            .forEach { (stableKey, duplicates) ->
                add(
                    finding(
                        code = CODE_DUPLICATE_ENTRY,
                        severity = IntegritySeverity.Warning,
                        title = "Entrada duplicada no catálogo",
                        evidence = "${duplicates.size} registros compartilham a chave $stableKey.",
                        action = "Revise paths duplicados no Aurora antes de remover qualquer conteúdo.",
                        gameStableKey = stableKey,
                    ),
                )
            }
    }

    private fun gameFindings(
        snapshot: LibrarySnapshot,
        game: GameEntry,
    ): List<IntegrityFinding> = buildList {
        if (game.titleId == 0L) {
            add(
                finding(
                    code = CODE_TITLE_ID_ZERO,
                    severity = IntegritySeverity.Error,
                    title = "Title ID inválido",
                    evidence = "${game.title.ifBlank { "Este item" }} foi registrado com Title ID 00000000.",
                    action = "Não use este registro para launch até confirmar a entrada correta no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        if (game.title.isBlank()) {
            add(
                finding(
                    code = CODE_TITLE_BLANK,
                    severity = IntegritySeverity.Warning,
                    title = "Título vazio no catálogo",
                    evidence = "O ContentItem ${game.databaseId} não possui nome de exibição.",
                    action = "Revise o scan/path correspondente no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        if (game.discNumber < 0) {
            add(
                finding(
                    code = CODE_DISC_NUMBER_NEGATIVE,
                    severity = IntegritySeverity.Warning,
                    title = "Número de disco suspeito",
                    evidence = "O catálogo informa discNumber=${game.discNumber}.",
                    action = "Compare este registro com o item correspondente no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        val directorySegments = normalizedSegments(game.directory)
        if (directorySegments.any { it == ".." }) {
            add(
                finding(
                    code = CODE_PATH_TRAVERSAL,
                    severity = IntegritySeverity.Error,
                    title = "Diretório contém segmento inseguro",
                    evidence = "O diretório catalogado contém '..': ${game.directory}",
                    action = "Não execute operações remotas nesse path até corrigir o scan no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        val contentRoot = game.contentRoot?.trim().orEmpty()
        if (contentRoot.isBlank() || game.canonicalDirectory == null) {
            add(
                finding(
                    code = CODE_CONTENT_ROOT_MISSING,
                    severity = IntegritySeverity.Error,
                    title = "Mountpoint do jogo não resolvido",
                    evidence = "O catálogo não conseguiu formar um diretório canônico para ${game.title.ifBlank { game.stableKey }}.",
                    action = "Sincronize novamente e confirme o mountpoint/path do jogo no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        val executable = game.executable.trim()
        when {
            executable.isBlank() -> add(
                finding(
                    code = CODE_EXECUTABLE_MISSING,
                    severity = IntegritySeverity.Error,
                    title = "Executável não informado",
                    evidence = "O registro do jogo não possui executável.",
                    action = "Confirme o executável configurado pelo Aurora antes de tentar iniciar o jogo.",
                    gameStableKey = game.stableKey,
                ),
            )

            !isSafeFileName(executable) -> add(
                finding(
                    code = CODE_EXECUTABLE_UNSAFE,
                    severity = IntegritySeverity.Error,
                    title = "Executável contém path inesperado",
                    evidence = "O campo executável deveria ser apenas um nome de arquivo, mas contém: $executable",
                    action = "Não envie launch para este registro até revisar o scan/path no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        val canonicalExecutable = game.canonicalExecutablePath
        if (canonicalExecutable != null && normalizedSegments(canonicalExecutable).any { it == ".." }) {
            add(
                finding(
                    code = CODE_EXECUTABLE_PATH_TRAVERSAL,
                    severity = IntegritySeverity.Error,
                    title = "Path final do executável é inseguro",
                    evidence = "O path resolvido contém '..': $canonicalExecutable",
                    action = "Bloqueie operações neste registro e corrija o path de origem no Aurora.",
                    gameStableKey = game.stableKey,
                ),
            )
        }

        val sameStableKey = snapshot.games.count { it.stableKey == game.stableKey }
        if (sameStableKey > 1 && none { it.code == CODE_DUPLICATE_ENTRY && it.gameStableKey == game.stableKey }) {
            add(
                finding(
                    code = CODE_DUPLICATE_ENTRY,
                    severity = IntegritySeverity.Warning,
                    title = "Entrada duplicada no catálogo",
                    evidence = "$sameStableKey registros compartilham a chave ${game.stableKey}.",
                    action = "Revise paths duplicados no Aurora antes de remover qualquer conteúdo.",
                    gameStableKey = game.stableKey,
                ),
            )
        }
    }

    private fun finding(
        code: String,
        severity: IntegritySeverity,
        title: String,
        evidence: String,
        action: String,
        gameStableKey: String? = null,
    ) = IntegrityFinding(
        code = code,
        severity = severity,
        source = IntegritySource.Snapshot,
        title = title,
        evidence = evidence,
        suggestedAction = action,
        gameStableKey = gameStableKey,
    )

    private fun normalizedSegments(path: String): List<String> = path
        .replace('\\', '/')
        .split('/')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun isSafeFileName(value: String): Boolean =
        value != "." && value != ".." && !value.contains('/') && !value.contains('\\')

    companion object {
        const val CODE_DATABASE_EMPTY = "snapshot.database.empty"
        const val CODE_DATABASE_PATH_MISSING = "snapshot.database.path_missing"
        const val CODE_AURORA_ROOT_MISSING = "snapshot.aurora_root.missing"
        const val CODE_DUPLICATE_ENTRY = "catalog.entry.duplicate"
        const val CODE_TITLE_ID_ZERO = "game.title_id.zero"
        const val CODE_TITLE_BLANK = "game.title.blank"
        const val CODE_DISC_NUMBER_NEGATIVE = "game.disc_number.negative"
        const val CODE_PATH_TRAVERSAL = "game.directory.traversal"
        const val CODE_CONTENT_ROOT_MISSING = "game.content_root.missing"
        const val CODE_EXECUTABLE_MISSING = "game.executable.missing"
        const val CODE_EXECUTABLE_UNSAFE = "game.executable.unsafe"
        const val CODE_EXECUTABLE_PATH_TRAVERSAL = "game.executable_path.traversal"
    }
}
