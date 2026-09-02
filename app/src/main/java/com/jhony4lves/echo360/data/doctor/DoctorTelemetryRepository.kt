package com.jhony4lves.echo360.data.doctor

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryAnalyzer
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetrySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryUnavailable
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NOVA compatibility adapter for the transport-neutral Doctor telemetry model.
 * Each read is independent so one unavailable endpoint does not discard the
 * other component or degrade console health by itself.
 */
class DoctorTelemetryRepository(
    context: Context,
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)
    private val analyzer = DoctorTelemetryAnalyzer()

    suspend fun inspect(): DoctorTelemetryReport = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de ler a telemetria.")
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

        val snapshot = DoctorTelemetrySnapshot(
            origin = DoctorTelemetryOrigin.NovaCompatibility,
            memory = memory,
            temperature = temperature,
            unavailable = unavailable,
            checkedAtEpochMs = System.currentTimeMillis(),
        )
        DoctorTelemetryReport(
            snapshot = snapshot,
            findings = analyzer.analyze(snapshot),
        )
    }

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(220)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "fonte indisponível" }
}
