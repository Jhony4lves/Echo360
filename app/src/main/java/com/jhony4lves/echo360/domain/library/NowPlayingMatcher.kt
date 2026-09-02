package com.jhony4lves.echo360.domain.library

/**
 * Resolves NOVA /title output against the cached Aurora catalog without inventing
 * metadata. Exact Title ID + Media ID wins; NOVA mediaId=0 and title-only fallback
 * mirror the player UI behavior already used by EchoHome/EchoLibrary.
 */
fun matchObservedGame(
    games: List<GameEntry>,
    nowPlaying: NowPlaying,
): GameEntry? {
    if (nowPlaying.titleId == 0L) return null

    return games.firstOrNull { game ->
        game.titleId == nowPlaying.titleId &&
            (nowPlaying.mediaId == 0L || game.mediaId == nowPlaying.mediaId)
    } ?: games.firstOrNull { game ->
        game.titleId == nowPlaying.titleId
    }
}
