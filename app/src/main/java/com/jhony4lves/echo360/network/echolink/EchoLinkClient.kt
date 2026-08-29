package com.jhony4lves.echo360.network.echolink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

data class EchoLinkPingResult(
    val protocolVersion: Int,
    val latencyMs: Long,
    val requestId: Int,
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
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)

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
        if (pong.type != EchoLinkProtocol.FrameType.Pong) {
            throw EchoLinkProtocolException("EchoCore respondeu ${pong.type}, esperado PONG.")
        }
        if (pong.requestId != requestId) {
            throw EchoLinkProtocolException(
                "EchoCore respondeu requestId ${pong.requestId}, esperado $requestId.",
            )
        }
        if (!pong.payload.contentEquals(payload)) {
            throw EchoLinkProtocolException("EchoCore respondeu PONG com nonce diferente.")
        }

        EchoLinkPingResult(
            protocolVersion = pong.version,
            latencyMs = elapsed,
            requestId = requestId,
        )
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
        val requestIds = AtomicInteger(0)
    }
}
