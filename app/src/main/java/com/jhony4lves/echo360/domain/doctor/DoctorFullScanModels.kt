package com.jhony4lves.echo360.domain.doctor

enum class DoctorScanComponent {
    DashLaunch,
    Telemetry,
    Storage,
}

enum class DoctorScanAvailability {
    Available,
    Partial,
    Unavailable,
}

data class DoctorScanComponentSummary(
    val component: DoctorScanComponent,
    val availability: DoctorScanAvailability,
    val errors: Int,
    val warnings: Int,
    val info: Int,
    val detail: String,
) {
    val healthIssueCount: Int get() = errors + warnings
    val hasHealthIssue: Boolean get() = healthIssueCount > 0
}

data class DoctorFullScanReport(
    val components: List<DoctorScanComponentSummary>,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
) {
    val errors: Int get() = components.sumOf(DoctorScanComponentSummary::errors)
    val warnings: Int get() = components.sumOf(DoctorScanComponentSummary::warnings)
    val info: Int get() = components.sumOf(DoctorScanComponentSummary::info)

    val unavailableCount: Int
        get() = components.count { it.availability == DoctorScanAvailability.Unavailable }

    val partialCount: Int
        get() = components.count { it.availability == DoctorScanAvailability.Partial }

    /** Health and source availability are intentionally separate dimensions. */
    val healthy: Boolean get() = errors == 0 && warnings == 0

    val complete: Boolean get() = unavailableCount == 0 && partialCount == 0

    val durationMs: Long
        get() = (completedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
}
