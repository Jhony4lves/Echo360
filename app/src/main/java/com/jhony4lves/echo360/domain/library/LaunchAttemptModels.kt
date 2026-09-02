package com.jhony4lves.echo360.domain.library

enum class LaunchAttemptStatus {
    Requested,
    Accepted,
    Confirmed,
    Rejected,
}

data class LaunchAttempt(
    val id: String,
    val titleId: Long,
    val mediaId: Long,
    val title: String,
    val requestedAtEpochMs: Long,
    val acceptedAtEpochMs: Long? = null,
    val confirmedAtEpochMs: Long? = null,
    val rejectedAtEpochMs: Long? = null,
    val rejectionReason: String? = null,
) {
    val status: LaunchAttemptStatus
        get() = when {
            rejectedAtEpochMs != null -> LaunchAttemptStatus.Rejected
            confirmedAtEpochMs != null -> LaunchAttemptStatus.Confirmed
            acceptedAtEpochMs != null -> LaunchAttemptStatus.Accepted
            else -> LaunchAttemptStatus.Requested
        }

    val titleIdHex: String get() = titleId.toUInt().toString(16).uppercase().padStart(8, '0')
}

data class LaunchAttemptLedger(
    val attempts: List<LaunchAttempt> = emptyList(),
)
