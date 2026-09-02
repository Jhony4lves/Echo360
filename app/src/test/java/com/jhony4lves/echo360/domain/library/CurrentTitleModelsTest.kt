package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTitleModelsTest {
    @Test
    fun `NOVA media id zero becomes unknown rather than real metadata`() {
        val rich = nowPlaying(mediaId = 0L)
        val observation = rich.toCurrentTitleObservation()

        assertEquals(rich.titleId, observation.titleId)
        assertNull(observation.mediaId)
        assertSame(rich, observation.details)
        assertEquals(CurrentTitleOrigin.NovaCompatibility, observation.origin)
        assertTrue(observation.hasRichDetails)
    }

    @Test
    fun `minimal EchoCore style observation keeps rich metadata absent`() {
        val observation = CurrentTitleObservation(
            titleId = 0x545408A7L,
            origin = CurrentTitleOrigin.EchoCore,
        )

        assertNull(observation.mediaId)
        assertNull(observation.details)
        assertFalse(observation.hasRichDetails)
        assertEquals("545408A7", observation.titleIdHex)
    }

    private fun nowPlaying(mediaId: Long) = NowPlaying(
        titleId = 0x465307E4L,
        mediaId = mediaId,
        executableDevicePath = "\\Device\\Harddisk0\\Partition1\\Games\\default.xex",
        titleUpdateVersion = 8,
        discCurrent = 1,
        discCount = 1,
        resolutionWidth = 1280,
        resolutionHeight = 720,
        baseVersion = null,
        currentVersion = null,
    )
}
