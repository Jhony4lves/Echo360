package com.jhony4lves.echo360.domain.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XboxProfileTest {
    @Test
    fun `active endpoint defaults match Aurora and FTPdll ports`() {
        val endpoint = XboxEndpoint()
        assertEquals(9999, endpoint.novaPort)
        assertEquals(21, endpoint.auroraFtpPort)
        assertEquals(7564, endpoint.ftpDllPort)
    }

    @Test
    fun `endpoint validation rejects invalid active port`() {
        assertThrows(IllegalArgumentException::class.java) {
            XboxEndpoint(host = "192.168.1.18", ftpDllPort = 0).validated()
        }
    }

    @Test
    fun `console is reachable when any active provider is connected`() {
        val snapshot = XboxConnectionSnapshot(
            nova = health(XboxTransport.Nova),
            auroraFtp = TransportHealth(
                transport = XboxTransport.AuroraFtp,
                status = TransportStatus.Connected,
                detail = "LIST ok",
                latencyMs = 5L,
            ),
            ftpDll = health(XboxTransport.FtpDll),
            checkedAtEpochMs = 1L,
        )

        assertTrue(snapshot.consoleReachable)
        assertTrue(snapshot.fileTransportReachable)
    }

    @Test
    fun `NOVA alone marks console reachable but not file transport`() {
        val snapshot = XboxConnectionSnapshot(
            nova = TransportHealth(
                transport = XboxTransport.Nova,
                status = TransportStatus.Connected,
                detail = "NOVA ok",
            ),
            auroraFtp = health(XboxTransport.AuroraFtp),
            ftpDll = health(XboxTransport.FtpDll),
            checkedAtEpochMs = 1L,
        )

        assertTrue(snapshot.consoleReachable)
        assertFalse(snapshot.fileTransportReachable)
    }

    private fun health(transport: XboxTransport) = TransportHealth(
        transport = transport,
        status = TransportStatus.Unreachable,
        detail = "offline",
    )
}
