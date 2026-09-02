package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaySessionEngineTest {
    private val engine = PlaySessionEngine(
        maxRecentSessions = 3,
        maxContinuousGapMs = 120_000L,
    )

    @Test
    fun `counts only continuous observations of the same game`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 0L))
        ledger = engine.observe(ledger, observation("A", 30_000L))
        ledger = engine.observe(ledger, observation("A", 60_000L))

        val active = requireNotNull(ledger.active)
        assertEquals(60_000L, active.durationMs)
        assertEquals(3, active.observationCount)
        assertEquals(60_000L, engine.summaryFor(ledger, "A").totalObservedMs)
    }

    @Test
    fun `splits same game after a large unobserved gap`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 0L))
        ledger = engine.observe(ledger, observation("A", 60_000L))
        ledger = engine.observe(ledger, observation("A", 300_000L))

        assertEquals("A", ledger.active?.stableKey)
        assertEquals(300_000L, ledger.active?.startedAtEpochMs)
        assertEquals(1, ledger.recent.size)
        assertEquals(60_000L, ledger.recent.single().durationMs)
        assertEquals(60_000L, engine.summaryFor(ledger, "A").totalObservedMs)
        assertEquals(2, engine.summaryFor(ledger, "A").sessionCount)
    }

    @Test
    fun `game switch closes previous session at last confirmed sample`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 0L))
        ledger = engine.observe(ledger, observation("A", 30_000L))
        ledger = engine.observe(ledger, observation("B", 90_000L))

        assertEquals("B", ledger.active?.stableKey)
        assertEquals(30_000L, ledger.recent.single().endedAtEpochMs)
        assertEquals(30_000L, ledger.recent.single().durationMs)
    }

    @Test
    fun `non game observation closes active without guessing tail time`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 10_000L))
        ledger = engine.observe(ledger, observation("A", 40_000L))
        ledger = engine.observeNonGame(ledger, 70_000L)

        assertNull(ledger.active)
        assertEquals(40_000L, ledger.recent.single().endedAtEpochMs)
        assertEquals(30_000L, ledger.recent.single().durationMs)
    }

    @Test
    fun `stopping observation closes at last sample and does not count app background gap`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 0L))
        ledger = engine.observe(ledger, observation("A", 60_000L))

        ledger = engine.stopObserving(ledger)

        assertNull(ledger.active)
        assertEquals(60_000L, ledger.recent.single().endedAtEpochMs)
        assertEquals(60_000L, ledger.recent.single().durationMs)

        ledger = engine.observe(ledger, observation("A", 90_000L))
        val summary = engine.summaryFor(ledger, "A")
        assertEquals(60_000L, summary.totalObservedMs)
        assertEquals(2, summary.sessionCount)
        assertEquals(90_000L, summary.activeSession?.startedAtEpochMs)
    }

    @Test
    fun `stop observation is idempotent without an active session`() {
        val ledger = PlaySessionLedger(
            recent = listOf(
                PlaySession(
                    id = "done",
                    stableKey = "A",
                    titleId = 1L,
                    mediaId = 2L,
                    title = "A",
                    startedAtEpochMs = 0L,
                    lastSeenAtEpochMs = 10L,
                    endedAtEpochMs = 10L,
                ),
            ),
        )

        assertSame(ledger, engine.stopObserving(ledger))
    }

    @Test
    fun `out of order observation is ignored`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 100_000L))
        ledger = engine.observe(ledger, observation("A", 130_000L))
        val before = ledger

        ledger = engine.observe(ledger, observation("A", 120_000L))

        assertEquals(before, ledger)
        assertSame(before.active, ledger.active)
    }

    @Test
    fun `retention remains bounded`() {
        var ledger = PlaySessionLedger()
        repeat(5) { index ->
            val key = "G$index"
            ledger = engine.observe(ledger, observation(key, index * 10_000L))
            ledger = engine.observeNonGame(ledger, index * 10_000L + 1_000L)
        }

        assertEquals(3, ledger.recent.size)
        assertEquals(listOf("G4", "G3", "G2"), ledger.recent.map { it.stableKey })
    }

    @Test
    fun `summary combines completed and active sessions`() {
        var ledger = PlaySessionLedger()
        ledger = engine.observe(ledger, observation("A", 0L))
        ledger = engine.observe(ledger, observation("A", 30_000L))
        ledger = engine.observeNonGame(ledger, 40_000L)
        ledger = engine.observe(ledger, observation("A", 100_000L))
        ledger = engine.observe(ledger, observation("A", 160_000L))

        val summary = engine.summaryFor(ledger, "A")

        assertEquals(90_000L, summary.totalObservedMs)
        assertEquals(2, summary.sessionCount)
        assertEquals(160_000L, summary.lastObservedAtEpochMs)
        assertEquals(2, summary.recentSessions.size)
        assertEquals(true, summary.recentSessions.first().active)
    }

    private fun observation(key: String, at: Long) = PlayObservation(
        stableKey = key,
        titleId = key.hashCode().toLong() and 0xFFFF_FFFFL,
        mediaId = 0L,
        title = "Game $key",
        observedAtEpochMs = at,
    )
}
