package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class RemoteGameIntegrityProbeTest {
    private val probe = RemoteGameIntegrityProbe()

    @Test
    fun `verifies executable from readable directory listing`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(RemoteEntry("default.xex", "/Hdd1/Games/Test/default.xex", false, 4096L))
            },
        )

        val result = probe.verify(session, game())

        assertTrue(result.verified)
        assertTrue(result.findings.isEmpty())
        assertEquals(1, session.listCalls)
        assertEquals(0, session.sizeCalls)
    }

    @Test
    fun `case difference in remote filename is accepted`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(RemoteEntry("DEFAULT.XEX", "/Hdd1/Games/Test/DEFAULT.XEX", false, 2048L))
            },
        )

        assertTrue(probe.verify(session, game()).verified)
    }

    @Test
    fun `trimmed executable name is reused for SIZE path`() = runBlocking {
        val session = FakeSession(
            listBlock = { error("LIST unsupported") },
            sizeBlock = { path ->
                assertEquals("/Hdd1/Games/Test/default.xex", path)
                4096L
            },
        )

        val result = probe.verify(session, game(executable = "  default.xex  "))

        assertTrue(result.verified)
    }

    @Test
    fun `directory where executable should be is an error`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(RemoteEntry("default.xex", "/Hdd1/Games/Test/default.xex", true, 0L))
            },
        )

        val result = probe.verify(session, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_IS_DIRECTORY, finding.code)
        assertEquals(IntegritySeverity.Error, finding.severity)
    }

    @Test
    fun `zero byte executable is an error`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(RemoteEntry("default.xex", "/Hdd1/Games/Test/default.xex", false, 0L))
            },
        )

        val result = probe.verify(session, game())

        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_EMPTY, result.findings.single().code)
    }

    @Test
    fun `missing file is only declared after readable directory returned other entries`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(RemoteEntry("other.bin", "/Hdd1/Games/Test/other.bin", false, 128L))
            },
            sizeBlock = { null },
        )

        val result = probe.verify(session, game())

        assertEquals(RemoteGameIntegrityProbe.CODE_EXECUTABLE_MISSING, result.findings.single().code)
        assertEquals(IntegritySeverity.Error, result.findings.single().severity)
        assertEquals(1, session.listCalls)
        assertEquals(1, session.sizeCalls)
    }

    @Test
    fun `empty parsed listing without SIZE stays informational`() = runBlocking {
        val session = FakeSession(
            listBlock = { emptyList() },
            sizeBlock = { null },
        )

        val result = probe.verify(session, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_EMPTY_OR_UNPARSED_LIST, finding.code)
        assertEquals(IntegritySeverity.Info, finding.severity)
    }

    @Test
    fun `SIZE can verify executable when LIST fails`() = runBlocking {
        val session = FakeSession(
            listBlock = { error("LIST unsupported") },
            sizeBlock = { 8192L },
        )

        val result = probe.verify(session, game())

        assertTrue(result.verified)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.message.contains("SIZE"))
    }

    @Test
    fun `transport failure stays informational instead of degrading game health`() = runBlocking {
        val session = FakeSession(
            listBlock = { error("connection reset") },
            sizeBlock = { throw IllegalStateException("timeout") },
        )

        val result = probe.verify(session, game())
        val finding = result.findings.single()

        assertFalse(result.verified)
        assertEquals(RemoteGameIntegrityProbe.CODE_DIRECTORY_UNREADABLE, finding.code)
        assertEquals(IntegritySeverity.Info, finding.severity)
        assertFalse(finding.code.contains("missing"))
    }

    @Test
    fun `unsafe local executable blocks all remote commands`() = runBlocking {
        val session = FakeSession()
        val result = probe.verify(session, game(executable = "folder/default.xex"))

        assertFalse(result.verified)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, session.listCalls)
        assertEquals(0, session.sizeCalls)
    }

    @Test
    fun `cancellation from FTP is propagated`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                probe.verify(
                    FakeSession(listBlock = { throw CancellationException("cancel") }),
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

    private class FakeSession(
        private val listBlock: suspend (String) -> List<RemoteEntry> = { emptyList() },
        private val sizeBlock: suspend (String) -> Long? = { null },
    ) : XboxFtpSession {
        var listCalls = 0
        var sizeCalls = 0

        override suspend fun list(canonicalPath: String): List<RemoteEntry> {
            listCalls += 1
            return listBlock(canonicalPath)
        }

        override suspend fun size(canonicalPath: String): Long? {
            sizeCalls += 1
            return sizeBlock(canonicalPath)
        }

        override suspend fun ensureDirectory(canonicalPath: String) =
            error("Mutation must never be called by integrity probe")

        override suspend fun upload(
            canonicalPath: String,
            source: InputStream,
            onProgress: (Long) -> Unit,
        ) = error("Mutation must never be called by integrity probe")

        override suspend fun download(
            canonicalPath: String,
            destination: OutputStream,
            onProgress: (Long) -> Unit,
        ) = error("Download is not needed by v1 integrity probe")

        override suspend fun close() = Unit
    }
}
