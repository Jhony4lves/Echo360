package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeCollectionModelTest {
    @Test
    fun `live game wins continue slot and rails stay bounded`() {
        val games = (1..40).map(::game)
        val states = games.associate { entry ->
            entry.stableKey to PlayerGameState(
                favorite = entry.databaseId % 2L == 0L,
                lastPlayedAt = entry.databaseId * 1_000L,
                launchCount = entry.databaseId.toInt(),
            )
        }
        val live = games[3]

        val model = buildHomeCollectionModel(games, states, liveGame = live, railLimit = 12)

        assertSame(live, model.continueGame)
        assertEquals(12, model.recentGames.size)
        assertEquals(12, model.favoriteGames.size)
        assertEquals(40, model.totalGames)
        assertEquals(20, model.favoriteCount)
        assertEquals(40L, model.recentGames.first().databaseId)
        assertEquals(40L, model.favoriteGames.first().databaseId)
    }

    @Test
    fun `most recent game becomes continue fallback`() {
        val alpha = game(1, "Alpha")
        val beta = game(2, "Beta")
        val states = mapOf(
            alpha.stableKey to PlayerGameState(lastPlayedAt = 10L),
            beta.stableKey to PlayerGameState(lastPlayedAt = 20L),
        )

        val model = buildHomeCollectionModel(listOf(alpha, beta), states)

        assertSame(beta, model.continueGame)
        assertEquals(listOf(beta, alpha), model.recentGames)
    }

    @Test
    fun `favorites with no playtime are alphabetic after played favorites`() {
        val zeta = game(1, "Zeta")
        val alpha = game(2, "Alpha")
        val played = game(3, "Played")
        val states = mapOf(
            zeta.stableKey to PlayerGameState(favorite = true),
            alpha.stableKey to PlayerGameState(favorite = true),
            played.stableKey to PlayerGameState(favorite = true, lastPlayedAt = 100L),
        )

        val model = buildHomeCollectionModel(listOf(zeta, alpha, played), states)

        assertEquals(listOf(played, alpha, zeta), model.favoriteGames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rail limit must be positive`() {
        buildHomeCollectionModel(emptyList(), emptyMap(), railLimit = 0)
    }

    private fun game(id: Int, title: String = "Game $id") = GameEntry(
        databaseId = id.toLong(),
        titleId = 0x10000000L + id,
        mediaId = 0x20000000L + id,
        discNumber = 1,
        title = title,
        directory = "Games/$title",
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1",
    )
}
