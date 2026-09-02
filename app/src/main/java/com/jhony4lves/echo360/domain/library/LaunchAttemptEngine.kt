package com.jhony4lves.echo360.domain.library

class LaunchAttemptEngine(
    private val confirmWindowMs: Long,
    private val maxAttempts: Int,
) {
    init {
        require(confirmWindowMs > 0L) { "confirmWindowMs deve ser positivo." }
        require(maxAttempts > 0) { "maxAttempts deve ser positivo." }
    }

    fun prepend(
        ledger: LaunchAttemptLedger,
        attempt: LaunchAttempt,
    ): LaunchAttemptLedger = LaunchAttemptLedger(
        attempts = (listOf(attempt) + ledger.attempts)
            .distinctBy(LaunchAttempt::id)
            .sortedByDescending(LaunchAttempt::requestedAtEpochMs)
            .take(maxAttempts),
    )

    fun markAccepted(
        ledger: LaunchAttemptLedger,
        id: String,
        atEpochMs: Long,
    ): LaunchAttemptLedger = transform(ledger, id) { current ->
        if (current.status == LaunchAttemptStatus.Rejected || current.status == LaunchAttemptStatus.Confirmed) {
            current
        } else {
            current.copy(
                acceptedAtEpochMs = atEpochMs.coerceAtLeast(current.requestedAtEpochMs),
                rejectedAtEpochMs = null,
                rejectionReason = null,
            )
        }
    }

    fun markRejected(
        ledger: LaunchAttemptLedger,
        id: String,
        reason: String?,
        atEpochMs: Long,
    ): LaunchAttemptLedger = transform(ledger, id) { current ->
        if (current.status == LaunchAttemptStatus.Confirmed) {
            current
        } else {
            current.copy(
                rejectedAtEpochMs = atEpochMs.coerceAtLeast(current.requestedAtEpochMs),
                rejectionReason = reason,
                confirmedAtEpochMs = null,
            )
        }
    }

    fun confirmObserved(
        ledger: LaunchAttemptLedger,
        titleId: Long,
        observedAtEpochMs: Long,
    ): LaunchAttemptLedger {
        val candidate = ledger.attempts.firstOrNull { attempt ->
            val acceptedAt = attempt.acceptedAtEpochMs
            attempt.titleId == titleId &&
                attempt.status == LaunchAttemptStatus.Accepted &&
                acceptedAt != null &&
                observedAtEpochMs >= acceptedAt &&
                observedAtEpochMs - acceptedAt <= confirmWindowMs
        } ?: return ledger

        return transform(ledger, candidate.id) { current ->
            current.copy(
                confirmedAtEpochMs = observedAtEpochMs.coerceAtLeast(
                    current.acceptedAtEpochMs ?: current.requestedAtEpochMs,
                ),
            )
        }
    }

    private fun transform(
        ledger: LaunchAttemptLedger,
        id: String,
        block: (LaunchAttempt) -> LaunchAttempt,
    ): LaunchAttemptLedger {
        var changed = false
        val updated = ledger.attempts.map { current ->
            if (current.id != id) current
            else block(current).also { next -> changed = changed || next != current }
        }
        return if (!changed) ledger else LaunchAttemptLedger(
            attempts = updated
                .sortedByDescending(LaunchAttempt::requestedAtEpochMs)
                .take(maxAttempts),
        )
    }
}
