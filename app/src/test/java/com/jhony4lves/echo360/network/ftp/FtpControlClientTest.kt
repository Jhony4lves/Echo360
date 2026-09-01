package com.jhony4lves.echo360.network.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FtpControlClientTest {
    @Test
    fun `reads single line reply`() {
        val reply = FtpReplyParser.read(
            BufferedReader(StringReader("220 FtpDll Ready\n")),
        )

        assertEquals(220, reply.code)
        assertEquals(listOf("220 FtpDll Ready"), reply.lines)
    }

    @Test
    fun `reads multiline FEAT reply until matching code`() {
        val reply = FtpReplyParser.read(
            BufferedReader(
                StringReader(
                    "211-Extensions supported:\n XCRC filename\n UTF8\n211 END\n",
                ),
            ),
        )

        assertEquals(211, reply.code)
        assertEquals(4, reply.lines.size)
        assertEquals("211 END", reply.lines.last())
    }

    @Test
    fun `recognizes FTPdll path not found response`() {
        val reply = FtpReply(
            code = 550,
            lines = listOf("550 \"/fHdd/Echo360/TransferTest\": Path not found."),
        )

        assertTrue(reply.isMissingPathReply())
    }

    @Test
    fun `does not treat unrelated 550 as missing path`() {
        val reply = FtpReply(
            code = 550,
            lines = listOf("550 Permission denied."),
        )

        assertFalse(reply.isMissingPathReply())
    }

    @Test
    fun `closes control socket when login fails before session is returned`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peerObservedEof = executor.submit<Boolean> {
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        val input = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val output = peer.getOutputStream().bufferedWriter(Charsets.US_ASCII)

                        output.write("220 Test FTP ready\r\n")
                        output.flush()
                        assertEquals("USER xbox", input.readLine())

                        output.write("530 Login incorrect\r\n")
                        output.flush()

                        input.readLine() == null
                    }
                }

                val channel = FtpCommandChannel(
                    host = loopback.hostAddress,
                    port = server.localPort,
                    timeoutMs = 1_500,
                )

                val error = assertThrows(FtpProtocolException::class.java) {
                    channel.connectAndLogin(username = "xbox", password = "xbox")
                }

                assertEquals(530, error.ftpCode)
                assertTrue(
                    "The failed setup must close its control socket.",
                    peerObservedEof.get(4, TimeUnit.SECONDS),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
