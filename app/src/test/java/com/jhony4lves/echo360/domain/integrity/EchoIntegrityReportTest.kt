package com.jhony4lves.echo360.domain.integrity

import com.jhony4lves.echo360.network.ftp.FtpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoIntegrityReportTest {
    @Test
    fun `summary counts severities and reports health`() {
        val report = EchoIntegrityReport(
            findings = listOf(
                finding("warn", IntegritySeverity.Warning),
                finding("error", IntegritySeverity.Error),
                finding("info", IntegritySeverity.Info),
            ),
            checkedAtEpochMs = 1L,
        )

        assertEquals(1, report.errorCount)
        assertEquals(1, report.warningCount)
        assertEquals(1, report.infoCount)
        assertEquals(IntegritySeverity.Error, report.highestSeverity)
        assertFalse(report.healthy)
    }

    @Test
    fun `empty report is healthy`() {
        val report = EchoIntegrityReport(emptyList(), checkedAtEpochMs = 1L)

        assertTrue(report.healthy)
        assertEquals(IntegritySeverity.Info, report.highestSeverity)
    }

    @Test
    fun `remote merge replaces previous remote findings and preserves snapshot evidence`() {
        val snapshot = finding("snapshot", IntegritySeverity.Warning, IntegritySource.Snapshot)
        val staleRemote = finding("old", IntegritySeverity.Error, IntegritySource.Remote)
        val newRemote = finding("new", IntegritySeverity.Info, IntegritySource.Remote)
        val initial = EchoIntegrityReport(
            findings = listOf(snapshot, staleRemote),
            checkedAtEpochMs = 1L,
            remoteAttempted = true,
        )

        val merged = initial.mergeRemote(
            remoteFindings = listOf(newRemote),
            verified = true,
            route = FtpRoute.Fast,
            message = "ok",
            checkedAtEpochMs = 2L,
        )

        assertEquals(setOf("snapshot", "new"), merged.findings.map { it.code }.toSet())
        assertTrue(merged.remoteAttempted)
        assertTrue(merged.remoteVerified)
        assertEquals(FtpRoute.Fast, merged.route)
        assertEquals("ok", merged.remoteMessage)
        assertEquals(2L, merged.checkedAtEpochMs)
    }

    private fun finding(
        code: String,
        severity: IntegritySeverity,
        source: IntegritySource = IntegritySource.Snapshot,
    ) = IntegrityFinding(
        code = code,
        severity = severity,
        source = source,
        title = code,
        evidence = "evidence",
        suggestedAction = "action",
    )
}
