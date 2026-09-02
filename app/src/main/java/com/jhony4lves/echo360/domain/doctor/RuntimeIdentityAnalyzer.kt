package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource
import com.jhony4lves.echo360.domain.library.CurrentTitleObservation
import com.jhony4lves.echo360.domain.library.GameEntry

data class RuntimeIdentityReport(
    val selectedTitleId: Long,
    val selectedMediaId: Long?,
    val observation: CurrentTitleObservation?,
    val findings: List<IntegrityFinding>,
) {
    val sameTitle: Boolean
        get() = observation?.titleId == selectedTitleId

    val runtimeMediaId: Long?
        get() = observation?.mediaId

    val titleUpdateVersion: Int?
        get() = observation?.details?.titleUpdateVersion

    val baseVersion: String?
        get() = observation?.details?.baseVersion

    val currentVersion: String?
        get() = observation?.details?.currentVersion

    val mediaComparable: Boolean
        get() = sameTitle && selectedMediaId != null && runtimeMediaId != null

    val mediaMatches: Boolean?
        get() = if (mediaComparable) selectedMediaId == runtimeMediaId else null
}

/**
 * Runtime identity checks that only classify facts the current sources can
 * prove. TU version is surfaced as raw runtime information; this analyzer does
 * not claim a TU is current/outdated without a trusted compatibility source.
 */
class RuntimeIdentityAnalyzer {
    fun analyze(game: GameEntry, observation: CurrentTitleObservation?): RuntimeIdentityReport {
        val selectedMedia = game.mediaId.takeIf { it != 0L }
        val findings = buildList {
            if (observation != null && observation.titleId != game.titleId) {
                add(
                    finding(
                        game = game,
                        code = CODE_OTHER_TITLE_RUNNING,
                        severity = IntegritySeverity.Info,
                        title = "Outro título está em execução",
                        evidence = "Selecionado ${game.titleIdHex}; runtime ${hex(observation.titleId)}.",
                        action = "Inicie este jogo para comparar sua identidade de mídia/TU em tempo real.",
                    ),
                )
            }

            if (observation != null && observation.titleId == game.titleId) {
                val runtimeMedia = observation.mediaId
                if (selectedMedia != null && runtimeMedia != null && selectedMedia != runtimeMedia) {
                    add(
                        finding(
                            game = game,
                            code = CODE_MEDIA_ID_MISMATCH,
                            severity = IntegritySeverity.Warning,
                            title = "Media ID diferente da entrada selecionada",
                            evidence = "Biblioteca ${hex(selectedMedia)}; runtime ${hex(runtimeMedia)} para o mesmo Title ID ${game.titleIdHex}.",
                            action = "Confirme se esta é a mídia/disco/entrada correta antes de aplicar TU ou conteúdo específico de Media ID.",
                        ),
                    )
                }
            }
        }

        return RuntimeIdentityReport(
            selectedTitleId = game.titleId,
            selectedMediaId = selectedMedia,
            observation = observation,
            findings = findings,
        )
    }

    private fun finding(
        game: GameEntry,
        code: String,
        severity: IntegritySeverity,
        title: String,
        evidence: String,
        action: String,
    ) = IntegrityFinding(
        code = code,
        severity = severity,
        source = IntegritySource.Remote,
        title = title,
        evidence = evidence,
        suggestedAction = action,
        gameStableKey = game.stableKey,
    )

    private fun hex(value: Long): String = value.toUInt().toString(16).uppercase().padStart(8, '0')

    companion object {
        const val CODE_OTHER_TITLE_RUNNING = "runtime.identity.other_title"
        const val CODE_MEDIA_ID_MISMATCH = "runtime.identity.media_mismatch"
    }
}
