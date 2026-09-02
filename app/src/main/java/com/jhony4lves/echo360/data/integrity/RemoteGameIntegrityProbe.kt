package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.CancellationException

internal data class RemoteIntegrityProbeResult(
    val findings: List<IntegrityFinding>,
    val verified: Boolean,
    val message: String,
)

/**
 * Read-only remote verifier for one catalog item. It never creates directories,
 * uploads files or mutates Xbox state. LIST is preferred because it proves the
 * parent directory is readable; SIZE is used as a fallback for FTP servers whose
 * LIST behavior differs.
 */
internal class RemoteGameIntegrityProbe {
    suspend fun verify(
        session: XboxFtpSession,
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
            session.list(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return verifyBySizeAfterListFailure(session, game, executablePath, error)
        }

        val entry = listing.firstOrNull { it.name.equals(executable, ignoreCase = true) }
        if (entry != null) {
            if (entry.isDirectory) {
                return resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_IS_DIRECTORY,
                    severity = IntegritySeverity.Error,
                    title = "Executável remoto virou diretório",
                    evidence = "${entry.canonicalPath} foi listado como diretório.",
                    action = "Não tente corrigir automaticamente; revise a pasta do jogo e recopie apenas de uma fonte íntegra.",
                    message = "O diretório responde, mas o executável catalogado não é um arquivo.",
                )
            }

            if (entry.size <= 0L) {
                return resultWithFinding(
                    game = game,
                    code = CODE_EXECUTABLE_EMPTY,
                    severity = IntegritySeverity.Error,
                    title = "Executável remoto vazio",
                    evidence = "${entry.canonicalPath} foi listado com ${entry.size} bytes.",
                    action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                    message = "O executável foi encontrado, mas não possui conteúdo válido.",
                )
            }

            return RemoteIntegrityProbeResult(
                findings = emptyList(),
                verified = true,
                message = "Executável confirmado no Xbox (${entry.size} bytes).",
            )
        }

        val directSize = safeSize(session, executablePath)
        return when {
            directSize != null && directSize > 0L -> RemoteIntegrityProbeResult(
                findings = emptyList(),
                verified = true,
                message = "Executável confirmado por SIZE ($directSize bytes).",
            )

            directSize == 0L -> resultWithFinding(
                game = game,
                code = CODE_EXECUTABLE_EMPTY,
                severity = IntegritySeverity.Error,
                title = "Executável remoto vazio",
                evidence = "$executablePath respondeu SIZE=0.",
                action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                message = "O executável existe, mas está vazio.",
            )

            listing.isNotEmpty() -> resultWithFinding(
                game = game,
                code = CODE_EXECUTABLE_MISSING,
                severity = IntegritySeverity.Error,
                title = "Executável não encontrado no Xbox",
                evidence = "O diretório $directory retornou ${listing.size} entrada(s), mas $executable não apareceu no LIST nem respondeu a SIZE.",
                action = "Confirme a pasta de instalação e a integridade da cópia antes de alterar o catálogo do Aurora.",
                message = "O diretório responde com conteúdo, mas o executável catalogado está ausente.",
            )

            else -> resultWithFinding(
                game = game,
                code = CODE_EMPTY_OR_UNPARSED_LIST,
                severity = IntegritySeverity.Info,
                title = "Listagem remota inconclusiva",
                evidence = "LIST de $directory não produziu entradas interpretáveis e SIZE não confirmou $executable.",
                action = "Tente novamente por outra rota FTP antes de concluir que o executável está ausente.",
                message = "A pasta respondeu sem entradas interpretáveis; a existência do executável ficou inconclusiva.",
            )
        }
    }

    private suspend fun verifyBySizeAfterListFailure(
        session: XboxFtpSession,
        game: GameEntry,
        executablePath: String,
        listError: Throwable,
    ): RemoteIntegrityProbeResult {
        val directSize = safeSize(session, executablePath)
        return when {
            directSize != null && directSize > 0L -> RemoteIntegrityProbeResult(
                findings = emptyList(),
                verified = true,
                message = "LIST não respondeu, mas o executável foi confirmado por SIZE ($directSize bytes).",
            )

            directSize == 0L -> resultWithFinding(
                game = game,
                code = CODE_EXECUTABLE_EMPTY,
                severity = IntegritySeverity.Error,
                title = "Executável remoto vazio",
                evidence = "$executablePath respondeu SIZE=0 após falha no LIST.",
                action = "Compare o arquivo com a origem antes de substituir qualquer conteúdo no Xbox.",
                message = "O executável existe, mas está vazio.",
            )

            else -> resultWithFinding(
                game = game,
                code = CODE_DIRECTORY_UNREADABLE,
                severity = IntegritySeverity.Info,
                title = "Verificação remota inconclusiva",
                evidence = "LIST falhou em ${game.canonicalDirectory}: ${safeError(listError)}; SIZE também não confirmou o executável.",
                action = "Verifique conexão/rota FTP e tente novamente. Não trate esta falha de transporte como problema do jogo.",
                message = "A verificação remota ficou inconclusiva por falha de acesso ao diretório.",
            )
        }
    }

    private suspend fun safeSize(session: XboxFtpSession, path: String): Long? = try {
        session.size(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

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
        const val CODE_EMPTY_OR_UNPARSED_LIST = "remote.directory.empty_or_unparsed"
        const val CODE_EXECUTABLE_MISSING = "remote.executable.missing"
        const val CODE_EXECUTABLE_EMPTY = "remote.executable.empty"
        const val CODE_EXECUTABLE_IS_DIRECTORY = "remote.executable.is_directory"
    }
}
