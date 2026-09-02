package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxCredentials
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class AuroraFtpSiteClientTest {
    @Test
    fun `restricted SITE commands are sent exactly as documented`() {
        AuroraSiteCommand.entries.forEach { command ->
            val server = FakeSiteServer()
            try {
                val profile = XboxProfile(
                    endpoint = XboxEndpoint(
                        host = "127.0.0.1",
                        auroraFtpPort = server.port,
                    ),
                    credentials = XboxCredentials(
                        auroraFtpUsername = "xbox",
                        auroraFtpPassword = "secret",
                    ),
                )

                val response = runBlocking {
                    AuroraFtpSiteClient(timeoutMs = 2_000).execute(profile, command)
                }

                server.await()
                assertTrue(response.startsWith("200"))
                assertTrue(server.commands.contains("USER xbox"))
                assertTrue(server.commands.contains("PASS secret"))
                assertTrue(server.commands.contains("TYPE I"))
                assertTrue(server.commands.contains("SITE ${command.ftpVerb}"))
                assertEquals(1, server.commands.count { it.startsWith("SITE ") })
            } finally {
                server.close()
            }
        }
    }

    private class FakeSiteServer {
        private val socket = ServerSocket(0)
        private val worker = thread(start = true, name = "fake-aurora-site") {
            socket.accept().use { client ->
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val output = client.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                fun reply(line: String) {
                    output.write(line)
                    output.write("\r\n")
                    output.flush()
                }

                reply("220 Fake Aurora FTP")
                while (true) {
                    val line = input.readLine() ?: break
                    synchronized(commands) { commands += line }
                    when {
                        line.startsWith("USER ") -> reply("331 Password required")
                        line.startsWith("PASS ") -> reply("230 Logged in")
                        line == "TYPE I" -> reply("200 Type set")
                        line.startsWith("SITE ") -> reply("200 SITE command accepted")
                        line == "QUIT" -> {
                            reply("221 Bye")
                            break
                        }
                        else -> reply("500 Unexpected")
                    }
                }
            }
        }

        val port: Int get() = socket.localPort
        val commands = mutableListOf<String>()

        fun await() {
            worker.join(3_000)
            check(!worker.isAlive) { "Fake FTP server did not finish." }
        }

        fun close() {
            runCatching { socket.close() }
            worker.join(500)
        }
    }
}
