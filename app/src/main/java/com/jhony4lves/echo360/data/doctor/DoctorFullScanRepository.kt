package com.jhony4lves.echo360.data.doctor

import android.content.Context
import com.jhony4lves.echo360.domain.doctor.DashLaunchDoctorReport
import com.jhony4lves.echo360.domain.doctor.DoctorFullScanReport
import com.jhony4lves.echo360.domain.doctor.DoctorScanAvailability
import com.jhony4lves.echo360.domain.doctor.DoctorScanComponent
import com.jhony4lves.echo360.domain.doctor.DoctorScanComponentSummary
import com.jhony4lves.echo360.domain.doctor.DoctorStorageOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorStorageReport
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DoctorFullScanRepository(
    context: Context,
    private val dashLaunch: DashLaunchDoctorRepository = DashLaunchDoctorRepository(context.applicationContext),
    private val telemetry: DoctorTelemetryRepository = DoctorTelemetryRepository(context.applicationContext),
    private val storage: DoctorStorageRepository = DoctorStorageRepository(context.applicationContext),
) {
    suspend fun inspect(): DoctorFullScanReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val components = listOf(
            isolated(DoctorScanComponent.DashLaunch) {
                DoctorFullScanSummaryMapper.dashLaunch(dashLaunch.inspect())
            },
            isolated(DoctorScanComponent.Telemetry) {
                DoctorFullScanSummaryMapper.telemetry(telemetry.inspect())
            },
            isolated(DoctorScanComponent.Storage) {
                DoctorFullScanSummaryMapper.storage(storage.inspect())
            },
        )
        DoctorFullScanReport(
            components = components,
            startedAtEpochMs = startedAt,
            completedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun isolated(
        component: DoctorScanComponent,
        read: suspend () -> DoctorScanComponentSummary,
    ): DoctorScanComponentSummary = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        DoctorFullScanSummaryMapper.failure(component, error)
    }
}

internal object DoctorFullScanSummaryMapper {
    fun dashLaunch(report: DashLaunchDoctorReport) = DoctorScanComponentSummary(
        component = DoctorScanComponent.DashLaunch,
        availability = DoctorScanAvailability.Available,
        errors = report.errors,
        warnings = report.warnings,
        info = report.info,
        detail = "DashLaunch v${report.snapshot.version.display} • kernel ${report.snapshot.version.kernel}",
    )

    fun telemetry(report: DoctorTelemetryReport): DoctorScanComponentSummary {
        val snapshot = report.snapshot
        val hasMemory = snapshot.memory != null
        val hasTemperature = snapshot.temperature != null
        val hasData = hasMemory || hasTemperature
        val availability = when {
            !hasData -> DoctorScanAvailability.Unavailable
            snapshot.unavailable.isNotEmpty() -> DoctorScanAvailability.Partial
            else -> DoctorScanAvailability.Available
        }
        val source = when (snapshot.origin) {
            DoctorTelemetryOrigin.NovaCompatibility -> "NOVA"
            DoctorTelemetryOrigin.EchoCore -> "EchoCore"
        }
        val components = buildList {
            if (hasMemory) add("RAM")
            if (hasTemperature) add("THERMALS")
        }.joinToString(" + ").ifBlank { "sem dados" }

        return DoctorScanComponentSummary(
            component = DoctorScanComponent.Telemetry,
            availability = availability,
            errors = report.errors,
            warnings = report.warnings,
            info = report.info,
            detail = "$source • $components" +
                snapshot.unavailable.takeIf { it.isNotEmpty() }?.let { " • ${it.size} fonte(s) parcial(is)" }.orEmpty(),
        )
    }

    fun storage(report: DoctorStorageReport): DoctorScanComponentSummary {
        val snapshot = report.snapshot
        val partialMounts = snapshot.mounts.count { it.listingUnavailableDetail != null }
        val availability = when {
            snapshot.unavailableDetail != null -> DoctorScanAvailability.Unavailable
            partialMounts > 0 -> DoctorScanAvailability.Partial
            else -> DoctorScanAvailability.Available
        }
        val source = when (snapshot.origin) {
            DoctorStorageOrigin.AuroraFtpCompatibility -> "Aurora FTP"
            DoctorStorageOrigin.FtpDllCompatibility -> "FTPdll"
            DoctorStorageOrigin.EchoCore -> "EchoCore"
            DoctorStorageOrigin.Unavailable -> "indisponível"
        }
        val detail = when {
            snapshot.unavailableDetail != null -> "$source • ${snapshot.unavailableDetail}"
            partialMounts > 0 -> "$source • ${snapshot.mounts.size} mount(s) • $partialMounts listagem(ns) parcial(is)"
            else -> "$source • ${snapshot.mounts.size} mount(s) • ${snapshot.rootEntryCount} entrada(s) na raiz"
        }

        return DoctorScanComponentSummary(
            component = DoctorScanComponent.Storage,
            availability = availability,
            errors = report.errors,
            warnings = report.warnings,
            info = report.info,
            detail = detail,
        )
    }

    fun failure(component: DoctorScanComponent, error: Throwable) = DoctorScanComponentSummary(
        component = component,
        availability = DoctorScanAvailability.Unavailable,
        errors = 0,
        warnings = 0,
        info = 0,
        detail = "Fonte indisponível: ${safeError(error)}",
    )

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(180)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "erro de leitura" }
}
