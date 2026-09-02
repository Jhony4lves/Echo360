package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.EchoIntegrityReport

enum class LaunchReadinessStatus {
    Ready,
    NeedsVerification,
    Caution,
    Blocked,
}

data class LaunchReadinessReport(
    val status: LaunchReadinessStatus,
    val integrity: EchoIntegrityReport,
    val summary: String,
) {
    val canRecommendLaunch: Boolean get() = status == LaunchReadinessStatus.Ready
}

/**
 * Conservative advisory derived only from EchoIntegrity evidence.
 *
 * READY requires a successful remote executable verification and no warnings or
 * errors. Snapshot-only evidence can never become READY. This deliberately does
 * not attempt to prove runtime stability, plugin compatibility or future crash
 * behavior beyond the checks that EchoIntegrity actually performed.
 */
class LaunchReadinessAnalyzer {
    fun analyze(integrity: EchoIntegrityReport): LaunchReadinessReport {
        val status = when {
            integrity.errorCount > 0 -> LaunchReadinessStatus.Blocked
            integrity.warningCount > 0 -> LaunchReadinessStatus.Caution
            integrity.remoteVerified -> LaunchReadinessStatus.Ready
            else -> LaunchReadinessStatus.NeedsVerification
        }

        val summary = when (status) {
            LaunchReadinessStatus.Ready ->
                "Executável remoto verificado e nenhuma anomalia objetiva foi encontrada pelas checagens atuais."

            LaunchReadinessStatus.NeedsVerification ->
                "O snapshot local não encontrou bloqueio, mas falta confirmação remota do executável."

            LaunchReadinessStatus.Caution ->
                "Há aviso(s) objetivo(s) que merecem revisão antes do launch."

            LaunchReadinessStatus.Blocked ->
                "Há erro(s) objetivo(s) nas checagens atuais; o Echo não recomenda o launch até revisar a evidência."
        }

        return LaunchReadinessReport(
            status = status,
            integrity = integrity,
            summary = summary,
        )
    }
}
