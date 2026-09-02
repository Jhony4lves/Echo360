package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource
import com.jhony4lves.echo360.domain.library.GameEntry
import kotlinx.coroutines.CancellationException

internal data class RemoteIntegrityProbeResult(
    val findings: List<IntegrityFinding>,
    val verified: Boolean,
    val message: String,
)

/**
 * Read-only remote verifier for one catalog item.
 *
 * It consumes transport-neutral directory-list and object-stat evidence. FTP is
 * the compatibility provider today; future EchoCore FILE_STAT / bounded
 * DIR_LIST can feed the same logic after the Xbox-side contract is promoted.
 */
internal class RemoteGameIntegrityProbe {
    suspend fun verify(
        filesystem: RemoteReadOnlyFilesystem,
        game: GameEntry,
    ): RemoteIntegrityProbeResult {
        val directory = game.canonicalDirectory?.trimEnd('/')
        val executable = game.executable.trim()

        if (directory.isNullOrBlank() || !isSafeFileName(executable)) {
            return RemoteIntegrityProbeResult(
                findings = emptyList(),
                verified = false,
                message = "Verificação remota bloqueada porque o path local do jogo não é seguro ou não foi resolvido.",
            )
        }
        val executablePath = "$directory/$executable"

        val listing = try {
            filesystem.list(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return verifyByStatAfterListFailure(filesystem, game, executablePath, error)
        }

        val entry = listing.entries.firstOrNull { it.name.equals(executable, ignoreCase = true) }
        if (entry != null) {
            if (entry.objectType == RemoteObjectType.Directory) {
                return executableIsDirectory(game, entry.canonicalPath)
            }

            if (entry.sizeBytes <= 0L) {
                return resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_EMPTY,
                    severity = IntegritySeverity.Error,
                    title = "Executável remoto vazio",
                    evidence = "${entry.canonicalPath} foi listado com ${entry.sizeBytes} bytes.",
                    action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                    message = "O executável foi encontrado, mas não possui conteúdo válido.",
                )
            }

            return RemoteIntegrityProbeResult(
                findings = emptyList(),
                verified = true,
                message = "Executável confirmado no Xbox (${entry.sizeBytes} bytes).",
            )
        }

        val directStat = safeStat(filesystem, executablePath)
        return when {
            directStat?.objectType == RemoteObjectType.Directory ->
                executableIsDirectory(game, directStat.canonicalPath)

            directStat?.objectType == RemoteObjectType.File && directStat.sizeBytes > 0L ->
                RemoteIntegrityProbeResult(
                    findings = emptyList(),
                    verified = true,
                    message = "Executável confirmado por STAT (${directStat.sizeBytes} bytes).",
                )

            directStat?.objectType == RemoteObjectType.File && directStat.sizeBytes == 0L ->
                resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_EMPTY,
                    severity = IntegritySeverity.Error,
                    title = "Executável remoto vazio",
                    evidence = "$executablePath respondeu STAT com tamanho 0.",
                    action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                    message = "O executável existe, mas está vazio.",
                )

            listing.limitReached ->
                resultWithFinding(
                    game = game,
                    code = CODE_DIRECTORY_LIMIT_REACHED,
                    severity = IntegritySeverity.Info,
                    title = "Listagem remota limitada",
                    evidence = "A leitura de $directory atingiu o limite da fonte e $executable não apareceu na parte recebida; STAT também não o confirmou.",
                    action = "Não conclua que o executável está ausente. Repita com STAT direto ou uma leitura mais específica.",
                    message = "A listagem atingiu o limite; a ausência do executável ficou inconclusiva.",
                )

            listing.entries.isNotEmpty() ->
                resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_MISSING,
                    severity = IntegritySeverity.Error,
                    title = "Executável não encontrado no Xbox",
                    evidence = "O diretório $directory retornou ${listing.entries.size} entrada(s), mas $executable não apareceu na listagem nem respondeu a STAT.",
                    action = "Confirme a pasta de instalação e a integridade da cópia antes de alterar o catálogo do Aurora.",
                    message = "O diretório responde com conteúdo, mas o executável catalogado está ausente.",
                )

            else ->
                resultWithFinding(
                    game = game,
                    code = CODE_EMPTY_OR_UNPARSED_LIST,
                    severity = IntegritySeverity.Info,
                    title = "Listagem remota inconclusiva",
                    evidence = "A leitura de $directory não produziu entradas interpretáveis e STAT não confirmou $executable.",
                    action = "Tente novamente por outra fonte read-only antes de concluir que o executável está ausente.",
                    message = "A pasta respondeu sem entradas interpretáveis; a existência do executável ficou inconclusiva.",
                )
        }
    }

    private suspend fun verifyByStatAfterListFailure(
        filesystem: RemoteReadOnlyFilesystem,
        game: GameEntry,
        executablePath: String,
        listError: Throwable,
    ): RemoteIntegrityProbeResult {
        val directStat = safeStat(filesystem, executablePath)
        return when {
            directStat?.objectType == RemoteObjectType.Directory ->
                executableIsDirectory(game, directStat.canonicalPath)

            directStat?.objectType == RemoteObjectType.File && directStat.sizeBytes > 0L ->
                RemoteIntegrityProbeResult(
                    findings = emptyList(),
                    verified = true,
                    message = "A listagem não respondeu, mas o executável foi confirmado por STAT (${directStat.sizeBytes} bytes).",
                )

            directStat?.objectType == RemoteObjectType.File && directStat.sizeBytes == 0L ->
                resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_EMPTY,
                    severity = IntegritySeverity.Error,
                    title = "Executável remoto vazio",
                    evidence = "$executablePath respondeu STAT com tamanho 0 após falha na listagem.",
                    action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                    message = "O executável existe, mas está vazio.",
                )

            else ->
                resultWithFinding(
                    game = game,
                    code = CODE_DIRECTORY_UNREADABLE,
                    severity = IntegritySeverity.Info,
                    title = "Verificação remota inconclusiva",
                    evidence = "Listagem falhou em ${game.canonicalDirectory}: ${safeError(listError)}; STAT também não confirmou o executável.",
                    action = "Verifique a fonte read-only e tente novamente. Não trate esta falha de transporte como problema do jogo.",
                    message = "A verificação remota ficou inconclusiva por falha de acesso ao diretório.",
                )
        }
    }

    private suspend fun safeStat(
        filesystem: RemoteReadOnlyFilesystem,
        path: String,
    ): RemoteObjectStat? = try {
        filesystem.stat(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun executableIsDirectory(game: GameEntry, path: String) = resultWithFinding(
        game = game,
        code = CODE_EXECUTABLE_IS_DIRECTORY,
        severity = IntegritySeverity.Error,
        title = "Executável remoto virou diretório",
        evidence = "$path foi reportado como diretório.",
        action = "Não tente corrigir automaticamente; revise a pasta do jogo e recopie apenas de uma fonte íntegra.",
        message = "O diretório responde, mas o executável catalogado não é um arquivo.",
    )

    private fun resultWithFinding(
        game: GameEntry,
        code: String,
        severity: IntegritySeverity,
        title: String,
        evidence: String,
        action: String,
        message: String,
    ) = RemoteIntegrityProbeResult(
        findings = listOf(
            IntegrityFinding(
                code = code,
                severity = severity,
                source = IntegritySource.Remote,
                title = title,
                evidence = evidence,
                suggestedAction = action,
                gameStableKey = game.stableKey,
            ),
        ),
        verified = false,
        message = message,
    )

    private fun isSafeFileName(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." && !value.contains('/') && !value.contains('\\')

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(240)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "erro de transporte" }

    companion object {
        const val CODE_DIRECTORY_UNREADABLE = "remote.directory.unreadable"
        const val CODE_DIRECTORY_LIMIT_REACHED = "remote.directory.limit_reached"
        const val CODE_EMPTY_OR_UNPARSED_LIST = "remote.directory.empty_or_unparsed"
        const val CODE_EXECUTABLE_MISSING = "remote.executable.missing"
        const val CODE_EXECUTABLE_EMPTY = "remote.executable.empty"
        const val CODE_EXECUTABLE_IS_DIRECTORY = "remote.executable.is_directory"
    }
}
