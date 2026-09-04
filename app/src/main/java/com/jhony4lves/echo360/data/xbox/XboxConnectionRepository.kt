package com.jhony4lves.echo360.data.xbox

import com.jhony4lves.echo360.domain.xbox.TransportHealth
import com.jhony4lves.echo360.domain.xbox.TransportStatus
import com.jhony4lves.echo360.domain.xbox.XboxConnectionSnapshot
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.domain.xbox.XboxTransport
import com.jhony4lves.echo360.network.echolink.EchoLinkAuthenticationException
import com.jhony4lves.echo360.network.echolink.EchoLinkClient
import com.jhony4lves.echo360.network.echolink.EchoLinkPairingTokenException
import com.jhony4lves.echo360.network.echolink.EchoLinkProtocolException
import com.jhony4lves.echo360.network.ftp.FtpProtocolException
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.FtpStageTimeoutException
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.system.measureTimeMillis

class XboxConnectionRepository(
    private val echoLinkClient: EchoLinkClient = EchoLinkClient(),
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
    private val ftpSessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    suspend fun check(profile: XboxProfile): XboxConnectionSnapshot {
        val endpoint = profile.endpoint.validated()

        val echoCore = echoCoreHealth(
            host = endpoint.host,
            port = endpoint.echoLinkPort,
            pairingToken = profile.credentials.echoCorePairingToken,
        )

        val novaResult = novaClient.probe(endpoint)
        val nova = TransportHealth(
            transport = XboxTransport.Nova,
            status = if (novaResult.reachable) TransportStatus.Connected else TransportStatus.Unreachable,
            detail = if (novaResult.reachable) {
                "NOVA respondeu na rede. Autenticação profunda ainda não foi executada."
            } else {
                novaResult.detail
            },
            latencyMs = novaResult.latencyMs,
        )

        val auroraFtp = ftpHealth(
            profile = profile,
            transport = XboxTransport.AuroraFtp,
            route = FtpRoute.Fast,
            port = endpoint.auroraFtpPort,
        )

        val ftpDll = ftpHealth(
            profile = profile,
            transport = XboxTransport.FtpDll,
            route = FtpRoute.Background,
            port = endpoint.ftpDllPort,
        )

        return XboxConnectionSnapshot(
            echoCore = echoCore,
            nova = nova,
            auroraFtp = auroraFtp,
            ftpDll = ftpDll,
            checkedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun echoCoreHealth(
        host: String,
        port: Int,
        pairingToken: String,
    ): TransportHealth {
        val startedAt = System.currentTimeMillis()

        if (pairingToken.isBlank()) {
            return try {
                val pong = echoLinkClient.ping(host, port)
                TransportHealth(
                    transport = XboxTransport.EchoCore,
                    status = TransportStatus.NotConfigured,
                    detail = "EchoCore respondeu PONG v${pong.protocolVersion}, mas falta informar o token de pareamento para autenticar a sessão.",
                    latencyMs = pong.latencyMs,
                )
            } catch (error: Throwable) {
                echoCoreFailure(
                    port = port,
                    error = error,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }
        }

        return try {
            val auth = echoLinkClient.authenticate(
                host = host,
                pairingToken = pairingToken,
                port = port,
            )
            TransportHealth(
                transport = XboxTransport.EchoCore,
                status = TransportStatus.Connected,
                detail = "EchoCore autenticado via pairing + challenge/HMAC v${auth.protocolVersion}; sessão read-only autorizada.",
                latencyMs = auth.latencyMs,
            )
        } catch (error: Throwable) {
            echoCoreFailure(
                port = port,
                error = error,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    private fun echoCoreFailure(
        port: Int,
        error: Throwable,
        elapsedMs: Long,
    ): TransportHealth {
        val status = when (error) {
            is EchoLinkAuthenticationException, is EchoLinkPairingTokenException -> TransportStatus.AuthFailed
            is EchoLinkProtocolException, is EOFException -> TransportStatus.ProtocolError
            is ConnectException, is SocketTimeoutException -> TransportStatus.Unreachable
            is IOException -> TransportStatus.Unreachable
            else -> TransportStatus.ProtocolError
        }
        val detail = when (error) {
            is EchoLinkPairingTokenException ->
                "Token EchoCore inválido no app: ${safeError(error)}"

            is EchoLinkAuthenticationException ->
                "EchoCore recusou o pareamento. Confira se o token salvo no app é o mesmo exibido pelo Pairing XEX."

            is EchoLinkProtocolException ->
                "A porta $port respondeu, mas não concluiu um handshake EchoLink v1 válido: ${safeError(error)}"

            is EOFException ->
                "A porta $port aceitou a conexão e fechou antes de concluir o handshake EchoLink."

            is ConnectException ->
                "EchoCore não está escutando na porta $port. Ative o XEX/plugin resident e teste novamente."

            is SocketTimeoutException ->
                "EchoCore não concluiu o handshake na porta $port dentro do timeout. • $elapsedMs ms"

            is IOException ->
                "Falha de rede ao validar EchoCore na porta $port: ${safeError(error)}"

            else ->
                "Falha ao validar EchoCore na porta $port: ${safeError(error)}"
        }
        return TransportHealth(
            transport = XboxTransport.EchoCore,
            status = status,
            detail = detail,
            latencyMs = elapsedMs,
        )
    }

    private suspend fun ftpHealth(
        profile: XboxProfile,
        transport: XboxTransport,
        route: FtpRoute,
        port: Int,
    ): TransportHealth {
        val credentials = profile.credentials
        val configured = when (transport) {
            XboxTransport.AuroraFtp ->
                credentials.auroraFtpUsername.isNotBlank() && credentials.auroraFtpPassword.isNotBlank()

            XboxTransport.FtpDll ->
                credentials.ftpDllUsername.isNotBlank() && credentials.ftpDllPassword.isNotBlank()

            XboxTransport.EchoCore, XboxTransport.Nova -> true
        }

        if (!configured) {
            return TransportHealth(
                transport = transport,
                status = TransportStatus.NotConfigured,
                detail = "Credenciais não configuradas.",
            )
        }

        var session: XboxFtpSession? = null
        val startedAt = System.currentTimeMillis()
        return try {
            var rootEntries = 0
            val elapsed = measureTimeMillis {
                val routed = ftpSessionFactory.connect(profile, route)
                session = routed.session
                rootEntries = routed.session.list("/").size
            }

            TransportHealth(
                transport = transport,
                status = TransportStatus.Connected,
                detail = when (transport) {
                    XboxTransport.AuroraFtp ->
                        "Fast validado: login + PASV + LIST da raiz ($rootEntries entradas)."

                    XboxTransport.FtpDll ->
                        "Background validado: login + PORT + LIST da raiz ($rootEntries entradas)."

                    XboxTransport.EchoCore -> "EchoCore validado."
                    XboxTransport.Nova -> "Conectado."
                },
                latencyMs = elapsed,
            )
        } catch (error: Throwable) {
            ftpFailure(
                transport = transport,
                port = port,
                error = error,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun ftpFailure(
        transport: XboxTransport,
        port: Int,
        error: Throwable,
        elapsedMs: Long,
    ): TransportHealth {
        val protocol = error as? FtpProtocolException
        val stagedTimeout = error as? FtpStageTimeoutException
        val ftpCode = protocol?.ftpCode
        val status = when {
            ftpCode == 421 -> TransportStatus.Busy
            ftpCode == 530 -> TransportStatus.AuthFailed
            (ftpCode ?: 0) in 500..599 &&
                protocol?.message.orEmpty().contains("login", ignoreCase = true) -> TransportStatus.AuthFailed
            error is ConnectException -> TransportStatus.Unreachable
            stagedTimeout != null -> TransportStatus.Unreachable
            error is SocketTimeoutException -> TransportStatus.Unreachable
            error is EOFException -> TransportStatus.ProtocolError
            error is IOException -> TransportStatus.Unreachable
            else -> TransportStatus.ProtocolError
        }

        val detail = when {
            ftpCode == 421 ->
                "FTP $port respondeu 421: limite de conexões atingido. Feche outras sessões e teste novamente."

            status == TransportStatus.AuthFailed ->
                "FTP $port recusou usuário/senha${ftpCode?.let { " (código $it)" }.orEmpty()}."

            stagedTimeout != null ->
                "Timeout na porta $port em: ${stagedTimeout.stage}. • ${elapsedMs} ms"

            error is ConnectException ->
                "Conexão recusada na porta $port. O serviço FTP parece não estar escutando nessa porta."

            error is SocketTimeoutException ->
                "Timeout na porta $port após ${elapsedMs} ms."

            error is EOFException ->
                "A porta $port aceitou a conexão, mas o servidor FTP fechou o canal sem concluir a resposta."

            protocol != null ->
                "FTP $port respondeu ${ftpCode ?: "sem código"}: ${protocol.message ?: "erro de protocolo"}"

            error is IOException ->
                "Falha de rede na porta $port: ${error.message ?: error.javaClass.simpleName}."

            else ->
                "Falha FTP na porta $port: ${error.message ?: error.javaClass.simpleName}."
        }

        return TransportHealth(
            transport = transport,
            status = status,
            detail = detail,
            latencyMs = elapsedMs,
        )
    }

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(220)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "erro de transporte" }
}
