package com.jhony4lves.echo360.network.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

class FtpRetryPolicyTest {
    private val policy = FtpRetryPolicy()

    @Test
    fun `retries transient transport failures`() {
        assertTrue(policy.isTransient(FtpStageTimeoutException("STOR", SocketTimeoutException("timeout"))))
        assertTrue(policy.isTransient(FtpControlConnectException("127.0.0.1", 21, ConnectException("refused"))))
        assertTrue(policy.isTransient(SocketException("Connection reset")))
        assertTrue(policy.isTransient(EOFException("closed")))
    }

    @Test
    fun `retries only temporary FTP replies`() {
        listOf(421, 425, 426, 450, 451).forEach { code ->
            assertTrue("Expected $code to be transient", policy.isTransient(FtpProtocolException(code, "temporary")))
        }

        listOf(500, 530, 550, 552, 553).forEach { code ->
            assertFalse("Expected $code to be terminal", policy.isTransient(FtpProtocolException(code, "permanent")))
        }
    }

    @Test
    fun `does not retry unrelated IO or configuration errors`() {
        assertFalse(policy.isTransient(IOException("local read failed")))
        assertFalse(policy.isTransient(IllegalArgumentException("bad config")))
        assertFalse(policy.isTransient(IllegalStateException("SIZE mismatch")))
    }

    @Test
    fun `finds transient network causes through wrappers`() {
        assertTrue(policy.isTransient(IllegalStateException("wrapped", SocketException("Broken pipe"))))
        assertFalse(policy.isTransient(IllegalStateException("wrapped", IOException("local IO"))))
    }

    @Test
    fun `uses bounded backoff schedule`() {
        assertEquals(2, policy.maxSameRouteRetries)
        assertEquals(350L, policy.delayMsForRetry(1))
        assertEquals(900L, policy.delayMsForRetry(2))
        assertEquals(900L, policy.delayMsForRetry(3))
    }
}
