package com.jhony4lves.echo360.domain.remote

enum class EchoRemoteProvider {
    Nova,
    AuroraFtp,
}

enum class EchoRemoteCommand(
    val provider: EchoRemoteProvider,
    val disruptive: Boolean,
) {
    PauseTitle(EchoRemoteProvider.Nova, false),
    ResumeTitle(EchoRemoteProvider.Nova, false),
    TakeScreenshot(EchoRemoteProvider.Nova, false),
    RestartAurora(EchoRemoteProvider.AuroraFtp, true),
    RebootConsole(EchoRemoteProvider.AuroraFtp, true),
    ShutdownConsole(EchoRemoteProvider.AuroraFtp, true),
}

data class EchoRemoteResult(
    val command: EchoRemoteCommand,
    val provider: EchoRemoteProvider,
    val accepted: Boolean,
    val detail: String,
)
