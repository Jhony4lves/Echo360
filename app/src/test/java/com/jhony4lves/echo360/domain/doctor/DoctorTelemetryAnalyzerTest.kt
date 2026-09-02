package com.jhony4lves.echo360.domain.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorTelemetryAnalyzerTest {
    private val analyzer = DoctorTelemetryAnalyzer()

    @Test
    fun `normal valid telemetry has no findings`() {
        val report = analyzer.analyze(
            snapshot(
                memory = DoctorMemorySnapshot(
                    freeBytes = 423_395_328L,
                    usedBytes = 113_475_584L,
                    totalBytes = 536_870_912L,
                ),
                temperature = DoctorTemperatureSnapshot(
                    cpu = 61.5,
                    gpu = 59.0,
                    memory = 54.25,
                    case = 42.0,
                    reportedUnit = DoctorTemperatureUnit.Celsius,
                ),
            ),
        )

        assertTrue(report.isEmpty())
    }

    @Test
    fun `fahrenheit readings normalize to celsius`() {
        val temperature = DoctorTemperatureSnapshot(
            cpu = 212.0,
            gpu = 32.0,
            memory = -40.0,
            case = 68.0,
            reportedUnit = DoctorTemperatureUnit.Fahrenheit,
        )

        assertEquals(100.0, temperature.cpuCelsius, 0.0001)
        assertEquals(0.0, temperature.gpuCelsius, 0.0001)
        assertEquals(-40.0, temperature.memoryCelsius, 0.0001)
        assertEquals(20.0, temperature.caseCelsius, 0.0001)
    }

    @Test
    fun `below absolute zero is invalid without defining hot threshold`() {
        val findings = analyzer.analyze(
            snapshot(
                temperature = DoctorTemperatureSnapshot(
                    cpu = -274.0,
                    gpu = 200.0,
                    memory = 50.0,
                    case = 45.0,
                    reportedUnit = DoctorTemperatureUnit.Celsius,
                ),
            ),
        )

        assertEquals(1, findings.size)
        assertEquals(
            DoctorTelemetryAnalyzer.CODE_TEMPERATURE_BELOW_ABSOLUTE_ZERO,
            findings.single().code,
        )
    }

    @Test
    fun `high finite temperature is not guessed as dangerous`() {
        val findings = analyzer.analyze(
            snapshot(
                temperature = DoctorTemperatureSnapshot(
                    cpu = 150.0,
                    gpu = 140.0,
                    memory = 130.0,
                    case = 120.0,
                    reportedUnit = DoctorTemperatureUnit.Celsius,
                ),
            ),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `memory counters greater than total are rejected as inconsistent payload`() {
        val findings = analyzer.analyze(
            snapshot(
                memory = DoctorMemorySnapshot(
                    freeBytes = 600L,
                    usedBytes = 500L,
                    totalBytes = 1_000L,
                ),
            ),
        )

        assertEquals(
            setOf(DoctorTelemetryAnalyzer.CODE_MEMORY_SUM_EXCEEDS_TOTAL),
            findings.map { it.code }.toSet(),
        )
    }

    @Test
    fun `individual memory counter above total is reported`() {
        val findings = analyzer.analyze(
            snapshot(
                memory = DoctorMemorySnapshot(
                    freeBytes = 1_001L,
                    usedBytes = 0L,
                    totalBytes = 1_000L,
                ),
            ),
        )

        assertTrue(findings.any { it.code == DoctorTelemetryAnalyzer.CODE_MEMORY_EXCEEDS_TOTAL })
    }

    @Test
    fun `source availability note does not degrade telemetry health`() {
        val snapshot = DoctorTelemetrySnapshot(
            origin = DoctorTelemetryOrigin.NovaCompatibility,
            memory = null,
            temperature = null,
            unavailable = listOf(
                DoctorTelemetryUnavailable(
                    component = DoctorTelemetryComponent.Temperature,
                    detail = "timeout",
                ),
            ),
            checkedAtEpochMs = 1L,
        )
        val report = DoctorTelemetryReport(snapshot, analyzer.analyze(snapshot))

        assertTrue(report.findings.isEmpty())
        assertTrue(report.healthy)
        assertEquals(1, report.snapshot.unavailable.size)
    }

    private fun snapshot(
        memory: DoctorMemorySnapshot? = null,
        temperature: DoctorTemperatureSnapshot? = null,
    ) = DoctorTelemetrySnapshot(
        origin = DoctorTelemetryOrigin.NovaCompatibility,
        memory = memory,
        temperature = temperature,
        checkedAtEpochMs = 1L,
    )
}
