package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private val alpha = game(1, 0x415608A7, 0x11111111, "Alpha")
    private val bravo = game(2, 0x5454082B, 0x22222222, "Bravo")

    @Test
    fun `stable key includes title media and disc`() {
        assertEquals("415608A7:11111111:1", alpha.stableKey)
    }

    @Test
    fun `favorites filter returns only favorite titles`() {
        val states = mapOf(
            alpha.stableKey to PlayerGameState(favorite = true),
            bravo.stableKey to PlayerGameState(),
        )

        val result = filterLibraryGames(
            games = listOf(alpha, bravo),
            states = states,
            filter = LibraryFilter.Favorites,
            query = "",
        )

        assertEquals(listOf(alpha), result)
    }

    @Test
    fun `recent games sort newest first`() {
        val states = mapOf(
            alpha.stableKey to PlayerGameState(lastPlayedAt = 100),
            bravo.stableKey to PlayerGameState(lastPlayedAt = 200),
        )

        assertEquals(listOf(bravo, alpha), recentGames(listOf(alpha, bravo), states))
    }

    @Test
    fun `query matches title id`() {
        val result = filterLibraryGames(
            games = listOf(alpha, bravo),
            states = emptyMap(),
            filter = LibraryFilter.All,
            query = "415608",
        )
        assertTrue(result.single() == alpha)
    }

    private fun game(id: Long, titleId: Long, mediaId: Long, title: String) = GameEntry(
        databaseId = id,
        titleId = titleId,
        mediaId = mediaId,
        discNumber = 1,
        title = title,
        directory = "Games/$title",
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1",
    )
}
