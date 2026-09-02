package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AuroraSiteCommand(val ftpVerb: String) {
    RestartAurora("RESTART"),
    RebootConsole("REBOOT"),
    ShutdownConsole("SHUTDOWN"),
}

class AuroraFtpSiteClient(
    private val timeoutMs: Int = 10_000,
) {
    init {
        require(timeoutMs > 0) { "timeoutMs deve ser positivo." }
    }

    suspend fun execute(
        profile: XboxProfile,
        command: AuroraSiteCommand,
    ): String = withContext(Dispatchers.IO) {
        val endpoint = profile.endpoint.validated()
        val credentials = profile.credentials
        require(credentials.auroraFtpUsername.isNotBlank() && credentials.auroraFtpPassword.isNotBlank()) {
            "Configure usuário e senha do Aurora FTP na aba Xbox."
        }

        val channel = FtpCommandChannel(
            host = endpoint.host,
            port = endpoint.auroraFtpPort,
            timeoutMs = timeoutMs,
        )
        try {
            channel.connectAndLogin(
                username = credentials.auroraFtpUsername,
                password = credentials.auroraFtpPassword,
            )
            val reply = channel.command("SITE ${command.ftpVerb}")
            if (!reply.isPositive) {
                throw FtpProtocolException(
                    reply.code,
                    "Aurora FTP recusou SITE ${command.ftpVerb}: ${reply.text}",
                )
            }
            reply.text
        } finally {
            channel.close()
        }
    }
}
