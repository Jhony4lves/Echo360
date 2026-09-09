package com.jhony4lves.echo360.data.transfer

import com.jhony4lves.echo360.domain.transfer.LocalTransferFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.network.ftp.FtpProtocolException
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class RemoteTreeScannerTest {
    @Test
    fun `missing destination root is treated as empty remote tree`() = runBlocking {
        val localTree = LocalTransferTree(
            rootUri = "content://local/tree",
            rootName = "Test",
            files = listOf(
                LocalTransferFile("a.bin", 10L, "content://local/a"),
                LocalTransferFile("sub/b.bin", 20L, "content://local/b"),
            ),
            directories = setOf("", "sub"),
        )
        val session = FakeSession(
            listings = emptyMap(),
            failures = mapOf("/Hdd1/Echo360/Test" to FtpProtocolException(550, "pasta ausente")),
        )

        val result = RemoteTreeScanner().scanForLocalTree(
            session = session,
            canonicalRemoteRoot = "/Hdd1/Echo360/Test",
            localTree = localTree,
        )

        assertEquals(emptyList<Any>(), result)
        assertEquals(listOf("/Hdd1/Echo360/Test"), session.listCalls)
    }

    @Test
    fun `missing nested destination folder leaves existing siblings visible`() = runBlocking {
        val root = "/Hdd1/Echo360/Test"
        val localTree = LocalTransferTree(
            rootUri = "content://local/tree",
            rootName = "Test",
            files = listOf(
                LocalTransferFile("root.bin", 7L, "content://local/root"),
                LocalTransferFile("sub/b.bin", 20L, "content://local/b"),
            ),
            directories = setOf("", "sub"),
        )
        val session = FakeSession(
            listings = mapOf(
                root to listOf(
                    RemoteEntry("root.bin", "$root/root.bin", false, 7L),
                    RemoteEntry("sub", "$root/sub", true, 0L),
                ),
            ),
            failures = mapOf("$root/sub" to FtpProtocolException(550, "pasta ausente")),
        )

        val result = RemoteTreeScanner().scanForLocalTree(session, root, localTree)

        assertEquals(listOf("root.bin"), result.map { it.relativePath })
        assertEquals(listOf(root, "$root/sub"), session.listCalls)
    }

    @Test
    fun `non 550 FTP failure is still fatal`() {
        val root = "/Hdd1/Echo360/Test"
        val localTree = LocalTransferTree(
            rootUri = "content://local/tree",
            rootName = "Test",
            files = emptyList(),
            directories = setOf(""),
        )
        val session = FakeSession(
            listings = emptyMap(),
            failures = mapOf(root to FtpProtocolException(421, "servidor ocupado")),
        )

        assertThrows(FtpProtocolException::class.java) {
            runBlocking { RemoteTreeScanner().scanForLocalTree(session, root, localTree) }
        }
    }

    private class FakeSession(
        private val listings: Map<String, List<RemoteEntry>>,
        private val failures: Map<String, Throwable>,
    ) : XboxFtpSession {
        val listCalls = mutableListOf<String>()

        override suspend fun list(canonicalPath: String): List<RemoteEntry> {
            listCalls += canonicalPath
            failures[canonicalPath]?.let { throw it }
            return listings[canonicalPath].orEmpty()
        }

        override suspend fun size(canonicalPath: String): Long? = null
        override suspend fun ensureDirectory(canonicalPath: String) = Unit
        override suspend fun delete(canonicalPath: String) = Unit
        override suspend fun upload(
            canonicalPath: String,
            source: InputStream,
            onProgress: (Long) -> Unit,
        ) = Unit

        override suspend fun download(
            canonicalPath: String,
            destination: OutputStream,
            onProgress: (Long) -> Unit,
        ) = Unit

        override suspend fun close() = Unit
    }
}
