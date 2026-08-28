package com.jhony4lves.echo360.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

data class TcpProbeResult(
    val reachable: Boolean,
    val latencyMs: Long?,
    val detail: String,
)

class TcpPortProbe(
    private val timeoutMs: Int = 2500,
) {
    suspend fun probe(host: String, port: Int): TcpProbeResult = withContext(Dispatchers.IO) {
        var connected = false
        val elapsed = runCatching {
            measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    connected = socket.isConnected
                }
            }
        }

        if (elapsed.isSuccess && connected) {
            TcpProbeResult(
                reachable = true,
                latencyMs = elapsed.getOrThrow(),
                detail = "Porta respondeu.",
            )
        } else {
            TcpProbeResult(
                reachable = false,
                latencyMs = null,
                detail = "Sem resposta na porta $port.",
            )
        }
    }
}
