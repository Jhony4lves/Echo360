package com.jhony4lves.echo360.domain.library

enum class GameStatus(val label: String) {
    None("Sem status"),
    WantToPlay("Quero jogar"),
    Playing("Jogando"),
    Finished("Finalizado"),
}

data class PlayerGameState(
    val favorite: Boolean = false,
    val status: GameStatus = GameStatus.None,
    val lastPlayedAt: Long = 0L,
    val launchCount: Int = 0,
)

enum class LibraryFilter(val label: String) {
    All("Todos"),
    Favorites("Favoritos"),
    Playing("Jogando"),
    Backlog("Quero jogar"),
    Finished("Finalizados"),
    Kinect("Kinect"),
    LocalMultiplayer("Multiplayer local"),
    Genre("Gênero"),
}

fun filterLibraryGames(
    games: List<GameEntry>,
    states: Map<String, PlayerGameState>,
    filter: LibraryFilter,
    query: String,
    capabilityFilter: LibraryCapabilityFilter = LibraryCapabilityFilter.All,
    genreFilter: String? = null,
    metadata: Map<Long, GameCapabilityMetadata> = emptyMap(),
): List<GameEntry> {
    val normalizedQuery = query.trim()
    val normalizedGenre = genreFilter?.trim()?.takeIf(String::isNotBlank)

    fun metadataFor(game: GameEntry): GameCapabilityMetadata =
        metadata[game.titleId] ?: GameCapabilityCatalog.metadataFor(game.titleId)

    return games
        .asSequence()
        .filter { game ->
            val gameMetadata = metadataFor(game)
            normalizedQuery.isBlank() ||
                game.title.contains(normalizedQuery, ignoreCase = true) ||
                game.titleIdHex.contains(normalizedQuery, ignoreCase = true) ||
                gameMetadata.normalizedGenre?.contains(normalizedQuery, ignoreCase = true) == true
        }
        .filter { game ->
            val state = states[game.stableKey] ?: PlayerGameState()
            val gameMetadata = metadataFor(game)
            when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Favorites -> state.favorite
                LibraryFilter.Playing -> state.status == GameStatus.Playing
                LibraryFilter.Backlog -> state.status == GameStatus.WantToPlay
                LibraryFilter.Finished -> state.status == GameStatus.Finished
                LibraryFilter.Kinect ->
                    gameMetadata.kinect == KinectSupport.Supported ||
                        gameMetadata.kinect == KinectSupport.Required
                LibraryFilter.LocalMultiplayer -> gameMetadata.hasLocalMultiplayer == true
                LibraryFilter.Genre -> gameMetadata.normalizedGenre != null
            }
        }
        .filter { game ->
            val gameMetadata = metadataFor(game)
            when (capabilityFilter) {
                LibraryCapabilityFilter.All -> true
                LibraryCapabilityFilter.Kinect ->
                    gameMetadata.kinect == KinectSupport.Supported ||
                        gameMetadata.kinect == KinectSupport.Required
                LibraryCapabilityFilter.LocalMultiplayer -> gameMetadata.hasLocalMultiplayer == true
            }
        }
        .filter { game ->
            normalizedGenre == null ||
                metadataFor(game).normalizedGenre?.equals(normalizedGenre, ignoreCase = true) == true
        }
        .sortedWith(
            compareByDescending<GameEntry> { states[it.stableKey]?.lastPlayedAt ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        .toList()
}

fun recentGames(
    games: List<GameEntry>,
    states: Map<String, PlayerGameState>,
    limit: Int = 6,
): List<GameEntry> = games
    .filter { (states[it.stableKey]?.lastPlayedAt ?: 0L) > 0L }
    .sortedByDescending { states[it.stableKey]?.lastPlayedAt ?: 0L }
    .take(limit)
