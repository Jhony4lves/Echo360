package com.jhony4lves.echo360.data.xbox

import com.jhony4lves.echo360.domain.xbox.TransportHealth
import com.jhony4lves.echo360.domain.xbox.TransportStatus
import com.jhony4lves.echo360.domain.xbox.XboxConnectionSnapshot
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.domain.xbox.XboxTransport
import com.jhony4lves.echo360.network.ftp.FtpControlClient
import com.jhony4lves.echo360.network.ftp.FtpLoginResult
import com.jhony4lves.echo360.network.ftp.FtpLoginStatus
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class XboxConnectionRepository(
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
    private val ftpClient: FtpControlClient = FtpControlClient(),
) {
    suspend fun check(profile: XboxProfile): XboxConnectionSnapshot = coroutineScope {
        val endpoint = profile.endpoint.validated()
        val credentials = profile.credentials

        val nova = async {
            val result = novaClient.probe(endpoint)
            TransportHealth(
                transport = XboxTransport.Nova,
                status = if (result.reachable) TransportStatus.Connected else TransportStatus.Unreachable,
                detail = if (result.reachable) {
                    "NOVA respondeu na rede. Autenticação profunda ainda não foi executada."
                } else {
                    result.detail
                },
                latencyMs = result.latencyMs,
            )
        }

        val auroraFtp = async {
            ftpHealth(
                transport = XboxTransport.AuroraFtp,
                host = endpoint.host,
                port = endpoint.auroraFtpPort,
                username = credentials.auroraFtpUsername,
                password = credentials.auroraFtpPassword,
            )
        }

        val ftpDll = async {
            ftpHealth(
                transport = XboxTransport.FtpDll,
                host = endpoint.host,
                port = endpoint.ftpDllPort,
                username = credentials.ftpDllUsername,
                password = credentials.ftpDllPassword,
            )
        }

        XboxConnectionSnapshot(
            nova = nova.await(),
            auroraFtp = auroraFtp.await(),
            ftpDll = ftpDll.await(),
            checkedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun ftpHealth(
        transport: XboxTransport,
        host: String,
        port: Int,
        username: String,
        password: String,
    ): TransportHealth {
        val result = ftpClient.loginAndQuit(
            host = host,
            port = port,
            username = username,
            password = password,
        )

        return TransportHealth(
            transport = transport,
            status = result.status.toTransportStatus(),
            detail = result.detail,
            latencyMs = result.latencyMs,
        )
    }

    private fun FtpLoginStatus.toTransportStatus(): TransportStatus = when (this) {
        FtpLoginStatus.Connected -> TransportStatus.Connected
        FtpLoginStatus.NotConfigured -> TransportStatus.NotConfigured
        FtpLoginStatus.AuthFailed -> TransportStatus.AuthFailed
        FtpLoginStatus.Busy -> TransportStatus.Busy
        FtpLoginStatus.Unreachable -> TransportStatus.Unreachable
        FtpLoginStatus.ProtocolError -> TransportStatus.ProtocolError
    }
}
