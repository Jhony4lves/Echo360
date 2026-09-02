package com.jhony4lves.echo360.data.doctor

import com.jhony4lves.echo360.domain.doctor.DashLaunchDoctorReport
import com.jhony4lves.echo360.domain.doctor.DashLaunchSnapshot
import com.jhony4lves.echo360.domain.doctor.DashLaunchVersion
import com.jhony4lves.echo360.domain.doctor.DoctorFullScanReport
import com.jhony4lves.echo360.domain.doctor.DoctorMemorySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorScanAvailability
import com.jhony4lves.echo360.domain.doctor.DoctorScanComponent
import com.jhony4lves.echo360.domain.doctor.DoctorStorageOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorStorageReport
import com.jhony4lves.echo360.domain.doctor.DoctorStorageSnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetrySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryUnavailable
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorFullScanSummaryMapperTest {
    @Test
    fun `partial telemetry remains partial without becoming health failure`() {
        val report = DoctorTelemetryReport(
            snapshot = DoctorTelemetrySnapshot(
                origin = DoctorTelemetryOrigin.EchoCore,
                memory = DoctorMemorySnapshot(100L, 200L, 300L),
                temperature = null,
                unavailable = listOf(
                    DoctorTelemetryUnavailable(
                        DoctorTelemetryComponent.Temperature,
                        "thermal unavailable",
                    ),
                ),
                checkedAtEpochMs = 1L,
            ),
            findings = emptyList(),
        )

        val summary = DoctorFullScanSummaryMapper.telemetry(report)

        assertEquals(DoctorScanAvailability.Partial, summary.availability)
        assertEquals(0, summary.healthIssueCount)
        assertTrue(summary.detail.contains("EchoCore"))
        assertTrue(summary.detail.contains("RAM"))
    }

    @Test
    fun `storage transport unavailable is not counted as health finding`() {
        val report = DoctorStorageReport(
            snapshot = DoctorStorageSnapshot(
                origin = DoctorStorageOrigin.Unavailable,
                mounts = emptyList(),
                rootEntryCount = 0,
                rootLimitReached = false,
                unavailableDetail = "timeout",
                checkedAtEpochMs = 1L,
            ),
            findings = emptyList(),
        )

        val summary = DoctorFullScanSummaryMapper.storage(report)

        assertEquals(DoctorScanAvailability.Unavailable, summary.availability)
        assertEquals(0, summary.errors)
        assertEquals(0, summary.warnings)
    }

    @Test
    fun `real DashLaunch finding remains a health warning`() {
        val report = DashLaunchDoctorReport(
            snapshot = DashLaunchSnapshot(
                options = emptyList(),
                version = DashLaunchVersion(kernel = 17559L, major = 3L, minor = 21L, build = 0L),
            ),
            findings = listOf(warning()),
            checkedAtEpochMs = 1L,
        )

        val summary = DoctorFullScanSummaryMapper.dashLaunch(report)

        assertEquals(DoctorScanAvailability.Available, summary.availability)
        assertEquals(1, summary.warnings)
        assertTrue(summary.hasHealthIssue)
    }

    @Test
    fun `full scan can be healthy while incomplete`() {
        val unavailable = DoctorFullScanSummaryMapper.failure(
            DoctorScanComponent.Storage,
            IllegalStateException("offline"),
        )
        val report = DoctorFullScanReport(
            components = listOf(unavailable),
            startedAtEpochMs = 10L,
            completedAtEpochMs = 25L,
        )

        assertTrue(report.healthy)
        assertFalse(report.complete)
        assertEquals(1, report.unavailableCount)
        assertEquals(15L, report.durationMs)
    }

    private fun warning() = IntegrityFinding(
        code = "test.warning",
        severity = IntegritySeverity.Warning,
        source = IntegritySource.Remote,
        title = "warning",
        evidence = "evidence",
        suggestedAction = "retry",
    )
}
