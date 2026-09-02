package com.jhony4lves.echo360.network.ftp

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Bounded retry policy for transient FTP transport failures.
 *
 * A retry always restarts the current file from byte zero after the caller
 * closes the broken session and reconnects on the same route. Permanent FTP
 * replies, authentication/configuration errors and integrity mismatches must
 * not be retried by this policy.
 */
class FtpRetryPolicy(
    val maxSameRouteRetries: Int = 2,
    private val backoffMs: List<Long> = listOf(350L, 900L),
) {
    init {
        require(maxSameRouteRetries >= 0) { "maxSameRouteRetries must be >= 0." }
        require(backoffMs.all { it >= 0L }) { "Retry backoff must not be negative." }
    }

    fun isTransient(error: Throwable): Boolean = when (error) {
        is FtpStageTimeoutException -> true
        is FtpControlConnectException -> true
        is SocketTimeoutException -> true
        is SocketException -> true
        is EOFException -> true
        is FtpProtocolException -> error.ftpCode in TRANSIENT_FTP_CODES
        else -> error.cause
            ?.takeIf { it !== error }
            ?.let(::isTransient)
            ?: false
    }

    fun delayMsForRetry(retryNumber: Int): Long {
        require(retryNumber >= 1) { "retryNumber starts at 1." }
        if (backoffMs.isEmpty()) return 0L
        return backoffMs[(retryNumber - 1).coerceAtMost(backoffMs.lastIndex)]
    }

    private companion object {
        val TRANSIENT_FTP_CODES = setOf(
            421, // service unavailable / too many connections
            425, // cannot open data connection
            426, // transfer aborted
            450, // file action temporarily unavailable
            451, // local processing error
        )
    }
}
