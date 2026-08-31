package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class AuroraPassiveFtpSession private constructor(
    private val channel: FtpCommandChannel,
    private val timeoutMs: Int,
) : XboxFtpSession {
    private val mutex = Mutex()

    override suspend fun list(canonicalPath: String): List<RemoteEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val canonical = XboxPath.canonical(canonicalPath)
            val remote = XboxPath.toAuroraFtpPath(canonical)
            val cwd = channel.command("CWD $remote")
            if (!cwd.isPositive) {
                if (cwd.isMissingPathReply()) {
                    throw FtpPathNotFoundException(canonical, cwd.text)
                }
                throw FtpProtocolException(cwd.code, "Aurora recusou CWD: ${cwd.text}")
            }

            openPassiveSocket().use { dataSocket ->
                channel.send("LIST")
                expectPreliminary(channel.read(), "Aurora não iniciou LIST.")
                val lines = try {
                    dataSocket.getInputStream()
                        .bufferedReader(Charsets.UTF_8)
                        .readLines()
                } catch (error: SocketTimeoutException) {
                    throw FtpStageTimeoutException("recebendo dados do LIST passivo", error)
                }
                expectPositive(channel.read(), "Aurora não concluiu LIST.")
                UnixFtpListParser.parse(lines, canonical)
            }
        }
    }

    override suspend fun size(canonicalPath: String): Long? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remote = XboxPath.toAuroraFtpPath(canonicalPath)
            val reply = channel.command("SIZE $remote")
            when (reply.code) {
                213 -> reply.lines.first().substringAfter("213").trim().toLongOrNull()
                550 -> null
                else -> throw FtpProtocolException(reply.code, "Aurora recusou SIZE.")
            }
        }
    }

    override suspend fun ensureDirectory(canonicalPath: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureDirectoryLocked(XboxPath.canonical(canonicalPath))
        }
    }

    override suspend fun upload(
        canonicalPath: String,
        source: InputStream,
        onProgress: (Long) -> Unit,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            source.use { input ->
                val canonical = XboxPath.canonical(canonicalPath)
                val parent = canonical.substringBeforeLast('/', "/").ifBlank { "/" }
                ensureDirectoryLocked(parent)
                val remote = XboxPath.toAuroraFtpPath(canonical)

                openPassiveSocket().use { dataSocket ->
                    channel.send("STOR $remote")
                    expectPreliminary(channel.read(), "Aurora não iniciou STOR.")

                    dataSocket.getOutputStream().use { output ->
                        copyStreamWithProgress(input, output, onProgress)
                    }

                    expectPositive(channel.read(), "Aurora não concluiu STOR.")
                }
            }
        }
    }

    override suspend fun download(
        canonicalPath: String,
        destination: OutputStream,
        onProgress: (Long) -> Unit,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remote = XboxPath.toAuroraFtpPath(XboxPath.canonical(canonicalPath))
            openPassiveSocket().use { dataSocket ->
                channel.send("RETR $remote")
                expectPreliminary(channel.read(), "Aurora não iniciou RETR.")
                dataSocket.getInputStream().use { input ->
                    copyStreamWithProgress(input, destination, onProgress)
                }
                destination.flush()
                expectPositive(channel.read(), "Aurora não concluiu RETR.")
            }
        }
    }

    override suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) { channel.close() }
    }

    private fun ensureDirectoryLocked(canonicalPath: String) {
        val canonical = XboxPath.canonical(canonicalPath)
        if (canonical == "/") return

        val segments = canonical.removePrefix("/").split('/').filter(String::isNotBlank)
        expectPositive(channel.command("CWD /"), "Aurora recusou a raiz FTP.")

        for (index in segments.indices) {
            val currentCanonical = "/" + segments.take(index + 1).joinToString("/")
            val currentRemote = XboxPath.toAuroraFtpPath(currentCanonical)
            val cwd = channel.command("CWD $currentRemote")
            if (cwd.isPositive) continue

            if (index == 0) {
                throw FtpProtocolException(cwd.code, "Drive remoto não encontrado: ${segments[index]}.")
            }

            val mkd = channel.command("MKD $currentRemote")
            expectPositive(mkd, "Aurora recusou MKD.")
            expectPositive(channel.command("CWD $currentRemote"), "Aurora recusou CWD após MKD.")
        }
    }

    private fun openPassiveSocket(): Socket {
        val pasv = channel.command("PASV")
        val endpoint = if (pasv.code == 227) {
            val values = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
                .find(pasv.text)
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
                ?: throw FtpProtocolException(227, "Resposta PASV inválida.")

            val advertisedHost = values.take(4).joinToString(".")
            val host = if (advertisedHost == "0.0.0.0") {
                channel.remoteAddress.hostAddress ?: advertisedHost
            } else {
                advertisedHost
            }
            val port = values[4] * 256 + values[5]
            InetSocketAddress(host, port)
        } else {
            val epsv = channel.command("EPSV")
            if (epsv.code != 229) {
                throw FtpProtocolException(epsv.code, "Aurora não ofereceu modo passivo (PASV/EPSV).")
            }
            val port = Regex("\\(\\|\\|\\|(\\d+)\\|\\)")
                .find(epsv.text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: throw FtpProtocolException(229, "Resposta EPSV inválida.")
            InetSocketAddress(channel.remoteAddress, port)
        }

        return Socket().apply {
            soTimeout = timeoutMs
            try {
                connect(endpoint, timeoutMs)
            } catch (error: SocketTimeoutException) {
                throw FtpStageTimeoutException(
                    "abrindo conexão de dados passiva ${endpoint.address?.hostAddress ?: endpoint.hostString}:${endpoint.port}",
                    error,
                )
            }
        }
    }

    companion object {
        suspend fun connect(
            profile: XboxProfile,
            timeoutMs: Int = 15_000,
            connectTimeoutMs: Int = 1_200,
        ): AuroraPassiveFtpSession = withContext(Dispatchers.IO) {
            val endpoint = profile.endpoint.validated()
            val credentials = profile.credentials
            val channel = FtpCommandChannel(
                host = endpoint.host,
                port = endpoint.auroraFtpPort,
                timeoutMs = timeoutMs,
                connectTimeoutMs = connectTimeoutMs,
            )
            channel.connectAndLogin(
                credentials.auroraFtpUsername,
                credentials.auroraFtpPassword,
            )
            AuroraPassiveFtpSession(channel, timeoutMs)
        }
    }
}

private fun expectPositive(reply: FtpReply, message: String) {
    if (!reply.isPositive) throw FtpProtocolException(reply.code, message)
}

private fun expectPreliminary(reply: FtpReply, message: String) {
    if (!reply.isPreliminary) throw FtpProtocolException(reply.code, message)
}

private fun copyStreamWithProgress(
    input: InputStream,
    output: OutputStream,
    onProgress: (Long) -> Unit,
) {
    val buffer = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        total += read
        onProgress(total)
    }
    output.flush()
}
