package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxCredentials
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FtpTransportIntegrationTest {
    @Test
    fun `Aurora upload uses passive data channel`() {
        val server = FakeFtpServer.startPassive()
        val payload = "echo360-aurora-passive".toByteArray()
        var lastProgress = 0L

        runBlocking {
            val session = AuroraPassiveFtpSession.connect(
                profile = profile(auroraPort = server.port),
                timeoutMs = 2_000,
            )
            try {
                session.upload(
                    canonicalPath = "/Hdd1/Content/test.bin",
                    source = ByteArrayInputStream(payload),
                ) { sent -> lastProgress = sent }

                assertEquals(payload.size.toLong(), session.size("/Hdd1/Content/test.bin"))
            } finally {
                session.close()
            }
        }

        server.await()

        assertArrayEquals(payload, server.receivedBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        assertTrue(server.commands.any { it == "PASV" })
        assertFalse(server.commands.any { it.startsWith("PORT ") })
        assertTrue(server.commands.any { it == "STOR /Hdd1/Content/test.bin" })
    }

    @Test
    fun `FTPdll upload uses active PORT data channel and fHdd namespace`() {
        val server = FakeFtpServer.startActive()
        val payload = "echo360-ftpdll-active".toByteArray()
        var lastProgress = 0L

        runBlocking {
            val session = FtpDllActiveFtpSession.connect(
                profile = profile(ftpDllPort = server.port),
                timeoutMs = 2_000,
            )
            try {
                session.upload(
                    canonicalPath = "/Hdd1/Content/test.bin",
                    source = ByteArrayInputStream(payload),
                ) { sent -> lastProgress = sent }

                assertEquals(payload.size.toLong(), session.size("/Hdd1/Content/test.bin"))
            } finally {
                session.close()
            }
        }

        server.await()

        assertArrayEquals(payload, server.receivedBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        assertTrue(server.commands.any { it.startsWith("PORT ") })
        assertFalse(server.commands.any { it == "PASV" })
        assertTrue(server.commands.any { it == "STOR /fHdd/Content/test.bin" })
    }

    private fun profile(
        auroraPort: Int = 21,
        ftpDllPort: Int = 7564,
    ): XboxProfile = XboxProfile(
        endpoint = XboxEndpoint(
            host = LOOPBACK,
            auroraFtpPort = auroraPort,
            ftpDllPort = ftpDllPort,
        ),
        credentials = XboxCredentials(
            auroraFtpUsername = "xbox",
            auroraFtpPassword = "xbox",
            ftpDllUsername = "xbox",
            ftpDllPassword = "xbox",
        ),
    )

    private class FakeFtpServer private constructor(
        private val controlListener: ServerSocket,
        private val mode: Mode,
    ) {
        val port: Int = controlListener.localPort
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val received = ByteArrayOutputStream()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(start = true, name = "echo360-fake-ftp") {
            try {
                serve()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                runCatching { controlListener.close() }
            }
        }

        fun receivedBytes(): ByteArray = received.toByteArray()

        fun await() {
            worker.join(4_000)
            if (worker.isAlive) {
                runCatching { controlListener.close() }
                fail("Fake FTP server did not terminate.")
            }
            failure.get()?.let { error ->
                throw AssertionError("Fake FTP server failed. Commands=${commands.joinToString()}", error)
            }
        }

        private fun serve() {
            controlListener.accept().use { control ->
                control.soTimeout = 3_000
                val reader = control.getInputStream().bufferedReader(Charsets.US_ASCII)
                val writer = control.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                var passiveListener: ServerSocket? = null
                var activeEndpoint: InetSocketAddress? = null

                fun reply(line: String) {
                    writer.write(line)
                    writer.write("\r\n")
                    writer.flush()
                }

                reply("220 Echo360 fake FTP ready")

                while (true) {
                    val command = reader.readLine() ?: break
                    commands += command
                    val verb = command.substringBefore(' ').uppercase()

                    when (verb) {
                        "USER" -> reply("331 Password required")
                        "PASS" -> reply("230 Login successful")
                        "TYPE" -> reply("200 Type set to I")
                        "CWD" -> reply("250 Directory changed")
                        "MKD" -> reply("257 Directory created")
                        "PASV" -> {
                            check(mode == Mode.Passive) { "PASV used against active-only fake server." }
                            passiveListener?.close()
                            passiveListener = ServerSocket(0)
                            val p = passiveListener.localPort
                            reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                        }
                        "EPSV" -> reply("500 EPSV not supported")
                        "PORT" -> {
                            check(mode == Mode.Active) { "PORT used against passive-only fake server." }
                            activeEndpoint = parsePort(command.substringAfter(' '))
                            reply("200 PORT command successful")
                        }
                        "STOR" -> {
                            reply("150 Opening binary data connection")
                            when (mode) {
                                Mode.Passive -> {
                                    val listener = checkNotNull(passiveListener) { "PASV was not negotiated before STOR." }
                                    listener.accept().use { data ->
                                        data.soTimeout = 3_000
                                        data.getInputStream().copyTo(received)
                                    }
                                    listener.close()
                                    passiveListener = null
                                }
                                Mode.Active -> {
                                    val endpoint = checkNotNull(activeEndpoint) { "PORT was not negotiated before STOR." }
                                    Socket().use { data ->
                                        data.soTimeout = 3_000
                                        data.connect(endpoint, 2_000)
                                        data.getInputStream().copyTo(received)
                                    }
                                }
                            }
                            reply("226 Transfer complete")
                        }
                        "SIZE" -> reply("213 ${received.size()}")
                        "QUIT" -> {
                            reply("221 Goodbye")
                            passiveListener?.close()
                            return
                        }
                        else -> error("Unexpected FTP command from client: $command")
                    }
                }

                passiveListener?.close()
            }
        }

        private fun parsePort(argument: String): InetSocketAddress {
            val values = argument.split(',').map { it.toInt() }
            require(values.size == 6) { "Invalid PORT argument: $argument" }
            val host = values.take(4).joinToString(".")
            val port = values[4] * 256 + values[5]
            return InetSocketAddress(host, port)
        }

        companion object {
            fun startPassive(): FakeFtpServer = FakeFtpServer(
                controlListener = ServerSocket(0),
                mode = Mode.Passive,
            )

            fun startActive(): FakeFtpServer = FakeFtpServer(
                controlListener = ServerSocket(0),
                mode = Mode.Active,
            )
        }

        private enum class Mode {
            Passive,
            Active,
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
    }
}
