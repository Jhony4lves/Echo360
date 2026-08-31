package com.jhony4lves.echo360.network.ftp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal data class FtpReply(
    val code: Int,
    val lines: List<String>,
) {
    val text: String get() = lines.joinToString("\n")
    val isPositive: Boolean get() = code in 200..299
    val isPreliminary: Boolean get() = code == 125 || code == 150
}

internal fun FtpReply.isMissingPathReply(): Boolean {
    if (code != 550) return false
    val normalized = text.lowercase()
    return normalized.contains("path not found") ||
        normalized.contains("no such file") ||
        normalized.contains("not found") ||
        normalized.contains("cannot find")
}

internal object FtpReplyParser {
    fun read(reader: BufferedReader): FtpReply {
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
}

internal class FtpProtocolException(
    val ftpCode: Int?,
    message: String,
) : IOException(message)

internal class FtpPathNotFoundException(
    val canonicalPath: String,
    val serverReply: String,
) : IOException("Caminho remoto não encontrado: $canonicalPath")

internal class FtpStageTimeoutException(
    val stage: String,
    cause: SocketTimeoutException,
) : IOException("Timeout em $stage.", cause)

internal class FtpCommandChannel(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int,
    private val connectTimeoutMs: Int = timeoutMs,
) : Closeable {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var expectedReplyStage: String = "resposta FTP"

    val localAddress: InetAddress
        get() = requireSocket().localAddress

    val remoteAddress: InetAddress
        get() = requireSocket().inetAddress

    fun connectAndLogin(username: String, password: String) {
        require(username.isNotBlank()) { "Usuário FTP não configurado." }
        require(password.isNotBlank()) { "Senha FTP não configurada." }

        val connected = Socket().apply {
            soTimeout = timeoutMs
            keepAlive = true
            tcpNoDelay = true
            try {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (error: SocketTimeoutException) {
                throw FtpStageTimeoutException("conexão TCP de controle", error)
            }
        }
        socket = connected
        reader = connected.getInputStream().bufferedReader(Charsets.US_ASCII)
        writer = connected.getOutputStream().bufferedWriter(Charsets.US_ASCII)

        expectedReplyStage = "banner FTP"
        val banner = read()
        if (banner.code == 421) {
            throw FtpProtocolException(421, "Servidor FTP atingiu o limite de conexões.")
        }
        if (!banner.isPositive) {
            throw FtpProtocolException(banner.code, "Servidor FTP recusou a conexão.")
        }

        send("USER $username", "resposta USER")
        val userReply = read()
        when (userReply.code) {
            230 -> Unit
            331 -> {
                send("PASS $password", "resposta PASS")
                val passReply = read()
                if (passReply.code == 421) {
                    throw FtpProtocolException(421, "Servidor FTP atingiu o limite de conexões.")
                }
                if (passReply.code != 230) {
                    throw FtpProtocolException(passReply.code, "Login FTP recusado.")
                }
            }
            421 -> throw FtpProtocolException(421, "Servidor FTP atingiu o limite de conexões.")
            else -> throw FtpProtocolException(userReply.code, "Login FTP recusado.")
        }

        val typeReply = command("TYPE I")
        if (!typeReply.isPositive) {
            throw FtpProtocolException(typeReply.code, "Servidor FTP não aceitou modo binário.")
        }
    }

    fun command(command: String): FtpReply {
        val verb = command.substringBefore(' ').uppercase()
        send(command, "resposta $verb")
        return read()
    }

    fun send(command: String) {
        val verb = command.substringBefore(' ').uppercase()
        send(command, "resposta $verb")
    }

    private fun send(command: String, replyStage: String) {
        val output = writer ?: throw IOException("Canal FTP não conectado.")
        expectedReplyStage = replyStage
        output.write(command)
        output.write("\r\n")
        output.flush()
    }

    fun read(): FtpReply {
        val input = reader ?: throw IOException("Canal FTP não conectado.")
        return try {
            FtpReplyParser.read(input)
        } catch (error: SocketTimeoutException) {
            throw FtpStageTimeoutException(expectedReplyStage, error)
        }
    }

    override fun close() {
        if (socket == null) return
        runCatching {
            send("QUIT", "resposta QUIT")
            read()
        }
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    private fun requireSocket(): Socket = socket ?: throw IOException("Canal FTP não conectado.")
}
