package com.jhony4lves.echo360.network.nova

import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.network.TcpPortProbe
import com.jhony4lves.echo360.network.TcpProbeResult

/**
 * First native NOVA transport primitive.
 *
 * This phase deliberately probes the NOVA control port without requesting
 * sensitive console identity endpoints. Authenticated NOVA operations are
 * layered on top of this client as their response contracts are normalized.
 */
class AuroraNovaClient(
    private val tcpProbe: TcpPortProbe = TcpPortProbe(),
) {
    suspend fun probe(endpoint: XboxEndpoint): TcpProbeResult =
        tcpProbe.probe(endpoint.host, endpoint.novaPort)
}
