package com.jhony4lves.echo360.data.tu

import com.jhony4lves.echo360.domain.tu.TitleUpdateLocation
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

class EchoTuScannerTest {
    @Test
    fun `content folder keeps only lowercase tu candidates and authoritative sizes`() = runBlocking {
        val path = EchoTuScanner.contentDirectory("465307E4")
        val session = FakeSession(
            listings = mapOf(
                path to listOf(
                    file("tu00000008_00000000", "$path/tu00000008_00000000", 999L),
                    file("TU_OLD", "$path/TU_OLD", 999L),
                    file("notes.txt", "$path/notes.txt", 999L),
                    dir("sub", "$path/sub"),
                ),
            ),
            sizes = mapOf("$path/tu00000008_00000000" to 1234L),
        )

        val result = EchoTuScanner().scanContentFolder(session, "465307e4")

        assertTrue(result.available)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals("tu00000008_00000000", candidate.fileName)
        assertEquals(1234L, candidate.sizeBytes)
        assertEquals("465307E4", candidate.titleIdHex)
        assertEquals(TitleUpdateLocation.ContentFolder, candidate.location)
        assertEquals(0, session.mutationCalls)
        assertEquals(0, session.downloadCalls)
    }

    @Test
    fun `legacy cache keeps only uppercase TU underscore and cannot assign title id`() = runBlocking {
        val path = EchoTuScanner.LEGACY_CACHE_DIRECTORY
        val session = FakeSession(
            listings = mapOf(
                path to listOf(
                    file("TU_10LC20O_0000008000000.00000000000O2", "$path/TU_10LC20O_0000008000000.00000000000O2", 42L),
                    file("tu00000008_00000000", "$path/tu00000008_00000000", 7L),
                ),
            ),
            sizes = emptyMap(),
        )

        val result = EchoTuScanner().scanLegacyCache(session)

        assertEquals(1, result.candidates.size)
        assertNull(result.candidates.single().titleIdHex)
        assertNull(result.candidates.single().sizeBytes)
        assertEquals(0, session.mutationCalls)
        assertEquals(0, session.downloadCalls)
    }

    @Test
    fun `candidate limit is explicit instead of silently scanning unbounded results`() = runBlocking {
        val path = EchoTuScanner.contentDirectory("545408A7")
        val session = FakeSession(
            listings = mapOf(
                path to (0 until 5).map { index ->
                    file("tu${index.toString().padStart(8, '0')}", "$path/tu${index.toString().padStart(8, '0')}", 1L)
                },
            ),
            sizes = emptyMap(),
        )

        val result = EchoTuScanner(maxCandidatesPerSource = 2)
            .scanContentFolder(session, "545408A7")

        assertTrue(result.limitReached)
        assertEquals(2, result.candidates.size)
        assertEquals(0, session.mutationCalls)
        assertEquals(0, session.downloadCalls)
    }

    @Test
    fun `missing source is unavailable rather than evidence of no title update`() = runBlocking {
        val session = FakeSession(
            listings = emptyMap(),
            sizes = emptyMap(),
            failUnknownLists = true,
        )

        val result = EchoTuScanner().scanContentFolder(session, "415608A7")

        assertFalse(result.available)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.detail?.contains("550") == true)
    }

    private fun file(name: String, path: String, size: Long) = RemoteEntry(name, path, false, size)
    private fun dir(name: String, path: String) = RemoteEntry(name, path, true, 0L)

    private class FakeSession(
        private val listings: Map<String, List<RemoteEntry>>,
        private val sizes: Map<String, Long>,
        private val failUnknownLists: Boolean = false,
    ) : XboxFtpSession {
        var mutationCalls = 0
        var downloadCalls = 0

        override suspend fun list(canonicalPath: String): List<RemoteEntry> {
            if (failUnknownLists && canonicalPath !in listings) error("550 pasta ausente")
            return listings[canonicalPath].orEmpty()
        }

        override suspend fun size(canonicalPath: String): Long? = sizes[canonicalPath]

        override suspend fun ensureDirectory(canonicalPath: String) {
            mutationCalls += 1
            error("mutation not allowed")
        }

        override suspend fun upload(
            canonicalPath: String,
            source: InputStream,
            onProgress: (Long) -> Unit,
        ) {
            mutationCalls += 1
            error("mutation not allowed")
        }

        override suspend fun download(
            canonicalPath: String,
            destination: OutputStream,
            onProgress: (Long) -> Unit,
        ) {
            downloadCalls += 1
            error("download not allowed")
        }

        override suspend fun close() = Unit
    }
}
