package com.jhony4lves.echo360.domain.library

data class HomeCollectionModel(
    val continueGame: GameEntry?,
    val recentGames: List<GameEntry>,
    val favoriteGames: List<GameEntry>,
    val totalGames: Int,
    val favoriteCount: Int,
)

fun buildHomeCollectionModel(
    games: List<GameEntry>,
    states: Map<String, PlayerGameState>,
    liveGame: GameEntry? = null,
    railLimit: Int = 12,
): HomeCollectionModel {
    require(railLimit > 0) { "railLimit must be positive." }

    val recent = games
        .asSequence()
        .filter { (states[it.stableKey]?.lastPlayedAt ?: 0L) > 0L }
        .sortedWith(
            compareByDescending<GameEntry> { states[it.stableKey]?.lastPlayedAt ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        .take(railLimit)
        .toList()

    val favorites = games
        .asSequence()
        .filter { states[it.stableKey]?.favorite == true }
        .sortedWith(
            compareByDescending<GameEntry> { states[it.stableKey]?.lastPlayedAt ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        .take(railLimit)
        .toList()

    return HomeCollectionModel(
        continueGame = liveGame ?: recent.firstOrNull(),
        recentGames = recent,
        favoriteGames = favorites,
        totalGames = games.size,
        favoriteCount = games.count { states[it.stableKey]?.favorite == true },
    )
}
