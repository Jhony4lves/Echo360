package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FtpAutoRouterTest {
    private val profile = XboxProfile(endpoint = XboxEndpoint(host = "192.168.1.18"))

    @Test
    fun `Auto selects FTPdll when measured upload is faster`() = runBlocking {
        var clockNanos = 0L
        val fast = FakeSession(uploadElapsedNanos = 10_000_000L) { clockNanos += it }
        val background = FakeSession(uploadElapsedNanos = 2_000_000L) { clockNanos += it }
        val router = FtpAutoRouter(
            benchmarkBytes = 1024,
            cacheTtlMs = 60_000L,
            nowMs = { 1_000L },
            nanoTime = { clockNanos },
            token = { "fixedtoken" },
        )

        val routed = router.connect(
            profile = profile,
            fastConnector = { fast },
            backgroundConnector = { background },
        )

        assertEquals(FtpRoute.Background, routed.route)
        assertTrue(fast.closed)
        assertFalse(background.closed)
        assertEquals(1, fast.uploadCalls)
        assertEquals(1, background.uploadCalls)
        assertEquals(1, fast.deleteCalls)
        assertEquals(1, background.deleteCalls)
        assertTrue(checkNotNull(routed.benchmark).selected.bytesPerSecond > checkNotNull(routed.benchmark).alternate!!.bytesPerSecond)
    }

    @Test
    fun `Auto keeps the only healthy provider`() = runBlocking {
        var clockNanos = 0L
        val background = FakeSession(uploadElapsedNanos = 2_000_000L) { clockNanos += it }
        val router = FtpAutoRouter(
            benchmarkBytes = 1024,
            cacheTtlMs = 60_000L,
            nowMs = { 1_000L },
            nanoTime = { clockNanos },
            token = { "fixedtoken" },
        )

        val routed = router.connect(
            profile = profile,
            fastConnector = { throw IOException("porta 21 fechada") },
            backgroundConnector = { background },
        )

        assertEquals(FtpRoute.Background, routed.route)
        assertTrue(routed.fallbackReason.orEmpty().contains("Aurora"))
        assertEquals(1, background.uploadCalls)
    }

    @Test
    fun `fresh cache reconnects winner without benchmarking again`() = runBlocking {
        var clockNanos = 0L
        var fastConnects = 0
        var backgroundConnects = 0
        var fastUploads = 0
        var backgroundUploads = 0
        val router = FtpAutoRouter(
            benchmarkBytes = 1024,
            cacheTtlMs = 60_000L,
            nowMs = { 1_000L },
            nanoTime = { clockNanos },
            token = { "fixedtoken" },
        )

        suspend fun newFast(): XboxFtpSession {
            fastConnects += 1
            return FakeSession(
                uploadElapsedNanos = 1_000_000L,
                advanceClock = { clockNanos += it },
                onUpload = { fastUploads += 1 },
            )
        }

        suspend fun newBackground(): XboxFtpSession {
            backgroundConnects += 1
            return FakeSession(
                uploadElapsedNanos = 4_000_000L,
                advanceClock = { clockNanos += it },
                onUpload = { backgroundUploads += 1 },
            )
        }

        val first = router.connect(profile, ::newFast, ::newBackground)
        first.session.close()
        val second = router.connect(profile, ::newFast, ::newBackground)

        assertEquals(FtpRoute.Fast, first.route)
        assertEquals(FtpRoute.Fast, second.route)
        assertEquals(2, fastConnects)
        assertEquals(1, backgroundConnects)
        assertEquals(1, fastUploads)
        assertEquals(1, backgroundUploads)
        assertEquals(first.benchmark, second.benchmark)
    }

    @Test(expected = IOException::class)
    fun `Auto fails when neither provider is available`() = runBlocking {
        val router = FtpAutoRouter(
            benchmarkBytes = 1024,
            nowMs = { 1_000L },
            nanoTime = { 0L },
        )

        router.connect(
            profile = profile,
            fastConnector = { throw IOException("Aurora offline") },
            backgroundConnector = { throw IOException("FTPdll offline") },
        )
    }

    private class FakeSession(
        private val uploadElapsedNanos: Long = 1_000_000L,
        private val advanceClock: (Long) -> Unit = {},
        private val onUpload: () -> Unit = {},
    ) : XboxFtpSession {
        var uploadedSize: Long? = null
        var uploadCalls = 0
        var deleteCalls = 0
        var closed = false

        override suspend fun list(canonicalPath: String): List<RemoteEntry> = emptyList()

        override suspend fun size(canonicalPath: String): Long? = uploadedSize

        override suspend fun ensureDirectory(canonicalPath: String) = Unit

        override suspend fun delete(canonicalPath: String) {
            deleteCalls += 1
            uploadedSize = null
        }

        override suspend fun upload(
            canonicalPath: String,
            source: InputStream,
            onProgress: (Long) -> Unit,
        ) {
            uploadCalls += 1
            onUpload()
            var total = 0L
            val buffer = ByteArray(256)
            source.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    onProgress(total)
                }
            }
            uploadedSize = total
            advanceClock(uploadElapsedNanos)
        }

        override suspend fun download(
            canonicalPath: String,
            destination: OutputStream,
            onProgress: (Long) -> Unit,
        ) = Unit

        override suspend fun close() {
            closed = true
        }
    }
}
