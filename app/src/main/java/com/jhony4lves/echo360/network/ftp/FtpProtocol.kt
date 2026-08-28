package com.jhony4lves.echo360.network.ftp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal data class FtpReply(
    val code: Int,
    val lines: List<String>,
) {
    val text: String get() = lines.joinToString("\n")
    val isPositive: Boolean get() = code in 200..299
    val isPreliminary: Boolean get() = code == 125 || code == 150
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

internal class FtpCommandChannel(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int,
) : Closeable {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

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
            connect(InetSocketAddress(host, port), timeoutMs)
        }
        socket = connected
        reader = connected.getInputStream().bufferedReader(Charsets.US_ASCII)
        writer = connected.getOutputStream().bufferedWriter(Charsets.US_ASCII)

        val banner = read()
        if (banner.code == 421) {
            throw FtpProtocolException(421, "Servidor FTP atingiu o limite de conexões.")
        }
        if (!banner.isPositive) {
            throw FtpProtocolException(banner.code, "Servidor FTP recusou a conexão.")
        }

        send("USER $username")
        val userReply = read()
        when (userReply.code) {
            230 -> Unit
            331 -> {
                send("PASS $password")
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
        send(command)
        return read()
    }

    fun send(command: String) {
        val output = writer ?: throw IOException("Canal FTP não conectado.")
        output.write(command)
        output.write("\r\n")
        output.flush()
    }

    fun read(): FtpReply {
        val input = reader ?: throw IOException("Canal FTP não conectado.")
        return FtpReplyParser.read(input)
    }

    override fun close() {
        if (socket == null) return
        runCatching {
            send("QUIT")
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
