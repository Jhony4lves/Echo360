package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.library.GameEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGameIntegrityProbeTest {
    private val probe = RemoteGameIntegrityProbe()

    @Test
    fun `verifies executable from readable directory listing`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = {
                listing(entry("default.xex", size = 4096L))
            },
        )

        val result = probe.verify(filesystem, game())

        assertTrue(result.verified)
        assertTrue(result.findings.isEmpty())
        assertEquals(1, filesystem.listCalls)
        assertEquals(0, filesystem.statCalls)
    }

    @Test
    fun `case difference in remote filename is accepted`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = {
                listing(entry("DEFAULT.XEX", size = 2048L))
            },
        )

        assertTrue(probe.verify(filesystem, game()).verified)
    }

    @Test
    fun `trimmed executable name is reused for STAT path`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { error("LIST unsupported") },
            statBlock = { path ->
                assertEquals("/Hdd1/Games/Test/default.xex", path)
                stat(path, size = 4096L)
            },
        )

        val result = probe.verify(filesystem, game(executable = "  default.xex  "))

        assertTrue(result.verified)
    }

    @Test
    fun `directory where executable should be is an error`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = {
                listing(entry("default.xex", type = RemoteObjectType.Directory, size = 0L))
            },
        )

        val result = probe.verify(filesystem, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_IS_DIRECTORY, finding.code)
        assertEquals(IntegritySeverity.Error, finding.severity)
    }

    @Test
    fun `STAT can identify directory after list miss`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { listing(entry("other.bin", size = 128L)) },
            statBlock = { path -> stat(path, type = RemoteObjectType.Directory, size = 0L) },
        )

        val result = probe.verify(filesystem, game())

        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_IS_DIRECTORY, result.findings.single().code)
    }

    @Test
    fun `zero byte executable is an error`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { listing(entry("default.xex", size = 0L)) },
        )

        val result = probe.verify(filesystem, game())

        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_EMPTY, result.findings.single().code)
    }

    @Test
    fun `missing file is only declared after complete readable directory returned other entries`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { listing(entry("other.bin", size = 128L)) },
            statBlock = { null },
        )

        val result = probe.verify(filesystem, game())

        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_MISSING, result.findings.single().code)
        assertEquals(IntegritySeverity.Error, result.findings.single().severity)
        assertEquals(1, filesystem.listCalls)
        assertEquals(1, filesystem.statCalls)
    }

    @Test
    fun `bounded listing never proves executable missing when STAT cannot confirm`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = {
                RemoteDirectoryListing(
                    entries = listOf(entry("other.bin", size = 128L)),
                    limitReached = true,
                )
            },
            statBlock = { null },
        )

        val result = probe.verify(filesystem, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_DIRECTORY_LIMIT_REACHED, finding.code)
        assertEquals(IntegritySeverity.Info, finding.severity)
        assertFalse(finding.code.contains("missing"))
    }

    @Test
    fun `STAT still verifies executable after bounded list`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = {
                RemoteDirectoryListing(
                    entries = listOf(entry("other.bin", size = 128L)),
                    limitReached = true,
                )
            },
            statBlock = { path -> stat(path, size = 8192L) },
        )

        val result = probe.verify(filesystem, game())

        assertTrue(result.verified)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `empty parsed listing without STAT stays informational`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { listing() },
            statBlock = { null },
        )

        val result = probe.verify(filesystem, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_EMPTY_OR_UNPARSED_LIST, finding.code)
        assertEquals(IntegritySeverity.Info, finding.severity)
    }

    @Test
    fun `STAT can verify executable when list fails`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { error("LIST unsupported") },
            statBlock = { path -> stat(path, size = 8192L) },
        )

        val result = probe.verify(filesystem, game())

        assertTrue(result.verified)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.message.contains("STAT"))
    }

    @Test
    fun `transport failure stays informational instead of degrading game health`() = runBlocking {
        val filesystem = FakeFilesystem(
            listBlock = { error("connection reset") },
            statBlock = { throw IllegalStateException("timeout") },
        )

        val result = probe.verify(filesystem, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_DIRECTORY_UNREADABLE, finding.code)
        assertEquals(IntegritySeverity.Info, finding.severity)
        assertFalse(finding.code.contains("missing"))
    }

    @Test
    fun `unsafe local executable blocks all remote commands`() = runBlocking {
        val filesystem = FakeFilesystem()
        val result = probe.verify(filesystem, game(executable = "folder/default.xex"))

        assertFalse(result.verified)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, filesystem.listCalls)
        assertEquals(0, filesystem.statCalls)
    }

    @Test
    fun `cancellation from read-only source is propagated`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                probe.verify(
                    FakeFilesystem(listBlock = { throw CancellationException("cancel") }),
                    game(),
                )
            }
        }
    }

    private fun game(executable: String = "default.xex") = GameEntry(
        databaseId = 1L,
        titleId = 0x465307E4L,
        mediaId = 0x12345678L,
        discNumber = 1,
        title = "Test Game",
        directory = "Games/Test",
        executable = executable,
        baseVersion = null,
        contentRoot = "/Hdd1",
    )

    private fun listing(vararg entries: RemoteDirectoryEntry) =
        RemoteDirectoryListing(entries = entries.toList())

    private fun entry(
        name: String,
        type: RemoteObjectType = RemoteObjectType.File,
        size: Long,
    ) = RemoteDirectoryEntry(
        name = name,
        canonicalPath = "/Hdd1/Games/Test/$name",
        objectType = type,
        sizeBytes = size,
    )

    private fun stat(
        path: String,
        type: RemoteObjectType = RemoteObjectType.File,
        size: Long,
    ) = RemoteObjectStat(
        canonicalPath = path,
        objectType = type,
        sizeBytes = size,
    )

    private class FakeFilesystem(
        private val listBlock: suspend (String) -> RemoteDirectoryListing = { RemoteDirectoryListing(emptyList()) },
        private val statBlock: suspend (String) -> RemoteObjectStat? = { null },
    ) : RemoteReadOnlyFilesystem {
        var listCalls = 0
        var statCalls = 0

        override suspend fun list(canonicalDirectory: String): RemoteDirectoryListing {
            listCalls += 1
            return listBlock(canonicalDirectory)
        }

        override suspend fun stat(canonicalPath: String): RemoteObjectStat? {
            statCalls += 1
            return statBlock(canonicalPath)
        }
    }
}
