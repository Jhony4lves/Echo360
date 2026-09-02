package com.jhony4lves.echo360.data.library

import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.library.PlaySessionLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySessionCodecTest {
    @Test
    fun `round trips active and completed sessions`() {
        val ledger = PlaySessionLedger(
            active = session(
                id = "active:1",
                key = "A:1",
                title = "Jogo com espaço | PT-BR",
                started = 10L,
                lastSeen = 70L,
            ),
            recent = listOf(
                session(
                    id = "done:1",
                    key = "B:2",
                    title = "Outro Jogo",
                    started = 100L,
                    lastSeen = 160L,
                    ended = 160L,
                    observations = 3,
                ),
            ),
        )

        val decoded = PlaySessionCodec.decode(PlaySessionCodec.encode(ledger))

        assertEquals(ledger, decoded)
    }

    @Test
    fun `corrupt lines do not destroy valid timeline`() {
        val valid = PlaySessionLedger(
            recent = listOf(
                session(
                    id = "good",
                    key = "A",
                    title = "Good",
                    started = 1L,
                    lastSeen = 2L,
                    ended = 2L,
                ),
            ),
        )
        val payload = PlaySessionCodec.encode(valid) + "\nnot-valid\n9|future"

        val decoded = PlaySessionCodec.decode(payload)

        assertEquals(valid, decoded)
    }

    @Test
    fun `completed record without end is normalized to last confirmed sample`() {
        val malformedCompleted = PlaySessionLedger(
            active = session(
                id = "x",
                key = "X",
                title = "X",
                started = 10L,
                lastSeen = 20L,
            ),
        )
        val activeLine = PlaySessionCodec.encode(malformedCompleted)
        val completedLine = activeLine.replaceFirst("|A|", "|C|")

        val decoded = PlaySessionCodec.decode(completedLine)

        assertNull(decoded.active)
        assertEquals(20L, decoded.recent.single().endedAtEpochMs)
        assertEquals(10L, decoded.recent.single().durationMs)
    }

    @Test
    fun `duplicate completed ids are deduplicated`() {
        val one = PlaySessionLedger(
            recent = listOf(
                session(
                    id = "same",
                    key = "A",
                    title = "A",
                    started = 1L,
                    lastSeen = 2L,
                    ended = 2L,
                ),
            ),
        )
        val line = PlaySessionCodec.encode(one)

        val decoded = PlaySessionCodec.decode("$line\n$line")

        assertEquals(1, decoded.recent.size)
    }

    @Test
    fun `playtime key ignores media and disc variants of the same title`() {
        val firstDisc = game(databaseId = 1L, mediaId = 0x11111111, disc = 1)
        val secondDisc = game(databaseId = 2L, mediaId = 0x22222222, disc = 2)

        assertEquals(observedPlaytimeKey(firstDisc), observedPlaytimeKey(secondDisc))
        assertEquals("title:465307E4", observedPlaytimeKey(firstDisc))
    }

    private fun game(databaseId: Long, mediaId: Long, disc: Int) = GameEntry(
        databaseId = databaseId,
        titleId = 0x465307E4,
        mediaId = mediaId,
        discNumber = disc,
        title = "Dark Souls II",
        directory = "Games/Dark Souls II",
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1",
    )

    private fun session(
        id: String,
        key: String,
        title: String,
        started: Long,
        lastSeen: Long,
        ended: Long? = null,
        observations: Int = 2,
    ) = PlaySession(
        id = id,
        stableKey = key,
        titleId = 0x465307E4,
        mediaId = 0x12345678,
        title = title,
        startedAtEpochMs = started,
        lastSeenAtEpochMs = lastSeen,
        endedAtEpochMs = ended,
        observationCount = observations,
    )
}
