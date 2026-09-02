package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCapabilityFilterTest {
    private val games = listOf(
        game(1L, "Dance", 0x10000001L),
        game(2L, "Couch Racer", 0x10000002L),
        game(3L, "Unknown Game", 0x10000003L),
    )
    private val states = emptyMap<String, PlayerGameState>()
    private val metadata = mapOf(
        games[0].titleId to GameCapabilityMetadata(
            kinect = KinectSupport.Required,
            localPlayers = 1,
            genre = "Dança",
        ),
        games[1].titleId to GameCapabilityMetadata(
            kinect = KinectSupport.No,
            localPlayers = 4,
            genre = "Corrida",
        ),
        games[2].titleId to GameCapabilityMetadata(),
    )

    @Test
    fun `Kinect filter includes only explicitly supported or required games`() {
        val filtered = filterLibraryGames(
            games = games,
            states = states,
            filter = LibraryFilter.All,
            query = "",
            capabilityFilter = LibraryCapabilityFilter.Kinect,
            metadata = metadata,
        )

        assertEquals(listOf("Dance"), filtered.map { it.title })
    }

    @Test
    fun `local multiplayer excludes unknown and confirmed single player`() {
        val filtered = filterLibraryGames(
            games = games,
            states = states,
            filter = LibraryFilter.All,
            query = "",
            capabilityFilter = LibraryCapabilityFilter.LocalMultiplayer,
            metadata = metadata,
        )

        assertEquals(listOf("Couch Racer"), filtered.map { it.title })
    }

    @Test
    fun `genre filter is case insensitive and explicit`() {
        val filtered = filterLibraryGames(
            games = games,
            states = states,
            filter = LibraryFilter.All,
            query = "",
            genreFilter = "corrida",
            metadata = metadata,
        )

        assertEquals(listOf("Couch Racer"), filtered.map { it.title })
    }

    @Test
    fun `query can search confirmed genre without fabricating unknown metadata`() {
        val dance = filterLibraryGames(
            games = games,
            states = states,
            filter = LibraryFilter.All,
            query = "dança",
            metadata = metadata,
        )
        val inferred = filterLibraryGames(
            games = games,
            states = states,
            filter = LibraryFilter.All,
            query = "kinect",
            metadata = metadata,
        )

        assertEquals(listOf("Dance"), dance.map { it.title })
        assertFalse(inferred.any { it.title == "Dance" })
    }

    @Test
    fun `known genres are deduplicated case insensitively`() {
        val duplicated = metadata + (4L to GameCapabilityMetadata(genre = "corrida"))
        val extraGames = games + game(4L, "Other Racer", 4L)

        val genres = knownGenres(extraGames, duplicated)

        assertTrue(genres.any { it.equals("Corrida", ignoreCase = true) })
        assertEquals(2, genres.size)
    }

    private fun game(databaseId: Long, title: String, titleId: Long) = GameEntry(
        databaseId = databaseId,
        titleId = titleId,
        mediaId = databaseId,
        discNumber = 1,
        title = title,
        directory = title,
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1/Games",
    )
}
