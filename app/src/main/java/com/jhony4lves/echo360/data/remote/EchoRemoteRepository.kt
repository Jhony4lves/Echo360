package com.jhony4lves.echo360.data.remote

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.remote.EchoRemoteCommand
import com.jhony4lves.echo360.domain.remote.EchoRemoteProvider
import com.jhony4lves.echo360.domain.remote.EchoRemoteResult
import com.jhony4lves.echo360.network.ftp.AuroraFtpSiteClient
import com.jhony4lves.echo360.network.ftp.AuroraSiteCommand
import com.jhony4lves.echo360.network.nova.AuroraNovaClient

class EchoRemoteRepository(
    context: Context,
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
    private val siteClient: AuroraFtpSiteClient = AuroraFtpSiteClient(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun execute(command: EchoRemoteCommand): EchoRemoteResult {
        val profile = configStore.load()
            ?: return EchoRemoteResult(
                command = command,
                provider = command.provider,
                accepted = false,
                detail = "Configure e salve o Xbox antes de usar o EchoRemote.",
            )

        return runCatching {
            when (command) {
                EchoRemoteCommand.PauseTitle -> {
                    novaClient.setMainThreadSuspended(profile, suspended = true)
                    "NOVA aceitou a pausa da thread principal."
                }
                EchoRemoteCommand.ResumeTitle -> {
                    novaClient.setMainThreadSuspended(profile, suspended = false)
                    "NOVA aceitou a retomada da thread principal."
                }
                EchoRemoteCommand.TakeScreenshot -> {
                    novaClient.takeScreenshot(profile)
                    "NOVA criou uma captura de tela do título atual."
                }
                EchoRemoteCommand.RestartAurora -> {
                    siteClient.execute(profile, AuroraSiteCommand.RestartAurora)
                    "Aurora FTP aceitou o comando de reinício do Aurora."
                }
                EchoRemoteCommand.RebootConsole -> {
                    siteClient.execute(profile, AuroraSiteCommand.RebootConsole)
                    "Aurora FTP aceitou o comando de reinício do console."
                }
                EchoRemoteCommand.ShutdownConsole -> {
                    siteClient.execute(profile, AuroraSiteCommand.ShutdownConsole)
                    "Aurora FTP aceitou o comando de desligamento do console."
                }
            }
        }.fold(
            onSuccess = { detail ->
                EchoRemoteResult(
                    command = command,
                    provider = command.provider,
                    accepted = true,
                    detail = detail,
                )
            },
            onFailure = { error ->
                EchoRemoteResult(
                    command = command,
                    provider = command.provider,
                    accepted = false,
                    detail = error.message ?: "O provider recusou ou não confirmou a ação.",
                )
            },
        )
    }

    fun providerLabel(provider: EchoRemoteProvider): String = when (provider) {
        EchoRemoteProvider.Nova -> "NOVA"
        EchoRemoteProvider.AuroraFtp -> "Aurora FTP"
    }
}
