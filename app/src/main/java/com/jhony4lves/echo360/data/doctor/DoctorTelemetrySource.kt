package com.jhony4lves.echo360.data.doctor

import com.jhony4lves.echo360.domain.doctor.DoctorMemorySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryAnalyzer
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetrySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryUnavailable
import com.jhony4lves.echo360.domain.doctor.DoctorTemperatureSnapshot
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.CancellationException

/**
 * One read from any read-only Doctor telemetry provider.
 *
 * Memory and temperature are deliberately independent: a provider may return
 * either component while marking the other unavailable. That matches the
 * candidate EchoCore DOCTOR_TELEMETRY payload and prevents partial transport or
 * ABI failures from erasing valid evidence.
 */
internal data class DoctorTelemetrySourceRead(
    val origin: DoctorTelemetryOrigin,
    val memory: DoctorMemorySnapshot?,
    val temperature: DoctorTemperatureSnapshot?,
    val unavailable: List<DoctorTelemetryUnavailable> = emptyList(),
)

internal interface DoctorTelemetrySource {
    suspend fun read(profile: XboxProfile): DoctorTelemetrySourceRead
}

/** Production compatibility source until EchoCore telemetry is hardware-promoted. */
internal class NovaDoctorTelemetrySource(
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) : DoctorTelemetrySource {
    override suspend fun read(profile: XboxProfile): DoctorTelemetrySourceRead {
        val unavailable = mutableListOf<DoctorTelemetryUnavailable>()

        val memory = try {
            novaClient.doctorMemory(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            unavailable += DoctorTelemetryUnavailable(
                component = DoctorTelemetryComponent.Memory,
                detail = safeError(error),
            )
            null
        }

        val temperature = try {
            novaClient.doctorTemperature(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            unavailable += DoctorTelemetryUnavailable(
                component = DoctorTelemetryComponent.Temperature,
                detail = safeError(error),
            )
            null
        }

        return DoctorTelemetrySourceRead(
            origin = DoctorTelemetryOrigin.NovaCompatibility,
            memory = memory,
            temperature = temperature,
            unavailable = unavailable,
        )
    }
}

internal class DoctorTelemetryCoordinator(
    private val source: DoctorTelemetrySource,
    private val analyzer: DoctorTelemetryAnalyzer = DoctorTelemetryAnalyzer(),
) {
    suspend fun inspect(
        profile: XboxProfile,
        checkedAtEpochMs: Long = System.currentTimeMillis(),
    ): DoctorTelemetryReport {
        val read = source.read(profile)
        val snapshot = DoctorTelemetrySnapshot(
            origin = read.origin,
            memory = read.memory,
            temperature = read.temperature,
            unavailable = read.unavailable
                .distinctBy { "${it.component}:${it.detail}" },
            checkedAtEpochMs = checkedAtEpochMs,
        )
        return DoctorTelemetryReport(
            snapshot = snapshot,
            findings = analyzer.analyze(snapshot),
        )
    }
}

private fun safeError(error: Throwable): String =
    error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(220)?.ifBlank { null }
        ?: error::class.simpleName.orEmpty().ifBlank { "fonte indisponível" }
