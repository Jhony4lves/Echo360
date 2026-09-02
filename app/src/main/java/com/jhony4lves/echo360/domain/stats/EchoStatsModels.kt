package com.jhony4lves.echo360.domain.stats

import com.jhony4lves.echo360.domain.library.PlaySession

data class EchoGameStats(
    val titleId: Long,
    val title: String,
    val totalObservedMs: Long,
    val sessionCount: Int,
    val lastObservedAtEpochMs: Long,
) {
    val averageSessionMs: Long
        get() = if (sessionCount <= 0) 0L else totalObservedMs / sessionCount
}

data class EchoStatsSnapshot(
    val totalObservedMs: Long,
    val sessionCount: Int,
    val distinctGames: Int,
    val averageSessionMs: Long,
    val longestSession: PlaySession?,
    val mostPlayedGame: EchoGameStats?,
    val games: List<EchoGameStats>,
    val recentSessions: List<PlaySession>,
    val activeSession: PlaySession?,
) {
    val hasData: Boolean
        get() = sessionCount > 0
}
