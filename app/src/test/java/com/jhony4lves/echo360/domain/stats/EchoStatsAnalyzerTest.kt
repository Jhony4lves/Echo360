package com.jhony4lves.echo360.domain.stats

import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.library.PlaySessionLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoStatsAnalyzerTest {
    private val analyzer = EchoStatsAnalyzer(recentLimit = 2)

    @Test
    fun `empty ledger produces honest empty snapshot`() {
        val stats = analyzer.analyze(PlaySessionLedger())

        assertEquals(0L, stats.totalObservedMs)
        assertEquals(0, stats.sessionCount)
        assertEquals(0, stats.distinctGames)
        assertEquals(0L, stats.averageSessionMs)
        assertNull(stats.longestSession)
        assertNull(stats.mostPlayedGame)
        assertTrue(stats.recentSessions.isEmpty())
    }

    @Test
    fun `aggregates sessions by Title ID across media variants`() {
        val first = session(
            id = "a",
            titleId = 0x465307E4L,
            mediaId = 1L,
            title = "Dark Souls II",
            start = 1_000L,
            last = 61_000L,
            end = 61_000L,
        )
        val second = session(
            id = "b",
            titleId = 0x465307E4L,
            mediaId = 2L,
            title = "Dark Souls II",
            start = 100_000L,
            last = 220_000L,
            end = 220_000L,
        )
        val other = session(
            id = "c",
            titleId = 0x415608A7L,
            mediaId = 3L,
            title = "Prototype 2",
            start = 300_000L,
            last = 330_000L,
            end = 330_000L,
        )

        val stats = analyzer.analyze(
            PlaySessionLedger(recent = listOf(other, second, first)),
        )

        assertEquals(210_000L, stats.totalObservedMs)
        assertEquals(3, stats.sessionCount)
        assertEquals(2, stats.distinctGames)
        assertEquals(180_000L, stats.mostPlayedGame?.totalObservedMs)
        assertEquals(2, stats.mostPlayedGame?.sessionCount)
        assertEquals("Dark Souls II", stats.mostPlayedGame?.title)
        assertSame(second, stats.longestSession)
    }

    @Test
    fun `active session is included once and recent list stays bounded`() {
        val active = session(
            id = "active",
            titleId = 1L,
            mediaId = 1L,
            title = "Active",
            start = 1_000L,
            last = 11_000L,
            end = null,
        )
        val completedA = session("a", 2L, 2L, "A", 20_000L, 25_000L, 25_000L)
        val completedB = session("b", 3L, 3L, "B", 30_000L, 40_000L, 40_000L)

        val stats = analyzer.analyze(
            PlaySessionLedger(
                active = active,
                recent = listOf(completedB, completedA, active.copy(endedAtEpochMs = 11_000L)),
            ),
        )

        assertEquals(3, stats.sessionCount)
        assertEquals(2, stats.recentSessions.size)
        assertEquals("B", stats.recentSessions.first().title)
        assertSame(active, stats.activeSession)
    }

    @Test
    fun `latest title label wins for same Title ID`() {
        val old = session("old", 9L, 1L, "Old label", 0L, 1_000L, 1_000L)
        val latest = session("new", 9L, 1L, "Current label", 2_000L, 4_000L, 4_000L)

        val stats = analyzer.analyze(PlaySessionLedger(recent = listOf(old, latest)))

        assertEquals("Current label", stats.games.single().title)
        assertEquals(3_000L, stats.games.single().totalObservedMs)
    }

    private fun session(
        id: String,
        titleId: Long,
        mediaId: Long,
        title: String,
        start: Long,
        last: Long,
        end: Long?,
    ) = PlaySession(
        id = id,
        stableKey = "title:${titleId.toString(16)}",
        titleId = titleId,
        mediaId = mediaId,
        title = title,
        startedAtEpochMs = start,
        lastSeenAtEpochMs = last,
        endedAtEpochMs = end,
        observationCount = 2,
    )
}
