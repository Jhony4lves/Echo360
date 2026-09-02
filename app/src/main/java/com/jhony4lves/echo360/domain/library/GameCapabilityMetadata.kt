package com.jhony4lves.echo360.domain.library

enum class KinectSupport(val label: String) {
    Unknown("Desconhecido"),
    No("Sem Kinect"),
    Supported("Kinect opcional"),
    Required("Kinect obrigatório"),
}

data class GameCapabilityMetadata(
    val kinect: KinectSupport = KinectSupport.Unknown,
    /** null = desconhecido; 1 = single-player local; 2+ = multiplayer local confirmado. */
    val localPlayers: Int? = null,
    val genre: String? = null,
) {
    init {
        require(localPlayers == null || localPlayers in 1..16) {
            "Jogadores locais deve ficar entre 1 e 16 quando informado."
        }
    }

    val hasLocalMultiplayer: Boolean?
        get() = localPlayers?.let { it >= 2 }

    val normalizedGenre: String?
        get() = genre?.trim()?.takeIf(String::isNotBlank)
}

enum class LibraryCapabilityFilter(val label: String) {
    All("Todos"),
    Kinect("Kinect"),
    LocalMultiplayer("Multiplayer local"),
}

fun knownGenres(
    games: List<GameEntry>,
    metadata: Map<Long, GameCapabilityMetadata>,
): List<String> = games
    .mapNotNull { metadata[it.titleId]?.normalizedGenre }
    .distinctBy(String::lowercase)
    .sortedWith(String.CASE_INSENSITIVE_ORDER)
