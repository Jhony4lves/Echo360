package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.domain.library.GameCapabilityCatalog
import com.jhony4lves.echo360.domain.library.GameCapabilityMetadata
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.KinectSupport

/**
 * Player-confirmed metadata only. Nothing here is inferred from title names or
 * silently populated from an unverified Aurora schema field.
 *
 * Metadata is Title-ID scoped so multiple discs/media variants of the same game
 * share capability/genre classification.
 */
class GameCapabilityMetadataStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(games: List<GameEntry>): Map<Long, GameCapabilityMetadata> = games
        .map(GameEntry::titleId)
        .distinct()
        .associateWith(::metadataForTitleId)
        .also(GameCapabilityCatalog::replace)

    fun metadataFor(game: GameEntry): GameCapabilityMetadata = metadataForTitleId(game.titleId)
        .also { metadata -> GameCapabilityCatalog.put(game.titleId, metadata) }

    fun save(game: GameEntry, metadata: GameCapabilityMetadata): GameCapabilityMetadata {
        val prefix = prefix(game.titleId)
        val genre = metadata.normalizedGenre
        prefs.edit()
            .putString("${prefix}kinect", metadata.kinect.name)
            .apply {
                if (metadata.localPlayers == null) remove("${prefix}localPlayers")
                else putInt("${prefix}localPlayers", metadata.localPlayers)
                if (genre == null) remove("${prefix}genre")
                else putString("${prefix}genre", genre.take(MAX_GENRE_CHARS))
            }
            .apply()
        return metadataFor(game)
    }

    fun clear(game: GameEntry) {
        val prefix = prefix(game.titleId)
        prefs.edit()
            .remove("${prefix}kinect")
            .remove("${prefix}localPlayers")
            .remove("${prefix}genre")
            .apply()
        GameCapabilityCatalog.remove(game.titleId)
    }

    private fun metadataForTitleId(titleId: Long): GameCapabilityMetadata {
        val prefix = prefix(titleId)
        val localPlayers = if (prefs.contains("${prefix}localPlayers")) {
            prefs.getInt("${prefix}localPlayers", 1).coerceIn(1, 16)
        } else {
            null
        }
        return GameCapabilityMetadata(
            kinect = prefs.getString("${prefix}kinect", null)
                ?.let { raw -> KinectSupport.entries.firstOrNull { it.name == raw } }
                ?: KinectSupport.Unknown,
            localPlayers = localPlayers,
            genre = prefs.getString("${prefix}genre", null)
                ?.trim()
                ?.take(MAX_GENRE_CHARS)
                ?.takeIf(String::isNotBlank),
        )
    }

    private fun prefix(titleId: Long): String =
        "title:${titleId.toUInt().toString(16).uppercase().padStart(8, '0')}:"

    companion object {
        private const val PREFS_NAME = "echo_game_capability_metadata"
        const val MAX_GENRE_CHARS = 40
    }
}
