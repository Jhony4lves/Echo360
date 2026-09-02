package com.jhony4lves.echo360.domain.library

/**
 * Resolves a runtime title observation against the cached Aurora catalog without
 * inventing metadata. Exact Title ID + known Media ID wins; if the source only
 * knows Title ID (for example future EchoCore CURRENT_TITLE), title-only fallback
 * remains valid and conservative.
 */
fun matchObservedGame(
    games: List<GameEntry>,
    observation: CurrentTitleObservation,
): GameEntry? {
    if (observation.titleId == 0L) return null

    val mediaId = observation.mediaId
    return if (mediaId != null) {
        games.firstOrNull { game ->
            game.titleId == observation.titleId && game.mediaId == mediaId
        } ?: games.firstOrNull { game -> game.titleId == observation.titleId }
    } else {
        games.firstOrNull { game -> game.titleId == observation.titleId }
    }
}

/** Backward-compatible helper for callers/tests still holding rich NOVA data. */
fun matchObservedGame(
    games: List<GameEntry>,
    nowPlaying: NowPlaying,
): GameEntry? = matchObservedGame(games, nowPlaying.toCurrentTitleObservation())
