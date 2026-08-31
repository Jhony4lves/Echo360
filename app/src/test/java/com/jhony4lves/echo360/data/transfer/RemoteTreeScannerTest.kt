package com.jhony4lves.echo360.data.transfer

import com.jhony4lves.echo360.domain.transfer.LocalTransferFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.network.ftp.FtpPathNotFoundException
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class RemoteTreeScannerTest {
    @Test
    fun `missing destination root is treated as empty remote tree`() = runBlocking {
        val root = "/Hdd1/Echo360/TransferTest"
        val session = object : XboxFtpSession {
            override suspend fun list(canonicalPath: String): List<RemoteEntry> {
                throw FtpPathNotFoundException(
                    canonicalPath = canonicalPath,
                    serverReply = "550 \"/fHdd/Echo360/TransferTest\": Path not found.",
                )
            }

            override suspend fun size(canonicalPath: String): Long? = null
            override suspend fun ensureDirectory(canonicalPath: String) = Unit
            override suspend fun upload(canonicalPath: String, source: InputStream, onProgress: (Long) -> Unit) = Unit
            override suspend fun download(canonicalPath: String, destination: OutputStream, onProgress: (Long) -> Unit) = Unit
            override suspend fun close() = Unit
        }

        val localTree = LocalTransferTree(
            rootUri = "content://echo360/test",
            rootName = "Echo360TransferTest",
            files = listOf(
                LocalTransferFile(
                    relativePath = "echo360_1mb.bin",
                    size = 1_048_576,
                    contentUri = "content://echo360/test/echo360_1mb.bin",
                ),
            ),
            directories = setOf(""),
        )

        val result = RemoteTreeScanner().scanForLocalTree(
            session = session,
            canonicalRemoteRoot = root,
            localTree = localTree,
        )

        assertTrue(result.isEmpty())
    }
}
