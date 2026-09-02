package com.jhony4lves.echo360.domain.library

import com.jhony4lves.echo360.data.library.LaunchAttemptCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LaunchAttemptEngineTest {
    private val engine = LaunchAttemptEngine(
        confirmWindowMs = 10 * 60_000L,
        maxAttempts = 3,
    )

    @Test
    fun `accepted same title observed within window becomes confirmed`() {
        val requested = attempt(id = "a", titleId = 0x465307E4L, requested = 1_000L)
        val accepted = engine.markAccepted(
            LaunchAttemptLedger(listOf(requested)),
            "a",
            atEpochMs = 2_000L,
        )

        val confirmed = engine.confirmObserved(
            accepted,
            titleId = 0x465307E4L,
            observedAtEpochMs = 60_000L,
        )

        assertEquals(LaunchAttemptStatus.Confirmed, confirmed.attempts.single().status)
        assertEquals(60_000L, confirmed.attempts.single().confirmedAtEpochMs)
    }

    @Test
    fun `different title never confirms accepted launch`() {
        val accepted = engine.markAccepted(
            LaunchAttemptLedger(listOf(attempt(id = "a", titleId = 0x465307E4L, requested = 1_000L))),
            "a",
            2_000L,
        )

        val result = engine.confirmObserved(accepted, 0x545408A7L, 30_000L)

        assertSame(accepted, result)
        assertEquals(LaunchAttemptStatus.Accepted, result.attempts.single().status)
    }

    @Test
    fun `observation after confirmation window stays accepted not crashed`() {
        val accepted = engine.markAccepted(
            LaunchAttemptLedger(listOf(attempt(id = "a", titleId = 1L, requested = 1_000L))),
            "a",
            2_000L,
        )

        val result = engine.confirmObserved(
            accepted,
            titleId = 1L,
            observedAtEpochMs = 2_000L + 10 * 60_000L + 1L,
        )

        assertSame(accepted, result)
        assertEquals(LaunchAttemptStatus.Accepted, result.attempts.single().status)
        assertNull(result.attempts.single().confirmedAtEpochMs)
    }

    @Test
    fun `rejected launch cannot later become confirmed`() {
        val initial = LaunchAttemptLedger(listOf(attempt(id = "a", titleId = 1L, requested = 1_000L)))
        val rejected = engine.markRejected(initial, "a", "NOVA 500", 2_000L)

        val observed = engine.confirmObserved(rejected, 1L, 3_000L)

        assertSame(rejected, observed)
        assertEquals(LaunchAttemptStatus.Rejected, observed.attempts.single().status)
        assertEquals("NOVA 500", observed.attempts.single().rejectionReason)
    }

    @Test
    fun `confirmed launch cannot be overwritten by late rejection`() {
        val initial = LaunchAttemptLedger(listOf(attempt(id = "a", titleId = 1L, requested = 1_000L)))
        val accepted = engine.markAccepted(initial, "a", 2_000L)
        val confirmed = engine.confirmObserved(accepted, 1L, 3_000L)

        val result = engine.markRejected(confirmed, "a", "late", 4_000L)

        assertSame(confirmed, result)
        assertEquals(LaunchAttemptStatus.Confirmed, result.attempts.single().status)
    }

    @Test
    fun `ledger prepend is bounded newest first`() {
        var ledger = LaunchAttemptLedger()
        repeat(5) { index ->
            ledger = engine.prepend(
                ledger,
                attempt(id = index.toString(), titleId = index.toLong(), requested = index.toLong()),
            )
        }

        assertEquals(listOf("4", "3", "2"), ledger.attempts.map { it.id })
    }

    @Test
    fun `codec round trips statuses and ignores isolated corrupt line`() {
        val attempts = listOf(
            attempt(id = "confirmed", titleId = 1L, requested = 10L).copy(
                acceptedAtEpochMs = 20L,
                confirmedAtEpochMs = 30L,
            ),
            attempt(id = "rejected", titleId = 2L, requested = 11L).copy(
                rejectedAtEpochMs = 21L,
                rejectionReason = "senha recusada",
            ),
        )
        val payload = LaunchAttemptCodec.encode(attempts) + "\nregistro|quebrado"

        val decoded = LaunchAttemptCodec.decode(payload)

        assertEquals(2, decoded.size)
        assertEquals(LaunchAttemptStatus.Rejected, decoded.first().status)
        assertEquals("senha recusada", decoded.first().rejectionReason)
        assertEquals(LaunchAttemptStatus.Confirmed, decoded.last().status)
    }

    private fun attempt(
        id: String,
        titleId: Long,
        requested: Long,
    ) = LaunchAttempt(
        id = id,
        titleId = titleId,
        mediaId = 0L,
        title = "Game $id",
        requestedAtEpochMs = requested,
    )
}
