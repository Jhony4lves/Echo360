package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class FtpReadOnlyFilesystemTest {
    @Test
    fun `LIST maps FTP entries into transport-neutral metadata`() = runBlocking {
        val session = FakeSession(
            listBlock = {
                listOf(
                    RemoteEntry("Content", "/Hdd1/Content", true, 0L),
                    RemoteEntry("default.xex", "/Hdd1/default.xex", false, 4096L),
                )
            },
        )
        val filesystem = FtpReadOnlyFilesystem(session)

        val listing = filesystem.list("/Hdd1")

        assertFalse(listing.limitReached)
        assertEquals(2, listing.entries.size)
        assertEquals(RemoteObjectType.Directory, listing.entries[0].objectType)
        assertEquals(RemoteObjectType.File, listing.entries[1].objectType)
        assertEquals(4096L, listing.entries[1].sizeBytes)
    }

    @Test
    fun `SIZE maps to file STAT and null remains not confirmed`() = runBlocking {
        val session = FakeSession(sizeBlock = { path -> if (path.endsWith("default.xex")) 8192L else null })
        val filesystem = FtpReadOnlyFilesystem(session)

        val found = filesystem.stat("/Hdd1/Games/Test/default.xex")
        val missing = filesystem.stat("/Hdd1/Games/Test/missing.xex")

        assertEquals(RemoteObjectType.File, found?.objectType)
        assertEquals(8192L, found?.sizeBytes)
        assertNull(missing)
        assertTrue(session.sizeCalls == 2)
    }

    private class FakeSession(
        private val listBlock: suspend (String) -> List<RemoteEntry> = { emptyList() },
        private val sizeBlock: suspend (String) -> Long? = { null },
    ) : XboxFtpSession {
        var sizeCalls = 0

        override suspend fun list(canonicalPath: String): List<RemoteEntry> = listBlock(canonicalPath)

        override suspend fun size(canonicalPath: String): Long? {
            sizeCalls += 1
            return sizeBlock(canonicalPath)
        }

        override suspend fun ensureDirectory(canonicalPath: String) = error("read-only test")

        override suspend fun upload(
            canonicalPath: String,
            source: InputStream,
            onProgress: (Long) -> Unit,
        ) = error("read-only test")

        override suspend fun download(
            canonicalPath: String,
            destination: OutputStream,
            onProgress: (Long) -> Unit,
        ) = error("read-only test")

        override suspend fun close() = Unit
    }
}
