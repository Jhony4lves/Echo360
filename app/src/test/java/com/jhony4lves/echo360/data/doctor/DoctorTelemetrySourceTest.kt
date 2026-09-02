package com.jhony4lves.echo360.data.doctor

import com.jhony4lves.echo360.domain.doctor.DoctorMemorySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryUnavailable
import com.jhony4lves.echo360.domain.doctor.DoctorTemperatureSnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTemperatureUnit
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorTelemetrySourceTest {
    @Test
    fun `partial EchoCore-style read preserves valid memory and unavailable temperature`() = runBlocking {
        val unavailable = DoctorTelemetryUnavailable(
            component = DoctorTelemetryComponent.Temperature,
            detail = "native thermal read unavailable",
        )
        val coordinator = DoctorTelemetryCoordinator(
            source = FakeSource(
                DoctorTelemetrySourceRead(
                    origin = DoctorTelemetryOrigin.EchoCore,
                    memory = DoctorMemorySnapshot(
                        freeBytes = 100L,
                        usedBytes = 200L,
                        totalBytes = 300L,
                    ),
                    temperature = null,
                    unavailable = listOf(unavailable, unavailable),
                ),
            ),
        )

        val report = coordinator.inspect(XboxProfile(), checkedAtEpochMs = 123L)

        assertEquals(DoctorTelemetryOrigin.EchoCore, report.snapshot.origin)
        assertEquals(100L, report.snapshot.memory?.freeBytes)
        assertNull(report.snapshot.temperature)
        assertEquals(1, report.snapshot.unavailable.size)
        assertEquals(DoctorTelemetryComponent.Temperature, report.snapshot.unavailable.single().component)
        assertEquals(123L, report.snapshot.checkedAtEpochMs)
        assertTrue(report.findings.isEmpty())
        assertTrue(report.healthy)
    }

    @Test
    fun `source metadata still passes through evidence analyzer`() = runBlocking {
        val coordinator = DoctorTelemetryCoordinator(
            source = FakeSource(
                DoctorTelemetrySourceRead(
                    origin = DoctorTelemetryOrigin.EchoCore,
                    memory = DoctorMemorySnapshot(
                        freeBytes = 200L,
                        usedBytes = 200L,
                        totalBytes = 300L,
                    ),
                    temperature = DoctorTemperatureSnapshot(
                        cpu = 55.0,
                        gpu = 60.0,
                        memory = 50.0,
                        case = 45.0,
                        reportedUnit = DoctorTemperatureUnit.Celsius,
                    ),
                ),
            ),
        )

        val report = coordinator.inspect(XboxProfile())

        assertTrue(report.findings.any { it.code == "telemetry.memory.sum_exceeds_total" })
    }

    @Test
    fun `source cancellation is propagated`() {
        val coordinator = DoctorTelemetryCoordinator(
            source = object : DoctorTelemetrySource {
                override suspend fun read(profile: XboxProfile): DoctorTelemetrySourceRead {
                    throw CancellationException("cancel")
                }
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { coordinator.inspect(XboxProfile()) }
        }
    }

    private class FakeSource(
        private val read: DoctorTelemetrySourceRead,
    ) : DoctorTelemetrySource {
        override suspend fun read(profile: XboxProfile): DoctorTelemetrySourceRead = read
    }
}
