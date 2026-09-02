package com.jhony4lves.echo360.domain.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XboxProfileTest {
    @Test
    fun `EchoLink defaults to published port 36000`() {
        assertEquals(36_000, XboxEndpoint().echoLinkPort)
    }

    @Test
    fun `endpoint validation rejects invalid EchoLink port`() {
        assertThrows(IllegalArgumentException::class.java) {
            XboxEndpoint(host = "192.168.1.18", echoLinkPort = 0).validated()
        }
    }

    @Test
    fun `console is reachable when only EchoCore completes protocol health`() {
        val connectedEchoCore = TransportHealth(
            transport = XboxTransport.EchoCore,
            status = TransportStatus.Connected,
            detail = "PONG v1",
            latencyMs = 5L,
        )
        val unavailableNova = health(XboxTransport.Nova)
        val unavailableAurora = health(XboxTransport.AuroraFtp)
        val unavailableFtpDll = health(XboxTransport.FtpDll)

        val snapshot = XboxConnectionSnapshot(
            echoCore = connectedEchoCore,
            nova = unavailableNova,
            auroraFtp = unavailableAurora,
            ftpDll = unavailableFtpDll,
            checkedAtEpochMs = 1L,
        )

        assertTrue(snapshot.consoleReachable)
    }

    private fun health(transport: XboxTransport) = TransportHealth(
        transport = transport,
        status = TransportStatus.Unreachable,
        detail = "offline",
    )
}
