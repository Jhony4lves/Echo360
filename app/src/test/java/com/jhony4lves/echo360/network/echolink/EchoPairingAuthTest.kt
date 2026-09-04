package com.jhony4lves.echo360.network.echolink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EchoPairingAuthTest {
    @Test
    fun token_decode_and_kdf_match_xbox_cross_platform_vector() {
        val display = "000G4-0R40M-30E20-9185G-R38E1W"

        assertArrayEquals(
            hex("000102030405060708090a0b0c0d0e0f"),
            EchoPairingAuth.decodeToken(display),
        )
        assertArrayEquals(
            hex("7344e42de48a363d0454babae5d527f1bf0b43e319fd18da2062ebf524d050f4"),
            EchoPairingAuth.deriveSecret(display),
        )
    }

    @Test
    fun auth_request_matches_xbox_transcript_vector() {
        val secret = hex("7344e42de48a363d0454babae5d527f1bf0b43e319fd18da2062ebf524d050f4")
        val challenge = hex("101112131415161718191a1b1c1d1e1f")

        val payload = EchoPairingAuth.makeAuthRequestPayload(
            secret = secret,
            sessionId = 0x0102030405060708L,
            challenge = challenge,
            counter = 1L,
            requestedCapabilities = EchoPairingAuth.READONLY_CAPABILITIES,
        )

        assertArrayEquals(
            hex(
                "0000000000000001" +
                    "0000000000000007" +
                    "99f6bcd7dc9f758de8b4526ad4d2a47627277bca",
            ),
            payload,
        )
    }

    @Test
    fun client_completes_paired_handshake_against_protocol_peer() = runBlocking {
        val display = "000G4-0R40M-30E20-9185G-R38E1W"
        val expectedAuthPayload = hex(
            "0000000000000001" +
                "0000000000000007" +
                "99f6bcd7dc9f758de8b4526ad4d2a47627277bca",
        )
        val workerFailure = AtomicReference<Throwable?>(null)

        ServerSocket(0).use { server ->
            val worker = thread(start = true, name = "echolink-pairing-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream().buffered())
                        val output = DataOutputStream(socket.getOutputStream().buffered())

                        val begin = EchoLinkProtocol.read(input)
                        assertEquals(EchoLinkProtocol.FrameType.SessionBeginRequest, begin.type)
                        assertEquals(0, begin.flags)
                        assertEquals(0, begin.payload.size)

                        val challengePayload = ByteBuffer.allocate(24)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(0x0102030405060708L)
                            .put(hex("101112131415161718191a1b1c1d1e1f"))
                            .array()
                        EchoLinkProtocol.write(
                            output,
                            EchoLinkProtocol.Frame(
                                type = EchoLinkProtocol.FrameType.SessionChallengeResponse,
                                requestId = begin.requestId,
                                payload = challengePayload,
                            ),
                        )

                        val auth = EchoLinkProtocol.read(input)
                        assertEquals(EchoLinkProtocol.FrameType.SessionAuthRequest, auth.type)
                        assertArrayEquals(expectedAuthPayload, auth.payload)

                        val authResponse = ByteBuffer.allocate(EchoPairingAuth.AUTH_RESPONSE_BYTES)
                            .order(ByteOrder.BIG_ENDIAN)
                            .put(0)
                            .put(ByteArray(7))
                            .putLong(EchoPairingAuth.READONLY_CAPABILITIES)
                            .putLong(1L)
                            .array()
                        EchoLinkProtocol.write(
                            output,
                            EchoLinkProtocol.Frame(
                                type = EchoLinkProtocol.FrameType.SessionAuthResponse,
                                requestId = auth.requestId,
                                payload = authResponse,
                            ),
                        )
                    }
                }.onFailure(workerFailure::set)
            }

            val result = EchoLinkClient(timeoutMs = 2_000).authenticate(
                host = "127.0.0.1",
                pairingToken = display,
                port = server.localPort,
            )

            worker.join(2_000)
            workerFailure.get()?.let { throw AssertionError("Peer de teste falhou", it) }
            assertEquals(EchoLinkProtocol.VERSION, result.protocolVersion)
            assertEquals(EchoPairingAuth.READONLY_CAPABILITIES, result.grantedCapabilities)
            assertEquals(1L, result.committedCounter)
        }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
