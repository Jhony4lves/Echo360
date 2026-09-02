package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity

enum class DoctorTelemetryOrigin {
    NovaCompatibility,
    EchoCore,
}

enum class DoctorTemperatureUnit {
    Celsius,
    Fahrenheit,
}

data class DoctorMemorySnapshot(
    val freeBytes: Long,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    val usedFraction: Double?
        get() = totalBytes.takeIf { it > 0L }?.let { total -> usedBytes.toDouble() / total.toDouble() }
}

data class DoctorTemperatureSnapshot(
    val cpu: Double,
    val gpu: Double,
    val memory: Double,
    val case: Double,
    val reportedUnit: DoctorTemperatureUnit,
) {
    val cpuCelsius: Double get() = toCelsius(cpu)
    val gpuCelsius: Double get() = toCelsius(gpu)
    val memoryCelsius: Double get() = toCelsius(memory)
    val caseCelsius: Double get() = toCelsius(case)

    private fun toCelsius(value: Double): Double = when (reportedUnit) {
        DoctorTemperatureUnit.Celsius -> value
        DoctorTemperatureUnit.Fahrenheit -> (value - 32.0) * (5.0 / 9.0)
    }
}

data class DoctorTelemetrySnapshot(
    val origin: DoctorTelemetryOrigin,
    val memory: DoctorMemorySnapshot?,
    val temperature: DoctorTemperatureSnapshot?,
    val unavailable: List<DoctorTelemetryUnavailable> = emptyList(),
    val checkedAtEpochMs: Long,
)

data class DoctorTelemetryUnavailable(
    val component: DoctorTelemetryComponent,
    val detail: String,
)

enum class DoctorTelemetryComponent {
    Memory,
    Temperature,
}

data class DoctorTelemetryReport(
    val snapshot: DoctorTelemetrySnapshot,
    val findings: List<IntegrityFinding>,
) {
    val errors: Int get() = findings.count { it.severity == IntegritySeverity.Error }
    val warnings: Int get() = findings.count { it.severity == IntegritySeverity.Warning }
    val info: Int get() = findings.count { it.severity == IntegritySeverity.Info }

    /** Source availability is intentionally separate from telemetry health. */
    val healthy: Boolean get() = errors == 0 && warnings == 0
}
