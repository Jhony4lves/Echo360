package com.jhony4lves.echo360.domain.transfer

import com.jhony4lves.echo360.network.ftp.FtpRoute
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferExecutionStatus {
    Preparing,
    Uploading,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

class TransferCancellationToken {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

data class TransferExecutionProgress(
    val status: TransferExecutionStatus,
    val route: FtpRoute? = null,
    val currentFile: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val currentFileBytes: Long = 0L,
    val currentFileSize: Long = 0L,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val verifiedFiles: Int = 0,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val fallbackReason: String? = null,
    val message: String? = null,
) {
    val logicalBytesTransferred: Long
        get() = (completedBytes + currentFileBytes).coerceAtMost(totalBytes)

    val overallFraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (logicalBytesTransferred.toDouble() / totalBytes.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()

    val currentFileFraction: Float
        get() = if (currentFileSize <= 0L) 0f
        else (currentFileBytes.toDouble() / currentFileSize.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
}

data class TransferExecutionResult(
    val status: TransferExecutionStatus,
    val route: FtpRoute?,
    val uploadedFiles: Int,
    val verifiedFiles: Int,
    val transferredBytes: Long,
    val fallbackReason: String? = null,
    val failedFile: String? = null,
    val message: String? = null,
) {
    val succeeded: Boolean get() = status == TransferExecutionStatus.Completed
}
