package com.jhony4lves.echo360.network.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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

internal data class FtpReply(
    val code: Int,
    val lines: List<String>,
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

        var result: FtpLoginResult? = null
        val elapsed = runCatching {
            measureTimeMillis {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(host, port), timeoutMs)

                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)

                    val banner = readReply(reader)
                    result = when {
                        banner.code == 421 -> busy(banner.code)
                        banner.code !in 200..299 -> protocolError(banner.code, "Banner FTP recusado.")
                        else -> authenticate(reader, writer, username, password)
                    }

                    runCatching {
                        send(writer, "QUIT")
                        readReply(reader)
                    }
                }
            }
        }

        if (elapsed.isFailure) {
            return@withContext FtpLoginResult(
                status = when (elapsed.exceptionOrNull()) {
                    is IOException -> FtpLoginStatus.Unreachable
                    else -> FtpLoginStatus.ProtocolError
                },
                detail = "Não foi possível concluir a sessão FTP.",
            )
        }

        val finished = result ?: protocolError(null, "Resposta FTP ausente.")
        finished.copy(latencyMs = elapsed.getOrThrow())
    }

    private fun authenticate(
        reader: BufferedReader,
        writer: BufferedWriter,
        username: String,
        password: String,
    ): FtpLoginResult {
        send(writer, "USER $username")
        val userReply = readReply(reader)

        if (userReply.code == 421) return busy(userReply.code)
        if (userReply.code == 230) {
            return FtpLoginResult(FtpLoginStatus.Connected, 230, "Login FTP aceito.")
        }
        if (userReply.code != 331) {
            return FtpLoginResult(
                FtpLoginStatus.AuthFailed,
                userReply.code,
                "Servidor FTP recusou o usuário.",
            )
        }

        send(writer, "PASS $password")
        val passReply = readReply(reader)
        return when {
            passReply.code == 230 -> FtpLoginResult(
                FtpLoginStatus.Connected,
                passReply.code,
                "Login FTP aceito.",
            )

            passReply.code == 421 -> busy(passReply.code)
            passReply.code in 500..599 -> FtpLoginResult(
                FtpLoginStatus.AuthFailed,
                passReply.code,
                "Servidor FTP recusou a senha.",
            )

            else -> protocolError(passReply.code, "Resposta inesperada durante autenticação FTP.")
        }
    }

    private fun send(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    internal fun readReply(reader: BufferedReader): FtpReply {
        val first = reader.readLine() ?: throw EOFException("FTP fechou a conexão sem resposta.")
        val code = first.take(3).toIntOrNull()
            ?: throw IOException("Resposta FTP sem código válido.")

        val lines = mutableListOf(first)
        if (first.length > 3 && first[3] == '-') {
            val terminator = "$code "
            while (true) {
                val next = reader.readLine()
                    ?: throw EOFException("Resposta FTP multilinha incompleta.")
                lines += next
                if (next.startsWith(terminator)) break
            }
        }

        return FtpReply(code, lines)
    }

    private fun busy(code: Int?) = FtpLoginResult(
        status = FtpLoginStatus.Busy,
        code = code,
        detail = "Servidor FTP atingiu o limite de conexões.",
    )

    private fun protocolError(code: Int?, detail: String) = FtpLoginResult(
        status = FtpLoginStatus.ProtocolError,
        code = code,
        detail = detail,
    )
}
