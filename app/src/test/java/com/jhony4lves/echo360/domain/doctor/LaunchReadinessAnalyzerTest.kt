package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.EchoIntegrityReport
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchReadinessAnalyzerTest {
    private val analyzer = LaunchReadinessAnalyzer()

    @Test
    fun `clear snapshot without remote proof still needs verification`() {
        val report = analyzer.analyze(integrity(remoteVerified = false))

        assertEquals(LaunchReadinessStatus.NeedsVerification, report.status)
        assertFalse(report.canRecommendLaunch)
    }

    @Test
    fun `remote verified clear report is ready`() {
        val report = analyzer.analyze(integrity(remoteVerified = true))

        assertEquals(LaunchReadinessStatus.Ready, report.status)
        assertTrue(report.canRecommendLaunch)
    }

    @Test
    fun `warning prevents ready even with remote executable proof`() {
        val report = analyzer.analyze(
            integrity(
                remoteVerified = true,
                findings = listOf(finding(IntegritySeverity.Warning)),
            ),
        )

        assertEquals(LaunchReadinessStatus.Caution, report.status)
        assertFalse(report.canRecommendLaunch)
    }

    @Test
    fun `objective error blocks launch recommendation`() {
        val report = analyzer.analyze(
            integrity(
                remoteVerified = true,
                findings = listOf(finding(IntegritySeverity.Error)),
            ),
        )

        assertEquals(LaunchReadinessStatus.Blocked, report.status)
        assertFalse(report.canRecommendLaunch)
    }

    @Test
    fun `transport attempt without verification never becomes ready`() {
        val report = analyzer.analyze(
            integrity(
                remoteVerified = false,
                remoteAttempted = true,
                remoteMessage = "timeout",
            ),
        )

        assertEquals(LaunchReadinessStatus.NeedsVerification, report.status)
    }

    private fun integrity(
        remoteVerified: Boolean,
        findings: List<IntegrityFinding> = emptyList(),
        remoteAttempted: Boolean = remoteVerified,
        remoteMessage: String? = null,
    ) = EchoIntegrityReport(
        findings = findings,
        checkedAtEpochMs = 1L,
        remoteAttempted = remoteAttempted,
        remoteVerified = remoteVerified,
        remoteMessage = remoteMessage,
    )

    private fun finding(severity: IntegritySeverity) = IntegrityFinding(
        code = "test.${severity.name.lowercase()}",
        severity = severity,
        source = IntegritySource.Snapshot,
        title = "Test finding",
        evidence = "test evidence",
        suggestedAction = "review",
    )
}
