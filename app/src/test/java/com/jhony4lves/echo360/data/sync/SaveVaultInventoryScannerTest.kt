package com.jhony4lves.echo360.data.sync

import com.jhony4lves.echo360.domain.sync.SaveVaultLimits
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class SaveVaultInventoryScannerTest {
    @Test
    fun `scanner inventories recursively and trusts SIZE rather than LIST size`() = runBlocking {
        val session = FakeSession(
            listings = mapOf(
                "/Hdd1/Content/Profile" to listOf(
                    dir("slot1", "/Hdd1/Content/Profile/slot1"),
                    file("settings.dat", "/Hdd1/Content/Profile/settings.dat", 999L),
                ),
                "/Hdd1/Content/Profile/slot1" to listOf(
                    file("save.bin", "/Hdd1/Content/Profile/slot1/save.bin", 999L),
                ),
            ),
            sizes = mapOf(
                "/Hdd1/Content/Profile/settings.dat" to 7L,
                "/Hdd1/Content/Profile/slot1/save.bin" to 3L,
            ),
        )

        val inventory = SaveVaultInventoryScanner().scan(
            session = session,
            sourceRoot = "/Hdd1/Content/Profile",
            route = FtpRoute.Fast,
        )

        assertEquals(2, inventory.directoryCount)
        assertEquals(2, inventory.fileCount)
        assertEquals(10L, inventory.totalBytes)
        assertEquals(listOf("settings.dat", "slot1/save.bin"), inventory.files.map { it.relativePath })
    }

    @Test
    fun `scanner refuses missing SIZE instead of undercounting`() {
        val session = FakeSession(
            listings = mapOf(
                "/Hdd1/Content/Profile" to listOf(file("save.bin", "/Hdd1/Content/Profile/save.bin", 0L)),
            ),
            sizes = emptyMap(),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                SaveVaultInventoryScanner().scan(session, "/Hdd1/Content/Profile", FtpRoute.Fast)
            }
        }
    }

    @Test
    fun `scanner stops before download-sized tree exceeds byte bound`() {
        val session = FakeSession(
            listings = mapOf(
                "/Hdd1/Content/Profile" to listOf(
                    file("a.bin", "/Hdd1/Content/Profile/a.bin", 0L),
                    file("b.bin", "/Hdd1/Content/Profile/b.bin", 0L),
                ),
            ),
            sizes = mapOf(
                "/Hdd1/Content/Profile/a.bin" to 6L,
                "/Hdd1/Content/Profile/b.bin" to 6L,
            ),
        )
        val scanner = SaveVaultInventoryScanner(
            SaveVaultLimits(maxFiles = 10, maxDirectories = 10, maxBytes = 10L, maxDepth = 4),
        )

        assertThrows(SaveVaultLimitExceededException::class.java) {
            runBlocking { scanner.scan(session, "/Hdd1/Content/Profile", FtpRoute.Fast) }
        }
        assertEquals(0, session.downloadCalls)
    }

    @Test
    fun `scanner rejects malicious server entry name`() {
        val session = FakeSession(
            listings = mapOf(
                "/Hdd1/Content/Profile" to listOf(dir("..", "/Hdd1/Content")),
            ),
            sizes = emptyMap(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SaveVaultInventoryScanner().scan(session, "/Hdd1/Content/Profile", FtpRoute.Fast) }
        }
    }

    private fun dir(name: String, path: String) = RemoteEntry(name, path, true, 0L)
    private fun file(name: String, path: String, size: Long) = RemoteEntry(name, path, false, size)

    private class FakeSession(
        private val listings: Map<String, List<RemoteEntry>>,
        private val sizes: Map<String, Long>,
    ) : XboxFtpSession {
        var downloadCalls: Int = 0

        override suspend fun list(canonicalPath: String): List<RemoteEntry> = listings[canonicalPath].orEmpty()
        override suspend fun size(canonicalPath: String): Long? = sizes[canonicalPath]
        override suspend fun ensureDirectory(canonicalPath: String) = error("mutation not allowed")
        override suspend fun upload(canonicalPath: String, source: InputStream, onProgress: (Long) -> Unit) = error("mutation not allowed")
        override suspend fun download(canonicalPath: String, destination: OutputStream, onProgress: (Long) -> Unit) {
            downloadCalls += 1
        }
        override suspend fun close() = Unit
    }
}
