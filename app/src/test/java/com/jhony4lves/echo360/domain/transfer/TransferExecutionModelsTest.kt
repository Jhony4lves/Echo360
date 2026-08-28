package com.jhony4lves.echo360.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferExecutionModelsTest {
    @Test
    fun overallProgressCombinesCompletedAndCurrentFileBytes() {
        val progress = TransferExecutionProgress(
            status = TransferExecutionStatus.Uploading,
            completedBytes = 400L,
            currentFileBytes = 100L,
            currentFileSize = 300L,
            totalBytes = 1_000L,
        )

        assertEquals(500L, progress.logicalBytesTransferred)
        assertEquals(0.5f, progress.overallFraction, 0.0001f)
        assertEquals(1f / 3f, progress.currentFileFraction, 0.0001f)
    }

    @Test
    fun progressNeverExceedsOneHundredPercent() {
        val progress = TransferExecutionProgress(
            status = TransferExecutionStatus.Verifying,
            completedBytes = 900L,
            currentFileBytes = 500L,
            currentFileSize = 100L,
            totalBytes = 1_000L,
        )

        assertEquals(1_000L, progress.logicalBytesTransferred)
        assertEquals(1f, progress.overallFraction, 0.0001f)
        assertEquals(1f, progress.currentFileFraction, 0.0001f)
    }

    @Test
    fun cancellationTokenIsExplicitAndSticky() {
        val token = TransferCancellationToken()
        assertFalse(token.isCancelled())

        token.cancel()

        assertTrue(token.isCancelled())
    }
}
