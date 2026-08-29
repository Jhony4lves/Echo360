package com.jhony4lves.echo360.network.echolink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class EchoLinkProtocolTest {
    @Test
    fun frame_round_trip_preserves_header_and_payload() {
        val payload = "hello-echo".encodeToByteArray()
        val frame = EchoLinkProtocol.Frame(
            type = EchoLinkProtocol.FrameType.Ping,
            requestId = 42,
            payload = payload,
            flags = 3,
        )

        val bytes = ByteArrayOutputStream().also { raw ->
            EchoLinkProtocol.write(DataOutputStream(raw), frame)
        }.toByteArray()

        assertEquals(EchoLinkProtocol.HEADER_BYTES + payload.size, bytes.size)

        val decoded = EchoLinkProtocol.read(
            DataInputStream(ByteArrayInputStream(bytes)),
        )
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.requestId, decoded.requestId)
        assertEquals(frame.flags, decoded.flags)
        assertEquals(EchoLinkProtocol.VERSION, decoded.version)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun rejects_invalid_magic() {
        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { output ->
                output.writeInt(0x12345678)
                output.writeByte(EchoLinkProtocol.VERSION)
                output.writeByte(EchoLinkProtocol.FrameType.Ping.code)
                output.writeShort(0)
                output.writeInt(0)
                output.writeInt(1)
            }
        }.toByteArray()

        assertThrows(EchoLinkProtocolException::class.java) {
            EchoLinkProtocol.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun rejects_oversized_control_payload_before_allocation() {
        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { output ->
                output.writeInt(EchoLinkProtocol.MAGIC)
                output.writeByte(EchoLinkProtocol.VERSION)
                output.writeByte(EchoLinkProtocol.FrameType.Ping.code)
                output.writeShort(0)
                output.writeInt(EchoLinkProtocol.MAX_CONTROL_PAYLOAD_BYTES + 1)
                output.writeInt(1)
            }
        }.toByteArray()

        assertThrows(EchoLinkProtocolException::class.java) {
            EchoLinkProtocol.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun client_ping_validates_pong_nonce_and_request_id() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(start = true, name = "echolink-test-server") {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val output = DataOutputStream(socket.getOutputStream().buffered())
                    val ping = EchoLinkProtocol.read(input)
                    assertEquals(EchoLinkProtocol.FrameType.Ping, ping.type)
                    assertEquals(Long.SIZE_BYTES, ping.payload.size)
                    ByteBuffer.wrap(ping.payload).long

                    EchoLinkProtocol.write(
                        output,
                        EchoLinkProtocol.Frame(
                            type = EchoLinkProtocol.FrameType.Pong,
                            requestId = ping.requestId,
                            payload = ping.payload,
                        ),
                    )
                }
            }

            val result = EchoLinkClient(timeoutMs = 2_000).ping(
                host = "127.0.0.1",
                port = server.localPort,
            )

            worker.join(2_000)
            assertEquals(EchoLinkProtocol.VERSION, result.protocolVersion)
            assert(result.latencyMs >= 0)
        }
    }
}
