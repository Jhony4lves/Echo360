package com.jhony4lves.echo360.network.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.system.measureTimeMillis

enum class FtpLoginStatus {
    Connected,
    NotConfigured,
    AuthFailed,
    Busy,
    Unreachable,
    ProtocolError,
}

data class FtpLoginResult(
    val status: FtpLoginStatus,
    val code: Int? = null,
    val detail: String,
    val latencyMs: Long? = null,
)

class FtpControlClient(
    private val timeoutMs: Int = 3500,
) {
    suspend fun loginAndQuit(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): FtpLoginResult = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            return@withContext FtpLoginResult(
                status = FtpLoginStatus.NotConfigured,
                detail = "Credenciais não configuradas.",
            )
        }

        val channel = FtpCommandChannel(host, port, timeoutMs)
        val attempt = runCatching {
            var elapsed = 0L
            elapsed = measureTimeMillis {
                channel.connectAndLogin(username, password)
            }
            FtpLoginResult(
                status = FtpLoginStatus.Connected,
                code = 230,
                detail = "Login FTP aceito.",
                latencyMs = elapsed,
            )
        }

        channel.close()

        attempt.getOrElse { error ->
            when (error) {
                is FtpProtocolException -> when {
                    error.ftpCode == 421 -> FtpLoginResult(
                        FtpLoginStatus.Busy,
                        error.ftpCode,
                        "Servidor FTP atingiu o limite de conexões.",
                    )

                    error.ftpCode in 500..599 -> FtpLoginResult(
                        FtpLoginStatus.AuthFailed,
                        error.ftpCode,
                        "Login FTP recusado.",
                    )

                    else -> FtpLoginResult(
                        FtpLoginStatus.ProtocolError,
                        error.ftpCode,
                        error.message ?: "Erro de protocolo FTP.",
                    )
                }

                is IOException -> FtpLoginResult(
                    FtpLoginStatus.Unreachable,
                    detail = "Não foi possível concluir a sessão FTP.",
                )

                else -> FtpLoginResult(
                    FtpLoginStatus.ProtocolError,
                    detail = "Falha inesperada no protocolo FTP.",
                )
            }
        }
    }
}
