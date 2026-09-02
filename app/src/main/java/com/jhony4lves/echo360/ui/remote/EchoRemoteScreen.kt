package com.jhony4lves.echo360.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.remote.EchoRemoteRepository
import com.jhony4lves.echo360.domain.remote.EchoRemoteCommand
import com.jhony4lves.echo360.domain.remote.EchoRemoteProvider
import com.jhony4lves.echo360.domain.remote.EchoRemoteResult
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch

@Composable
fun EchoRemoteScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { EchoRemoteRepository(context) }
    val scope = rememberCoroutineScope()

    var busyCommand by remember { mutableStateOf<EchoRemoteCommand?>(null) }
    var pendingConfirmation by remember { mutableStateOf<EchoRemoteCommand?>(null) }
    var lastResult by remember { mutableStateOf<EchoRemoteResult?>(null) }

    fun execute(command: EchoRemoteCommand) {
        if (busyCommand != null) return
        busyCommand = command
        lastResult = null
        scope.launch {
            lastResult = repository.execute(command)
            busyCommand = null
        }
    }

    pendingConfirmation?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            containerColor = EchoColors.SurfaceHigh,
            title = {
                Text(
                    text = remoteConfirmationTitle(command),
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Black,
                )
            },
            text = {
                Text(
                    text = remoteConfirmationBody(command),
                    color = EchoColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfirmation = null
                        execute(command)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (command == EchoRemoteCommand.ShutdownConsole) {
                            EchoColors.Error
                        } else {
                            EchoColors.Warning
                        },
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    Text(remoteConfirmationButton(command), fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("CANCELAR")
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EchoEyebrow("ECHO REMOTE // DOCUMENTED CONTROL")
            Text(
                text = "Controle remoto",
                style = MaterialTheme.typography.headlineMedium,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Ações limitadas aos contratos documentados do NOVA e aos comandos SITE conhecidos do Aurora FTP. Sem gamepad virtual inventado.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    EchoEyebrow("PROVIDERS")
                    Text(
                        "NOVA: pausar, retomar e capturar tela.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Text,
                    )
                    Text(
                        "Aurora FTP: reiniciar Aurora, reiniciar console e desligar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Text,
                    )
                    Text(
                        "Ligar o Xbox remotamente não é anunciado: não há Wake-on-LAN confiável neste contrato.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }

        item {
            EchoEyebrow("TÍTULO ATUAL // NOVA")
        }
        item {
            RemoteActionCard(
                title = "Pausar título",
                detail = "POST /thread/state com suspend=1.",
                provider = EchoRemoteProvider.Nova,
                command = EchoRemoteCommand.PauseTitle,
                busyCommand = busyCommand,
                onAction = ::execute,
            )
        }
        item {
            RemoteActionCard(
                title = "Retomar título",
                detail = "POST /thread/state com suspend=0.",
                provider = EchoRemoteProvider.Nova,
                command = EchoRemoteCommand.ResumeTitle,
                busyCommand = busyCommand,
                onAction = ::execute,
            )
        }
        item {
            RemoteActionCard(
                title = "Capturar tela",
                detail = "GET /screencapture/meta cria uma captura do título atual no Aurora.",
                provider = EchoRemoteProvider.Nova,
                command = EchoRemoteCommand.TakeScreenshot,
                busyCommand = busyCommand,
                onAction = ::execute,
            )
        }

        item {
            EchoEyebrow("CONSOLE // AÇÕES DISRUPTIVAS")
        }
        item {
            RemoteActionCard(
                title = "Reiniciar Aurora",
                detail = "Aurora FTP SITE RESTART. Fecha/reabre o dashboard; serviços podem ficar indisponíveis temporariamente.",
                provider = EchoRemoteProvider.AuroraFtp,
                command = EchoRemoteCommand.RestartAurora,
                busyCommand = busyCommand,
                onAction = { pendingConfirmation = it },
            )
        }
        item {
            RemoteActionCard(
                title = "Reiniciar Xbox",
                detail = "Aurora FTP SITE REBOOT. Interrompe jogo, transferência e sessão atual.",
                provider = EchoRemoteProvider.AuroraFtp,
                command = EchoRemoteCommand.RebootConsole,
                busyCommand = busyCommand,
                onAction = { pendingConfirmation = it },
            )
        }
        item {
            RemoteActionCard(
                title = "Desligar Xbox",
                detail = "Aurora FTP SITE SHUTDOWN. Desliga o console e encerra toda atividade atual.",
                provider = EchoRemoteProvider.AuroraFtp,
                command = EchoRemoteCommand.ShutdownConsole,
                busyCommand = busyCommand,
                onAction = { pendingConfirmation = it },
                danger = true,
            )
        }

        lastResult?.let { result ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EchoEyebrow("ÚLTIMA AÇÃO")
                            EchoStatusPill(
                                text = if (result.accepted) "ACEITA" else "NÃO CONFIRMADA",
                                active = result.accepted,
                            )
                        }
                        Text(
                            "${remoteCommandLabel(result.command)} • ${remoteProviderLabel(result.provider)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            result.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.accepted) EchoColors.TextSecondary else EchoColors.Warning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteActionCard(
    title: String,
    detail: String,
    provider: EchoRemoteProvider,
    command: EchoRemoteCommand,
    busyCommand: EchoRemoteCommand?,
    onAction: (EchoRemoteCommand) -> Unit,
    danger: Boolean = false,
) {
    val isBusy = busyCommand != null
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        remoteProviderLabel(provider),
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.NeonGreen,
                    )
                }
                if (busyCommand == command) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = EchoColors.NeonGreen,
                    )
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
            if (danger) {
                Button(
                    onClick = { onAction(command) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.Error,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    Text("DESLIGAR…", fontWeight = FontWeight.Black)
                }
            } else if (command.disruptive) {
                OutlinedButton(
                    onClick = { onAction(command) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("CONFIRMAR AÇÃO…")
                }
            } else {
                Button(
                    onClick = { onAction(command) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    Text("EXECUTAR", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private fun remoteProviderLabel(provider: EchoRemoteProvider): String = when (provider) {
    EchoRemoteProvider.Nova -> "NOVA"
    EchoRemoteProvider.AuroraFtp -> "AURORA FTP"
}

private fun remoteCommandLabel(command: EchoRemoteCommand): String = when (command) {
    EchoRemoteCommand.PauseTitle -> "Pausar título"
    EchoRemoteCommand.ResumeTitle -> "Retomar título"
    EchoRemoteCommand.TakeScreenshot -> "Capturar tela"
    EchoRemoteCommand.RestartAurora -> "Reiniciar Aurora"
    EchoRemoteCommand.RebootConsole -> "Reiniciar Xbox"
    EchoRemoteCommand.ShutdownConsole -> "Desligar Xbox"
}

private fun remoteConfirmationTitle(command: EchoRemoteCommand): String = when (command) {
    EchoRemoteCommand.RestartAurora -> "Reiniciar o Aurora?"
    EchoRemoteCommand.RebootConsole -> "Reiniciar o Xbox?"
    EchoRemoteCommand.ShutdownConsole -> "Desligar o Xbox?"
    else -> "Confirmar ação?"
}

private fun remoteConfirmationBody(command: EchoRemoteCommand): String = when (command) {
    EchoRemoteCommand.RestartAurora ->
        "O dashboard Aurora será reiniciado. Transferências e serviços do Aurora podem cair durante o processo."
    EchoRemoteCommand.RebootConsole ->
        "O console será reiniciado. O jogo atual, transferências e sessões em andamento serão interrompidos."
    EchoRemoteCommand.ShutdownConsole ->
        "O console será desligado. O Echo360 não afirma conseguir ligá-lo novamente pela rede."
    else -> "Esta ação altera o estado atual do console."
}

private fun remoteConfirmationButton(command: EchoRemoteCommand): String = when (command) {
    EchoRemoteCommand.RestartAurora -> "REINICIAR AURORA"
    EchoRemoteCommand.RebootConsole -> "REINICIAR XBOX"
    EchoRemoteCommand.ShutdownConsole -> "DESLIGAR XBOX"
    else -> "CONFIRMAR"
}
