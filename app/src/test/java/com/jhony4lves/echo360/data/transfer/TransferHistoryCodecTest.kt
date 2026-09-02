package com.jhony4lves.echo360.data.transfer

import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.domain.transfer.TransferHistoryEntry
import com.jhony4lves.echo360.network.ftp.FtpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferHistoryCodecTest {
    @Test
    fun `round trips terminal transfer metadata`() {
        val entry = sampleEntry(
            id = "run-1",
            remoteRoot = "/Hdd1/Games/Dark Souls II | PT-BR",
            failedFile = "Content/arquivo com espaço.bin",
            fallbackReason = "Fast → Background: conexão caiu",
            message = "Falha controlada | sem apagar nada",
        )

        val decoded = TransferHistoryCodec.decode(TransferHistoryCodec.encode(listOf(entry)))

        assertEquals(listOf(entry), decoded)
        assertTrue(decoded.single().usedFallback)
    }

    @Test
    fun `preserves newest first order`() {
        val newest = sampleEntry(id = "new", startedAt = 3_000L)
        val middle = sampleEntry(id = "middle", startedAt = 2_000L)
        val oldest = sampleEntry(id = "old", startedAt = 1_000L)

        val decoded = TransferHistoryCodec.decode(
            TransferHistoryCodec.encode(listOf(newest, middle, oldest)),
        )

        assertEquals(listOf("new", "middle", "old"), decoded.map { it.id })
    }

    @Test
    fun `ignores corrupt records without losing valid history`() {
        val valid = sampleEntry(id = "valid")
        val payload = TransferHistoryCodec.encode(listOf(valid)) +
            "\nnot-a-valid-history-record\n" +
            "9|unsupported"

        val decoded = TransferHistoryCodec.decode(payload)

        assertEquals(listOf(valid), decoded)
    }

    @Test
    fun `derives duration speed and fallback state`() {
        val entry = sampleEntry(
            startedAt = 1_000L,
            finishedAt = 3_000L,
            transferredBytes = 4_000L,
        )

        assertEquals(2_000L, entry.durationMs)
        assertEquals(2_000L, entry.averageBytesPerSecond)
        assertTrue(entry.usedFallback)

        val fastOnly = entry.copy(requestedRoute = FtpRoute.Fast, fallbackReason = null)
        assertFalse(fastOnly.usedFallback)
    }

    private fun sampleEntry(
        id: String,
        startedAt: Long = 1_000L,
        finishedAt: Long = 2_500L,
        transferredBytes: Long = 1_024L,
        remoteRoot: String = "/Hdd1/Games",
        failedFile: String? = null,
        fallbackReason: String? = "Fast → Background: timeout",
        message: String? = "Concluído",
    ) = TransferHistoryEntry(
        id = id,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        requestedRoute = FtpRoute.Auto,
        usedRoute = FtpRoute.Background,
        status = TransferExecutionStatus.Completed,
        fileCount = 3,
        verifiedFiles = 3,
        transferredBytes = transferredBytes,
        totalBytes = 1_024L,
        retryCount = 2,
        remoteRoot = remoteRoot,
        failedFile = failedFile,
        fallbackReason = fallbackReason,
        message = message,
    )
}
