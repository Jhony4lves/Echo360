package com.jhony4lves.echo360.network.echolink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

data class EchoLinkPingResult(
    val protocolVersion: Int,
    val latencyMs: Long,
    val requestId: Int,
)

data class EchoLinkAuthResult(
    val protocolVersion: Int,
    val latencyMs: Long,
    val requestId: Int,
    val grantedCapabilities: Long,
    val committedCounter: Long,
)

class EchoLinkClient(
    private val timeoutMs: Int = 2_500,
) {
    suspend fun ping(
        host: String,
        port: Int = EchoLinkProtocol.DEFAULT_PORT,
    ): EchoLinkPingResult = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Informe o IP ou host do Xbox." }
        require(port in 1..65535) { "Porta EchoLink inválida." }

        val requestId = nextRequestId()
        val nonce = System.nanoTime()
        val payload = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(nonce).array()
        var response: EchoLinkProtocol.Frame? = null

        val elapsed = measureTimeMillis {
            Socket().use { socket ->
                configureAndConnect(socket, host, port)
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val input = DataInputStream(socket.getInputStream().buffered())

                EchoLinkProtocol.write(
                    output,
                    EchoLinkProtocol.Frame(
                        type = EchoLinkProtocol.FrameType.Ping,
                        requestId = requestId,
                        payload = payload,
                    ),
                )
                response = EchoLinkProtocol.read(input)
            }
        }

        val pong = requireNotNull(response)
        requireResponseEnvelope(pong, EchoLinkProtocol.FrameType.Pong, requestId)
        if (!pong.payload.contentEquals(payload)) {
            throw EchoLinkProtocolException("EchoCore respondeu PONG com nonce diferente.")
        }

        EchoLinkPingResult(
            protocolVersion = pong.version,
            latencyMs = elapsed,
            requestId = requestId,
        )
    }

    suspend fun authenticate(
        host: String,
        pairingToken: String,
        port: Int = EchoLinkProtocol.DEFAULT_PORT,
    ): EchoLinkAuthResult = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Informe o IP ou host do Xbox." }
        require(port in 1..65535) { "Porta EchoLink inválida." }

        val secret = EchoPairingAuth.deriveSecret(pairingToken)
        val beginRequestId = nextRequestId()
        val authRequestId = nextRequestId()
        var authResult: EchoLinkAuthResult? = null

        try {
            val elapsed = measureTimeMillis {
                Socket().use { socket ->
                    configureAndConnect(socket, host, port)
                    val output = DataOutputStream(socket.getOutputStream().buffered())
                    val input = DataInputStream(socket.getInputStream().buffered())

                    EchoLinkProtocol.write(
                        output,
                        EchoLinkProtocol.Frame(
                            type = EchoLinkProtocol.FrameType.SessionBeginRequest,
                            requestId = beginRequestId,
                        ),
                    )

                    val challengeFrame = EchoLinkProtocol.read(input)
                    requireResponseEnvelope(
                        challengeFrame,
                        EchoLinkProtocol.FrameType.SessionChallengeResponse,
                        beginRequestId,
                    )
                    if (challengeFrame.payload.size != SESSION_CHALLENGE_BYTES) {
                        throw EchoLinkProtocolException(
                            "EchoCore respondeu challenge com ${challengeFrame.payload.size} bytes; esperado $SESSION_CHALLENGE_BYTES.",
                        )
                    }

                    val challengeBuffer = ByteBuffer.wrap(challengeFrame.payload)
                        .order(ByteOrder.BIG_ENDIAN)
                    val sessionId = challengeBuffer.long
                    if (sessionId == 0L) {
                        throw EchoLinkProtocolException("EchoCore respondeu sessionId zero.")
                    }
                    val challenge = ByteArray(EchoPairingAuth.CHALLENGE_BYTES)
                    challengeBuffer.get(challenge)

                    val counter = 1L
                    val requestedCapabilities = EchoPairingAuth.READONLY_CAPABILITIES
                    val authPayload = EchoPairingAuth.makeAuthRequestPayload(
                        secret = secret,
                        sessionId = sessionId,
                        challenge = challenge,
                        counter = counter,
                        requestedCapabilities = requestedCapabilities,
                    )
                    challenge.fill(0)

                    try {
                        EchoLinkProtocol.write(
                            output,
                            EchoLinkProtocol.Frame(
                                type = EchoLinkProtocol.FrameType.SessionAuthRequest,
                                requestId = authRequestId,
                                payload = authPayload,
                            ),
                        )
                    } finally {
                        authPayload.fill(0)
                    }

                    val response = EchoLinkProtocol.read(input)
                    requireResponseEnvelope(
                        response,
                        EchoLinkProtocol.FrameType.SessionAuthResponse,
                        authRequestId,
                    )
                    if (response.payload.size != EchoPairingAuth.AUTH_RESPONSE_BYTES) {
                        throw EchoLinkProtocolException(
                            "EchoCore respondeu AUTH com ${response.payload.size} bytes; esperado ${EchoPairingAuth.AUTH_RESPONSE_BYTES}.",
                        )
                    }
                    if (response.payload.copyOfRange(1, 8).any { it != 0.toByte() }) {
                        throw EchoLinkProtocolException("EchoCore respondeu AUTH com bytes reservados não-zero.")
                    }

                    val status = response.payload[0].toInt() and 0xff
                    val responseBuffer = ByteBuffer.wrap(response.payload).order(ByteOrder.BIG_ENDIAN)
                    responseBuffer.position(8)
                    val grantedCapabilities = responseBuffer.long
                    val committedCounter = responseBuffer.long

                    when (status) {
                        SESSION_STATUS_OK -> Unit
                        SESSION_STATUS_DENIED -> throw EchoLinkAuthenticationException(
                            "EchoCore recusou o token de pareamento.",
                        )
                        SESSION_STATUS_PROTOCOL_ERROR -> throw EchoLinkAuthenticationException(
                            "EchoCore recusou o handshake por erro de protocolo.",
                        )
                        else -> throw EchoLinkProtocolException(
                            "EchoCore respondeu status AUTH desconhecido: $status.",
                        )
                    }

                    if (committedCounter != counter) {
                        throw EchoLinkProtocolException(
                            "EchoCore confirmou contador $committedCounter; esperado $counter.",
                        )
                    }
                    if ((grantedCapabilities and requestedCapabilities) != requestedCapabilities) {
                        throw EchoLinkProtocolException(
                            "EchoCore autenticou sem conceder todas as capabilities read-only solicitadas.",
                        )
                    }

                    authResult = EchoLinkAuthResult(
                        protocolVersion = response.version,
                        latencyMs = 0L,
                        requestId = authRequestId,
                        grantedCapabilities = grantedCapabilities,
                        committedCounter = committedCounter,
                    )
                }
            }

            requireNotNull(authResult).copy(latencyMs = elapsed)
        } finally {
            secret.fill(0)
        }
    }

    private fun configureAndConnect(socket: Socket, host: String, port: Int) {
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)
    }

    private fun requireResponseEnvelope(
        frame: EchoLinkProtocol.Frame,
        expectedType: EchoLinkProtocol.FrameType,
        requestId: Int,
    ) {
        if (frame.type != expectedType) {
            throw EchoLinkProtocolException(
                "EchoCore respondeu ${frame.type}, esperado $expectedType.",
            )
        }
        if (frame.requestId != requestId) {
            throw EchoLinkProtocolException(
                "EchoCore respondeu requestId ${frame.requestId}, esperado $requestId.",
            )
        }
        if (frame.flags != 0) {
            throw EchoLinkProtocolException("EchoCore respondeu flags inesperadas: ${frame.flags}.")
        }
    }

    private fun nextRequestId(): Int {
        while (true) {
            val value = requestIds.updateAndGet { current ->
                if (current == Int.MAX_VALUE) 1 else current + 1
            }
            if (value != 0) return value
        }
    }

    private companion object {
        const val SESSION_CHALLENGE_BYTES = 8 + EchoPairingAuth.CHALLENGE_BYTES
        const val SESSION_STATUS_OK = 0
        const val SESSION_STATUS_DENIED = 1
        const val SESSION_STATUS_PROTOCOL_ERROR = 2
        val requestIds = AtomicInteger(0)
    }
}
