package com.jhony4lves.echo360.domain.xbox

data class XboxEndpoint(
    val host: String = "",
    val novaPort: Int = 9999,
    val auroraFtpPort: Int = 21,
    val ftpDllPort: Int = 7564,
) {
    fun validated(): XboxEndpoint {
        val normalizedHost = host.trim()
        require(normalizedHost.isNotBlank()) { "Informe o IP ou host do Xbox." }
        require(!normalizedHost.contains("://")) { "Informe somente o IP ou host, sem http://." }
        require(novaPort in 1..65535) { "Porta NOVA inválida." }
        require(auroraFtpPort in 1..65535) { "Porta Aurora FTP inválida." }
        require(ftpDllPort in 1..65535) { "Porta FTPdll inválida." }

        return copy(host = normalizedHost)
    }
}

data class XboxCredentials(
    val novaUsername: String = "",
    val novaPassword: String = "",
    val auroraFtpUsername: String = "",
    val auroraFtpPassword: String = "",
    val ftpDllUsername: String = "",
    val ftpDllPassword: String = "",
) {
    override fun toString(): String = "XboxCredentials(<redacted>)"
}

data class XboxProfile(
    val endpoint: XboxEndpoint = XboxEndpoint(),
    val credentials: XboxCredentials = XboxCredentials(),
) {
    override fun toString(): String = "XboxProfile(endpoint=$endpoint, credentials=<redacted>)"
}

enum class XboxTransport {
    Nova,
    AuroraFtp,
    FtpDll,
}

enum class TransportStatus {
    Connected,
    NotConfigured,
    AuthFailed,
    Busy,
    Unreachable,
    ProtocolError,
}

data class TransportHealth(
    val transport: XboxTransport,
    val status: TransportStatus,
    val detail: String,
    val latencyMs: Long? = null,
)

data class XboxConnectionSnapshot(
    val nova: TransportHealth,
    val auroraFtp: TransportHealth,
    val ftpDll: TransportHealth,
    val checkedAtEpochMs: Long,
) {
    val consoleReachable: Boolean
        get() = listOf(nova, auroraFtp, ftpDll).any { it.status == TransportStatus.Connected }
}
