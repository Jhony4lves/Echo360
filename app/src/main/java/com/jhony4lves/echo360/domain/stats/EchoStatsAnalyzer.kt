package com.jhony4lves.echo360.domain.stats

import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.library.PlaySessionLedger

/**
 * Pure analytics over the conservative foreground observation ledger.
 *
 * The ledger intentionally retains a bounded number of completed sessions, so
 * these metrics describe retained observed history rather than pretending to be
 * platform-authoritative lifetime Xbox telemetry.
 */
class EchoStatsAnalyzer(
    private val recentLimit: Int = 10,
) {
    init {
        require(recentLimit >= 0) { "recentLimit não pode ser negativo." }
    }

    fun analyze(ledger: PlaySessionLedger): EchoStatsSnapshot {
        val sessions = buildList {
            ledger.active?.let(::add)
            addAll(ledger.recent)
        }
            .distinctBy(PlaySession::id)
            .sortedByDescending(PlaySession::lastSeenAtEpochMs)

        val totalObservedMs = sessions.fold(0L) { total, session ->
            saturatingAdd(total, session.durationMs)
        }
        val gameStats = sessions
            .groupBy(PlaySession::titleId)
            .map { (titleId, gameSessions) ->
                val ordered = gameSessions.sortedByDescending(PlaySession::lastSeenAtEpochMs)
                EchoGameStats(
                    titleId = titleId,
                    title = ordered.firstOrNull()?.title.orEmpty().ifBlank {
                        titleId.toUInt().toString(16).uppercase().padStart(8, '0')
                    },
                    totalObservedMs = gameSessions.fold(0L) { total, session ->
                        saturatingAdd(total, session.durationMs)
                    },
                    sessionCount = gameSessions.size,
                    lastObservedAtEpochMs = gameSessions.maxOfOrNull(PlaySession::lastSeenAtEpochMs) ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<EchoGameStats> { it.totalObservedMs }
                    .thenByDescending { it.lastObservedAtEpochMs }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )

        return EchoStatsSnapshot(
            totalObservedMs = totalObservedMs,
            sessionCount = sessions.size,
            distinctGames = gameStats.size,
            averageSessionMs = if (sessions.isEmpty()) 0L else totalObservedMs / sessions.size,
            longestSession = sessions.maxWithOrNull(
                compareBy<PlaySession> { it.durationMs }
                    .thenBy { it.lastSeenAtEpochMs },
            ),
            mostPlayedGame = gameStats.firstOrNull(),
            games = gameStats,
            recentSessions = sessions.take(recentLimit),
            activeSession = ledger.active,
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - left < safeRight) Long.MAX_VALUE else left + safeRight
    }
}
