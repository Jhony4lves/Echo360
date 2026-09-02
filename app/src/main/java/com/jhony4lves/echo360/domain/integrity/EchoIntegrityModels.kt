package com.jhony4lves.echo360.domain.integrity

import com.jhony4lves.echo360.network.ftp.FtpRoute

enum class IntegritySeverity(val rank: Int) {
    Info(0),
    Warning(1),
    Error(2),
}

enum class IntegritySource {
    Snapshot,
    Remote,
}

data class IntegrityFinding(
    val code: String,
    val severity: IntegritySeverity,
    val source: IntegritySource,
    val title: String,
    val evidence: String,
    val suggestedAction: String,
    val gameStableKey: String? = null,
)

data class EchoIntegrityReport(
    val findings: List<IntegrityFinding>,
    val checkedAtEpochMs: Long,
    val remoteAttempted: Boolean = false,
    val remoteVerified: Boolean = false,
    val route: FtpRoute? = null,
    val remoteMessage: String? = null,
) {
    val errorCount: Int
        get() = findings.count { it.severity == IntegritySeverity.Error }

    val warningCount: Int
        get() = findings.count { it.severity == IntegritySeverity.Warning }

    val infoCount: Int
        get() = findings.count { it.severity == IntegritySeverity.Info }

    val highestSeverity: IntegritySeverity
        get() = findings.maxByOrNull { it.severity.rank }?.severity ?: IntegritySeverity.Info

    val healthy: Boolean
        get() = errorCount == 0 && warningCount == 0

    fun mergeRemote(
        remoteFindings: List<IntegrityFinding>,
        verified: Boolean,
        route: FtpRoute?,
        message: String?,
        checkedAtEpochMs: Long,
    ): EchoIntegrityReport = copy(
        findings = (findings.filterNot { it.source == IntegritySource.Remote } + remoteFindings)
            .distinctBy { finding -> "${finding.source}:${finding.code}:${finding.gameStableKey}:${finding.evidence}" }
            .sortedWith(
                compareByDescending<IntegrityFinding> { it.severity.rank }
                    .thenBy { it.code }
                    .thenBy { it.gameStableKey.orEmpty() },
            ),
        checkedAtEpochMs = checkedAtEpochMs,
        remoteAttempted = true,
        remoteVerified = verified,
        route = route,
        remoteMessage = message,
    )
}
