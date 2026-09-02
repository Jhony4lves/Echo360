package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.library.CurrentTitleObservation
import com.jhony4lves.echo360.domain.library.CurrentTitleOrigin
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIdentityAnalyzerTest {
    private val analyzer = RuntimeIdentityAnalyzer()
    private val game = game(mediaId = 0x3E4B911DL)

    @Test
    fun `same title and Media ID is clear while TU stays raw information`() {
        val report = analyzer.analyze(
            game,
            observation(
                titleId = game.titleId,
                mediaId = game.mediaId,
                tu = 8,
            ),
        )

        assertTrue(report.sameTitle)
        assertEquals(true, report.mediaMatches)
        assertEquals(8, report.titleUpdateVersion)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `same title with proven different Media ID is warning`() {
        val report = analyzer.analyze(
            game,
            observation(
                titleId = game.titleId,
                mediaId = 0x11111111L,
                tu = 8,
            ),
        )

        assertEquals(false, report.mediaMatches)
        assertEquals(1, report.findings.size)
        assertEquals(RuntimeIdentityAnalyzer.CODE_MEDIA_ID_MISMATCH, report.findings.single().code)
        assertEquals(IntegritySeverity.Warning, report.findings.single().severity)
    }

    @Test
    fun `different running title is informational not corruption`() {
        val report = analyzer.analyze(
            game,
            observation(
                titleId = 0x545408A7L,
                mediaId = 0x12345678L,
                tu = 27,
            ),
        )

        assertFalse(report.sameTitle)
        assertEquals(RuntimeIdentityAnalyzer.CODE_OTHER_TITLE_RUNNING, report.findings.single().code)
        assertEquals(IntegritySeverity.Info, report.findings.single().severity)
    }

    @Test
    fun `unknown Media ID never produces mismatch`() {
        val unknownLibraryMedia = game(mediaId = 0L)
        val minimalEchoCore = CurrentTitleObservation(
            titleId = unknownLibraryMedia.titleId,
            origin = CurrentTitleOrigin.EchoCore,
            mediaId = null,
            details = null,
        )

        val report = analyzer.analyze(unknownLibraryMedia, minimalEchoCore)

        assertTrue(report.sameTitle)
        assertFalse(report.mediaComparable)
        assertNull(report.mediaMatches)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `no runtime observation is inconclusive and not a finding`() {
        val report = analyzer.analyze(game, null)

        assertNull(report.observation)
        assertTrue(report.findings.isEmpty())
        assertNull(report.titleUpdateVersion)
    }

    @Test
    fun `TU zero remains raw version rather than outdated warning`() {
        val report = analyzer.analyze(
            game,
            observation(
                titleId = game.titleId,
                mediaId = game.mediaId,
                tu = 0,
            ),
        )

        assertEquals(0, report.titleUpdateVersion)
        assertTrue(report.findings.isEmpty())
    }

    private fun observation(titleId: Long, mediaId: Long, tu: Int) = CurrentTitleObservation(
        titleId = titleId,
        origin = CurrentTitleOrigin.NovaCompatibility,
        mediaId = mediaId.takeIf { it != 0L },
        details = NowPlaying(
            titleId = titleId,
            mediaId = mediaId,
            executableDevicePath = "Hdd1:\\Games\\default.xex",
            titleUpdateVersion = tu,
            discCurrent = 1,
            discCount = 1,
            resolutionWidth = 1280,
            resolutionHeight = 720,
            baseVersion = "1.0.0",
            currentVersion = "1.0.$tu",
        ),
    )

    private fun game(mediaId: Long) = GameEntry(
        databaseId = 1L,
        titleId = 0x4D530919L,
        mediaId = mediaId,
        discNumber = 1,
        title = "Test Game",
        directory = "Test Game",
        executable = "default.xex",
        baseVersion = "1.0.0",
        contentRoot = "/Hdd1/Games",
    )
}
