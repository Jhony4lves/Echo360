package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket

class FtpDllActiveFtpSession private constructor(
    private val channel: FtpCommandChannel,
    private val timeoutMs: Int,
) : XboxFtpSession {
    private val mutex = Mutex()

    override suspend fun list(canonicalPath: String): List<RemoteEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val canonical = XboxPath.canonical(canonicalPath)
            val remote = XboxPath.toFtpDllPath(canonical)
            requirePositive(channel.command("CWD $remote"), "FTPdll recusou CWD.")

            prepareActiveListener().use { listener ->
                channel.send("LIST")
                requirePreliminary(channel.read(), "FTPdll não iniciou LIST.")

                listener.accept().use { dataSocket ->
                    dataSocket.soTimeout = timeoutMs
                    val lines = dataSocket.getInputStream()
                        .bufferedReader(Charsets.UTF_8)
                        .readLines()
                    requirePositive(channel.read(), "FTPdll não concluiu LIST.")
                    UnixFtpListParser.parse(lines, canonical)
                }
            }
        }
    }

    override suspend fun size(canonicalPath: String): Long? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remote = XboxPath.toFtpDllPath(canonicalPath)
            val reply = channel.command("SIZE $remote")
            when (reply.code) {
                213 -> reply.lines.first().substringAfter("213").trim().toLongOrNull()
                550 -> null
                else -> throw FtpProtocolException(reply.code, "FTPdll recusou SIZE.")
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
                val remote = XboxPath.toFtpDllPath(canonical)

                prepareActiveListener().use { listener ->
                    channel.send("STOR $remote")
                    requirePreliminary(channel.read(), "FTPdll não iniciou STOR.")

                    listener.accept().use { dataSocket ->
                        dataSocket.soTimeout = timeoutMs
                        dataSocket.getOutputStream().use { output ->
                            copyActiveWithProgress(input, output, onProgress)
                        }
                    }

                    requirePositive(channel.read(), "FTPdll não concluiu STOR.")
                }
            }
        }
    }

    override suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) { channel.close() }
    }

    private fun ensureDirectoryLocked(canonicalPath: String) {
        val canonical = XboxPath.canonical(canonicalPath)
        if (canonical == "/") return

        val segments = canonical.removePrefix("/").split('/').filter { it.isNotBlank() }
        requirePositive(channel.command("CWD /"), "FTPdll recusou a raiz FTP.")

        for (index in segments.indices) {
            val currentCanonical = "/" + segments.take(index + 1).joinToString("/")
            val currentRemote = XboxPath.toFtpDllPath(currentCanonical)
            val cwd = channel.command("CWD $currentRemote")
            if (cwd.isPositive) continue

            if (index == 0) {
                throw FtpProtocolException(cwd.code, "Drive FTPdll não encontrado: ${segments[index]}.")
            }

            val mkd = channel.command("MKD $currentRemote")
            requirePositive(mkd, "FTPdll recusou MKD.")
            requirePositive(channel.command("CWD $currentRemote"), "FTPdll recusou CWD após MKD.")
        }
    }

    private fun prepareActiveListener(): ServerSocket {
        val localAddress = channel.localAddress
        val addressBytes = localAddress.address
        if (addressBytes.size != 4) {
            throw FtpProtocolException(null, "FTP ativo exige IPv4 na rede local.")
        }

        val localHost = localAddress.hostAddress
            ?: throw FtpProtocolException(null, "Não foi possível determinar o IPv4 local.")

        val listener = ServerSocket().apply {
            reuseAddress = true
            soTimeout = timeoutMs
            bind(InetSocketAddress(localHost, 0), 1)
        }

        val octets = addressBytes.map { it.toInt() and 0xff }
        val port = listener.localPort
        val portHigh = port / 256
        val portLow = port % 256
        val argument = "${octets[0]},${octets[1]},${octets[2]},${octets[3]},$portHigh,$portLow"

        val reply = channel.command("PORT $argument")
        if (!reply.isPositive) {
            listener.close()
            throw FtpProtocolException(reply.code, "FTPdll recusou PORT.")
        }

        return listener
    }

    companion object {
        suspend fun connect(
            profile: XboxProfile,
            timeoutMs: Int = 15_000,
        ): FtpDllActiveFtpSession = withContext(Dispatchers.IO) {
            val endpoint = profile.endpoint.validated()
            val credentials = profile.credentials
            val channel = FtpCommandChannel(endpoint.host, endpoint.ftpDllPort, timeoutMs)
            channel.connectAndLogin(
                credentials.ftpDllUsername,
                credentials.ftpDllPassword,
            )
            FtpDllActiveFtpSession(channel, timeoutMs)
        }
    }
}

private fun requirePositive(reply: FtpReply, message: String) {
    if (!reply.isPositive) throw FtpProtocolException(reply.code, message)
}

private fun requirePreliminary(reply: FtpReply, message: String) {
    if (!reply.isPreliminary) throw FtpProtocolException(reply.code, message)
}

private fun copyActiveWithProgress(
    input: InputStream,
    output: java.io.OutputStream,
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
