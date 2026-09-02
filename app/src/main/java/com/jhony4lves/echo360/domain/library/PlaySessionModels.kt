package com.jhony4lves.echo360.domain.library

data class PlayObservation(
    val stableKey: String,
    val titleId: Long,
    val mediaId: Long,
    val title: String,
    val observedAtEpochMs: Long,
)

data class PlaySession(
    val id: String,
    val stableKey: String,
    val titleId: Long,
    val mediaId: Long,
    val title: String,
    val startedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val observationCount: Int = 1,
) {
    val active: Boolean
        get() = endedAtEpochMs == null

    val durationMs: Long
        get() = ((endedAtEpochMs ?: lastSeenAtEpochMs) - startedAtEpochMs).coerceAtLeast(0L)
}

data class PlaySessionLedger(
    val active: PlaySession? = null,
    val recent: List<PlaySession> = emptyList(),
)

data class PlaytimeSummary(
    val stableKey: String,
    val totalObservedMs: Long,
    val sessionCount: Int,
    val lastObservedAtEpochMs: Long,
    val recentSessions: List<PlaySession>,
    val activeSession: PlaySession?,
)

/**
 * Conservative playtime accounting from NOVA observations.
 *
 * We count time between consecutive observations of the same game only while
 * the gap stays within [maxContinuousGapMs]. Large gaps are split instead of
 * being interpreted as playtime. A game switch/non-game observation closes the
 * previous session at its last confirmed observation, so uncertain tail time is
 * never added silently.
 */
class PlaySessionEngine(
    private val maxRecentSessions: Int = 200,
    private val maxContinuousGapMs: Long = 3 * 60_000L,
) {
    init {
        require(maxRecentSessions > 0) { "maxRecentSessions deve ser positivo." }
        require(maxContinuousGapMs > 0L) { "maxContinuousGapMs deve ser positivo." }
    }

    fun observe(
        ledger: PlaySessionLedger,
        observation: PlayObservation,
    ): PlaySessionLedger {
        require(observation.stableKey.isNotBlank()) { "stableKey não pode ser vazio." }
        require(observation.observedAtEpochMs >= 0L) { "Timestamp de observação inválido." }

        val active = ledger.active
            ?: return ledger.copy(active = startSession(observation))

        if (observation.observedAtEpochMs < active.lastSeenAtEpochMs) {
            return ledger
        }

        val sameGame = active.stableKey == observation.stableKey
        val gap = observation.observedAtEpochMs - active.lastSeenAtEpochMs
        if (sameGame && gap <= maxContinuousGapMs) {
            return ledger.copy(
                active = active.copy(
                    titleId = observation.titleId,
                    mediaId = observation.mediaId,
                    title = observation.title,
                    lastSeenAtEpochMs = observation.observedAtEpochMs,
                    observationCount = active.observationCount + 1,
                ),
            )
        }

        val closed = closeAtLastConfirmed(active)
        return PlaySessionLedger(
            active = startSession(observation),
            recent = prependCompleted(closed, ledger.recent),
        )
    }

    fun observeNonGame(
        ledger: PlaySessionLedger,
        observedAtEpochMs: Long,
    ): PlaySessionLedger {
        require(observedAtEpochMs >= 0L) { "Timestamp de observação inválido." }
        val active = ledger.active ?: return ledger
        if (observedAtEpochMs < active.lastSeenAtEpochMs) return ledger

        return PlaySessionLedger(
            active = null,
            recent = prependCompleted(closeAtLastConfirmed(active), ledger.recent),
        )
    }

    fun summaryFor(
        ledger: PlaySessionLedger,
        stableKey: String,
        recentLimit: Int = 6,
    ): PlaytimeSummary {
        require(recentLimit >= 0) { "recentLimit não pode ser negativo." }
        val completed = ledger.recent.filter { it.stableKey == stableKey }
        val active = ledger.active?.takeIf { it.stableKey == stableKey }
        val totalObservedMs = completed.sumOf(PlaySession::durationMs) + (active?.durationMs ?: 0L)
        val lastObservedAt = sequenceOf(
            completed.maxOfOrNull(PlaySession::lastSeenAtEpochMs) ?: 0L,
            active?.lastSeenAtEpochMs ?: 0L,
        ).maxOrNull() ?: 0L

        return PlaytimeSummary(
            stableKey = stableKey,
            totalObservedMs = totalObservedMs,
            sessionCount = completed.size + if (active != null) 1 else 0,
            lastObservedAtEpochMs = lastObservedAt,
            recentSessions = buildList {
                active?.let(::add)
                addAll(completed)
            }.sortedByDescending { it.lastSeenAtEpochMs }.take(recentLimit),
            activeSession = active,
        )
    }

    private fun startSession(observation: PlayObservation): PlaySession = PlaySession(
        id = "${observation.stableKey}:${observation.observedAtEpochMs}",
        stableKey = observation.stableKey,
        titleId = observation.titleId,
        mediaId = observation.mediaId,
        title = observation.title,
        startedAtEpochMs = observation.observedAtEpochMs,
        lastSeenAtEpochMs = observation.observedAtEpochMs,
        observationCount = 1,
    )

    private fun closeAtLastConfirmed(session: PlaySession): PlaySession = session.copy(
        endedAtEpochMs = session.lastSeenAtEpochMs,
    )

    private fun prependCompleted(
        session: PlaySession,
        recent: List<PlaySession>,
    ): List<PlaySession> = buildList {
        add(session)
        addAll(recent.filterNot { it.id == session.id })
    }.take(maxRecentSessions)
}
