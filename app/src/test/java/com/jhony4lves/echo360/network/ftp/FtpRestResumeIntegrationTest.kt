package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxCredentials
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FtpRestResumeIntegrationTest {
    @Test
    fun `Aurora REST resumes passive RETR at exact offset`() {
        val payload = ByteArray(96 * 1024) { index -> (index * 13).toByte() }
        val offset = 37_777L
        val server = RestFakeFtpServer.start(Mode.Passive, payload)
        val output = ByteArrayOutputStream()

        runBlocking {
            val session = AuroraPassiveFtpSession.connect(
                profile = profile(auroraPort = server.port),
                timeoutMs = 2_000,
            )
            try {
                session.downloadFromOffset("/Hdd1/Games/Data0003", offset, output)
            } finally {
                runCatching { session.close() }
            }
        }

        server.await()
        assertArrayEquals(payload.copyOfRange(offset.toInt(), payload.size), output.toByteArray())
        assertTrue(server.commands.any { it == "REST $offset" })
        assertTrue(server.commands.any { it == "RETR /Hdd1/Games/Data0003" })
    }

    @Test
    fun `FTPdll REST resumes active RETR at exact offset with fHdd namespace`() {
        val payload = ByteArray(128 * 1024) { index -> (255 - index).toByte() }
        val offset = 55_321L
        val server = RestFakeFtpServer.start(Mode.Active, payload)
        val output = ByteArrayOutputStream()

        runBlocking {
            val session = FtpDllActiveFtpSession.connect(
                profile = profile(ftpDllPort = server.port),
                timeoutMs = 2_000,
            )
            try {
                session.downloadFromOffset("/Hdd1/Content/Data0007", offset, output)
            } finally {
                runCatching { session.close() }
            }
        }

        server.await()
        assertArrayEquals(payload.copyOfRange(offset.toInt(), payload.size), output.toByteArray())
        assertTrue(server.commands.any { it == "REST $offset" })
        assertTrue(server.commands.any { it == "RETR /fHdd/Content/Data0007" })
    }

    private fun profile(
        auroraPort: Int = 21,
        ftpDllPort: Int = 7564,
    ) = XboxProfile(
        endpoint = XboxEndpoint(
            host = "127.0.0.1",
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

    private enum class Mode { Passive, Active }

    private class RestFakeFtpServer private constructor(
        private val controlListener: ServerSocket,
        private val mode: Mode,
        private val payload: ByteArray,
    ) {
        val port: Int = controlListener.localPort
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(start = true, isDaemon = true) {
            try {
                serve()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                runCatching { controlListener.close() }
            }
        }

        fun await() {
            worker.join(4_000)
            if (worker.isAlive) {
                runCatching { controlListener.close() }
                fail("REST fake FTP server did not terminate. commands=$commands")
            }
            failure.get()?.let { throw AssertionError("REST fake FTP failed. commands=$commands", it) }
        }

        private fun serve() {
            controlListener.accept().use { control ->
                control.soTimeout = 3_000
                val reader = control.getInputStream().bufferedReader(Charsets.US_ASCII)
                val writer = control.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                var passiveListener: ServerSocket? = null
                var activeEndpoint: InetSocketAddress? = null
                var restartOffset = 0

                fun reply(line: String) {
                    writer.write(line)
                    writer.write("\r\n")
                    writer.flush()
                }

                fun withDataSocket(block: (Socket) -> Unit) {
                    when (mode) {
                        Mode.Passive -> {
                            val listener = checkNotNull(passiveListener)
                            listener.accept().use(block)
                            listener.close()
                            passiveListener = null
                        }
                        Mode.Active -> {
                            val endpoint = checkNotNull(activeEndpoint)
                            Socket().use { data ->
                                data.connect(endpoint, 2_000)
                                data.soTimeout = 3_000
                                block(data)
                            }
                        }
                    }
                }

                reply("220 Echo360 REST fake ready")
                while (true) {
                    val command = reader.readLine() ?: break
                    commands += command
                    val verb = command.substringBefore(' ').uppercase()
                    when (verb) {
                        "USER" -> reply("331 Password required")
                        "PASS" -> reply("230 Login successful")
                        "TYPE" -> reply("200 Type set to I")
                        "PASV" -> {
                            check(mode == Mode.Passive)
                            passiveListener?.close()
                            val listener = ServerSocket(0)
                            passiveListener = listener
                            val p = listener.localPort
                            reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                        }
                        "EPSV" -> reply("500 EPSV unsupported")
                        "PORT" -> {
                            check(mode == Mode.Active)
                            activeEndpoint = parsePort(command.substringAfter(' '))
                            reply("200 PORT command successful")
                        }
                        "REST" -> {
                            restartOffset = command.substringAfter(' ').toInt()
                            require(restartOffset in 0..payload.size)
                            reply("350 Restarting at $restartOffset")
                        }
                        "RETR" -> {
                            reply("150 Opening binary data connection")
                            withDataSocket { data ->
                                data.getOutputStream().use { output ->
                                    output.write(payload, restartOffset, payload.size - restartOffset)
                                    output.flush()
                                }
                            }
                            reply("226 Transfer complete")
                            restartOffset = 0
                        }
                        "QUIT" -> {
                            reply("221 Goodbye")
                            passiveListener?.close()
                            return
                        }
                        else -> error("Unexpected FTP command: $command")
                    }
                }
                passiveListener?.close()
            }
        }

        private fun parsePort(argument: String): InetSocketAddress {
            val values = argument.split(',').map(String::toInt)
            require(values.size == 6)
            return InetSocketAddress(
                values.take(4).joinToString("."),
                values[4] * 256 + values[5],
            )
        }

        companion object {
            fun start(mode: Mode, payload: ByteArray): RestFakeFtpServer = RestFakeFtpServer(
                controlListener = ServerSocket(0),
                mode = mode,
                payload = payload,
            )
        }
    }
}
