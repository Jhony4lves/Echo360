package com.jhony4lves.echo360.data.doctor

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only telemetry entry point for EchoDoctor.
 *
 * The repository no longer knows whether telemetry came from two NOVA endpoints
 * or a future single EchoCore payload. It only loads the configured Xbox profile
 * and delegates to a source-agnostic coordinator.
 */
class DoctorTelemetryRepository internal constructor(
    context: Context,
    private val coordinator: DoctorTelemetryCoordinator,
) {
    constructor(context: Context) : this(
        context = context,
        coordinator = DoctorTelemetryCoordinator(NovaDoctorTelemetrySource()),
    )

    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun inspect(): DoctorTelemetryReport = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de ler a telemetria.")
        coordinator.inspect(profile)
    }
}
