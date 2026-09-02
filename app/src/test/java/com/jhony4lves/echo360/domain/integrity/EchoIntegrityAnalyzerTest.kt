package com.jhony4lves.echo360.domain.integrity

import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoIntegrityAnalyzerTest {
    private val analyzer = EchoIntegrityAnalyzer()

    @Test
    fun `healthy game stays clean and media id zero is not guessed as corruption`() {
        val game = game(mediaId = 0L)
        val report = analyzer.analyze(snapshot(listOf(game)), game, checkedAtEpochMs = 123L)

        assertTrue(report.findings.isEmpty())
        assertTrue(report.healthy)
        assertEquals(123L, report.checkedAtEpochMs)
        assertFalse(report.remoteAttempted)
    }

    @Test
    fun `invalid launch facts become evidence based errors`() {
        val game = game(
            titleId = 0L,
            directory = "Games/../Bad",
            executable = "subdir/default.xex",
            contentRoot = null,
        )
        val report = analyzer.analyze(snapshot(listOf(game)), game)
        val codes = report.findings.map { it.code }.toSet()

        assertTrue(EchoIntegrityAnalyzer.CODE_TITLE_ID_ZERO in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_PATH_TRAVERSAL in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_CONTENT_ROOT_MISSING in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_EXECUTABLE_UNSAFE in codes)
        assertEquals(IntegritySeverity.Error, report.highestSeverity)
        assertFalse(report.healthy)
    }

    @Test
    fun `blank executable and title are reported without inventing metadata rules`() {
        val game = game(title = "", executable = "")
        val report = analyzer.analyze(snapshot(listOf(game)), game)
        val byCode = report.findings.associateBy { it.code }

        assertEquals(IntegritySeverity.Warning, byCode.getValue(EchoIntegrityAnalyzer.CODE_TITLE_BLANK).severity)
        assertEquals(IntegritySeverity.Error, byCode.getValue(EchoIntegrityAnalyzer.CODE_EXECUTABLE_MISSING).severity)
        assertFalse(report.findings.any { it.code.contains("media", ignoreCase = true) })
    }

    @Test
    fun `duplicate stable key is reported for selected game`() {
        val first = game(databaseId = 1L)
        val second = game(databaseId = 2L)
        val report = analyzer.analyze(snapshot(listOf(first, second)), first)

        val duplicate = report.findings.single { it.code == EchoIntegrityAnalyzer.CODE_DUPLICATE_ENTRY }
        assertEquals(first.stableKey, duplicate.gameStableKey)
        assertEquals(IntegritySeverity.Warning, duplicate.severity)
    }

    @Test
    fun `selected game does not inherit duplicate finding from another title`() {
        val selected = game(titleId = 0x11111111L, mediaId = 1L, title = "Selected")
        val otherA = game(titleId = 0x22222222L, mediaId = 2L, databaseId = 20L, title = "Other")
        val otherB = otherA.copy(databaseId = 21L)

        val report = analyzer.analyze(snapshot(listOf(selected, otherA, otherB)), selected)

        assertFalse(report.findings.any { it.code == EchoIntegrityAnalyzer.CODE_DUPLICATE_ENTRY })
    }

    @Test
    fun `collection report includes snapshot level failures and duplicate entries`() {
        val duplicate = game()
        val report = analyzer.analyze(
            LibrarySnapshot(
                games = listOf(duplicate, duplicate.copy(databaseId = 99L)),
                auroraRoot = "",
                databaseRemotePath = "",
                databaseBytes = 0L,
            ),
        )
        val codes = report.findings.map { it.code }.toSet()

        assertTrue(EchoIntegrityAnalyzer.CODE_DATABASE_EMPTY in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_DATABASE_PATH_MISSING in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_AURORA_ROOT_MISSING in codes)
        assertTrue(EchoIntegrityAnalyzer.CODE_DUPLICATE_ENTRY in codes)
        assertTrue(report.errorCount >= 3)
    }

    @Test
    fun `negative disc number is warning only`() {
        val game = game(discNumber = -1)
        val report = analyzer.analyze(snapshot(listOf(game)), game)

        assertEquals(1, report.warningCount)
        assertEquals(0, report.errorCount)
        assertEquals(EchoIntegrityAnalyzer.CODE_DISC_NUMBER_NEGATIVE, report.findings.single().code)
    }

    private fun snapshot(games: List<GameEntry>) = LibrarySnapshot(
        games = games,
        auroraRoot = "/Hdd1/Aurora",
        databaseRemotePath = "/Hdd1/Aurora/Data/Databases/content.db",
        databaseBytes = 1024L,
    )

    private fun game(
        databaseId: Long = 10L,
        titleId: Long = 0x465307E4L,
        mediaId: Long = 0x12345678L,
        discNumber: Int = 1,
        title: String = "Dark Souls II",
        directory: String = "Games/Dark Souls II",
        executable: String = "default.xex",
        contentRoot: String? = "/Hdd1",
    ) = GameEntry(
        databaseId = databaseId,
        titleId = titleId,
        mediaId = mediaId,
        discNumber = discNumber,
        title = title,
        directory = directory,
        executable = executable,
        baseVersion = "1.0",
        contentRoot = contentRoot,
    )
}
