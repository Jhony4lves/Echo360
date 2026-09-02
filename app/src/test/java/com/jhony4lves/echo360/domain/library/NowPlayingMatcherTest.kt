package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingMatcherTest {
    private val first = game(
        databaseId = 1,
        titleId = 0x465307E4,
        mediaId = 0x11111111,
        title = "Dark Souls II Disc A",
    )
    private val second = game(
        databaseId = 2,
        titleId = 0x465307E4,
        mediaId = 0x22222222,
        title = "Dark Souls II Disc B",
    )
    private val other = game(
        databaseId = 3,
        titleId = 0x545408A7,
        mediaId = 0x33333333,
        title = "GTA V",
    )
    private val games = listOf(first, second, other)

    @Test
    fun `exact media id wins when title has multiple entries`() {
        val matched = matchObservedGame(
            games,
            CurrentTitleObservation(
                titleId = first.titleId,
                mediaId = second.mediaId,
                origin = CurrentTitleOrigin.NovaCompatibility,
            ),
        )

        assertEquals(second.stableKey, matched?.stableKey)
    }

    @Test
    fun `title-only EchoCore style observation falls back to title id`() {
        val matched = matchObservedGame(
            games,
            CurrentTitleObservation(
                titleId = first.titleId,
                mediaId = null,
                origin = CurrentTitleOrigin.EchoCore,
            ),
        )

        assertEquals(first.stableKey, matched?.stableKey)
    }

    @Test
    fun `unknown media id still falls back to known title id`() {
        val matched = matchObservedGame(
            games,
            CurrentTitleObservation(
                titleId = first.titleId,
                mediaId = 0x77777777,
                origin = CurrentTitleOrigin.NovaCompatibility,
            ),
        )

        assertEquals(first.stableKey, matched?.stableKey)
    }

    @Test
    fun `zero or unknown title is not matched`() {
        assertNull(
            matchObservedGame(
                games,
                CurrentTitleObservation(0L, CurrentTitleOrigin.EchoCore),
            ),
        )
        assertNull(
            matchObservedGame(
                games,
                CurrentTitleObservation(0x12345678, CurrentTitleOrigin.EchoCore),
            ),
        )
    }

    @Test
    fun `legacy rich NowPlaying overload preserves matching behavior`() {
        val matched = matchObservedGame(games, nowPlaying(first.titleId, second.mediaId))
        assertEquals(second.stableKey, matched?.stableKey)
    }

    private fun game(
        databaseId: Long,
        titleId: Long,
        mediaId: Long,
        title: String,
    ) = GameEntry(
        databaseId = databaseId,
        titleId = titleId,
        mediaId = mediaId,
        discNumber = 1,
        title = title,
        directory = "Games/$title",
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1",
    )

    private fun nowPlaying(titleId: Long, mediaId: Long) = NowPlaying(
        titleId = titleId,
        mediaId = mediaId,
        executableDevicePath = "",
        titleUpdateVersion = 0,
        discCurrent = 1,
        discCount = 1,
        resolutionWidth = 1280,
        resolutionHeight = 720,
        baseVersion = null,
        currentVersion = null,
    )
}
