package com.jhony4lves.echo360.domain.transfer

import com.jhony4lves.echo360.network.ftp.FtpRoute
import kotlin.math.roundToLong

data class TransferHistoryEntry(
    val id: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val requestedRoute: FtpRoute,
    val usedRoute: FtpRoute?,
    val status: TransferExecutionStatus,
    val fileCount: Int,
    val verifiedFiles: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val retryCount: Int,
    val remoteRoot: String,
    val failedFile: String? = null,
    val fallbackReason: String? = null,
    val message: String? = null,
) {
    val durationMs: Long
        get() = (finishedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)

    val averageBytesPerSecond: Long
        get() {
            if (durationMs <= 0L || transferredBytes <= 0L) return 0L
            return (transferredBytes.toDouble() * 1_000.0 / durationMs.toDouble())
                .roundToLong()
                .coerceAtLeast(0L)
        }

    val usedFallback: Boolean
        get() = fallbackReason != null && requestedRoute == FtpRoute.Auto
}
