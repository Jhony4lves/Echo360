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
            val session = try {
                AuroraPassiveFtpSession.connect(
                    profile = profile(auroraPort = server.port),
                    timeoutMs = 2_000,
                )
            } catch (error: Throwable) {
                failAt("Aurora control connect/login", server, error)
            }

            try {
                try {
                    session.upload(
                        canonicalPath = "/Hdd1/Content/test.bin",
                        source = ByteArrayInputStream(payload),
                    ) { sent -> lastProgress = sent }
                } catch (error: Throwable) {
                    failAt("Aurora passive STOR", server, error)
                }

                try {
                    assertEquals(payload.size.toLong(), session.size("/Hdd1/Content/test.bin"))
                } catch (error: Throwable) {
                    failAt("Aurora SIZE verification", server, error)
                }
            } finally {
                runCatching { session.close() }
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
            val session = try {
                FtpDllActiveFtpSession.connect(
                    profile = profile(ftpDllPort = server.port),
                    timeoutMs = 2_000,
                )
            } catch (error: Throwable) {
                failAt("FTPdll control connect/login", server, error)
            }

            try {
                try {
                    session.upload(
                        canonicalPath = "/Hdd1/Content/test.bin",
                        source = ByteArrayInputStream(payload),
                    ) { sent -> lastProgress = sent }
                } catch (error: Throwable) {
                    failAt("FTPdll active STOR", server, error)
                }

                try {
                    assertEquals(payload.size.toLong(), session.size("/Hdd1/Content/test.bin"))
                } catch (error: Throwable) {
                    failAt("FTPdll SIZE verification", server, error)
                }
            } finally {
                runCatching { session.close() }
            }
        }

        server.await()

        assertArrayEquals(payload, server.receivedBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        assertTrue(server.commands.any { it.startsWith("PORT ") })
        assertFalse(server.commands.any { it == "PASV" })
        assertTrue(server.commands.any { it == "STOR /fHdd/Content/test.bin" })
    }

    private fun failAt(stage: String, server: FakeFtpServer, error: Throwable): Nothing {
        throw AssertionError(
            "$stage failed. ${server.diagnostics()}",
            error,
        )
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
        private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val received = ByteArrayOutputStream()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "echo360-fake-ftp-${mode.name.lowercase()}",
        ) {
            events += "server-thread-start control=${controlListener.localSocketAddress}"
            try {
                serve()
                events += "server-thread-complete"
            } catch (error: Throwable) {
                failure.set(error)
                events += "server-thread-failed:${error::class.java.simpleName}:${error.message}"
            } finally {
                runCatching { controlListener.close() }
            }
        }

        fun receivedBytes(): ByteArray = received.toByteArray()

        fun diagnostics(): String {
            val commandSnapshot = synchronized(commands) { commands.toList() }
            val eventSnapshot = synchronized(events) { events.toList() }
            val serverFailure = failure.get()
            return buildString {
                append("mode=$mode, controlPort=$port")
                append(", commands=")
                append(commandSnapshot.joinToString(" | ").ifBlank { "<none>" })
                append(", events=")
                append(eventSnapshot.joinToString(" | ").ifBlank { "<none>" })
                if (serverFailure != null) {
                    append(", serverFailure=${serverFailure::class.java.name}:${serverFailure.message}")
                }
            }
        }

        fun await() {
            worker.join(4_000)
            if (worker.isAlive) {
                runCatching { controlListener.close() }
                fail("Fake FTP server did not terminate. ${diagnostics()}")
            }
            failure.get()?.let { error ->
                throw AssertionError("Fake FTP server failed. ${diagnostics()}", error)
            }
        }

        private fun serve() {
            events += "control-await-accept"
            controlListener.accept().use { control ->
                events += "control-accepted local=${control.localSocketAddress} remote=${control.remoteSocketAddress}"
                control.soTimeout = 3_000
                val reader = control.getInputStream().bufferedReader(Charsets.US_ASCII)
                val writer = control.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                var passiveListener: ServerSocket? = null
                var activeEndpoint: InetSocketAddress? = null

                fun reply(line: String) {
                    events += "reply:${line.substringBefore(' ')}"
                    writer.write(line)
                    writer.write("\r\n")
                    writer.flush()
                }

                reply("220 Echo360 fake FTP ready")

                while (true) {
                    val command = reader.readLine() ?: break
                    commands += command
                    events += "command:${command.substringBefore(' ').uppercase()}"
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
                            events += "passive-listen:${passiveListener.localSocketAddress}"
                            reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                        }
                        "EPSV" -> reply("500 EPSV not supported")
                        "PORT" -> {
                            check(mode == Mode.Active) { "PORT used against passive-only fake server." }
                            activeEndpoint = parsePort(command.substringAfter(' '))
                            events += "active-target:$activeEndpoint"
                            reply("200 PORT command successful")
                        }
                        "STOR" -> {
                            reply("150 Opening binary data connection")
                            when (mode) {
                                Mode.Passive -> {
                                    val listener = checkNotNull(passiveListener) { "PASV was not negotiated before STOR." }
                                    events += "passive-await-data:${listener.localSocketAddress}"
                                    listener.accept().use { data ->
                                        events += "passive-data-accepted remote=${data.remoteSocketAddress}"
                                        data.soTimeout = 3_000
                                        data.getInputStream().copyTo(received)
                                    }
                                    events += "passive-data-complete bytes=${received.size()}"
                                    listener.close()
                                    passiveListener = null
                                }
                                Mode.Active -> {
                                    val endpoint = checkNotNull(activeEndpoint) { "PORT was not negotiated before STOR." }
                                    events += "active-data-connect:$endpoint"
                                    Socket().use { data ->
                                        data.soTimeout = 3_000
                                        data.connect(endpoint, 2_000)
                                        events += "active-data-connected local=${data.localSocketAddress} remote=${data.remoteSocketAddress}"
                                        data.getInputStream().copyTo(received)
                                    }
                                    events += "active-data-complete bytes=${received.size()}"
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
