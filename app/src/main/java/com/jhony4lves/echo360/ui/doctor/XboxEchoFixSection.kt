package com.jhony4lves.echo360.ui.doctor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.fix.EchoFixRepository
import com.jhony4lves.echo360.domain.fix.RemoteMoveStatus
import com.jhony4lves.echo360.domain.fix.RemoteRepairExecution
import com.jhony4lves.echo360.domain.fix.RemoteRepairState
import com.jhony4lves.echo360.domain.fix.RemoteRepairValidation
import com.jhony4lves.echo360.domain.fix.RepairPlan
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch

@Composable
fun XboxEchoFixSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(appContext) { EchoFixRepository(appContext) }
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("echo_fix", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var scanRoot by remember {
        mutableStateOf(
            prefs.getString("xbox_scan_root", EchoFixRepository.DEFAULT_XBOX_SCAN_ROOT)
                ?: EchoFixRepository.DEFAULT_XBOX_SCAN_ROOT,
        )
    }
    var plan by remember { mutableStateOf<RepairPlan?>(null) }
    var validation by remember { mutableStateOf<RemoteRepairValidation?>(null) }
    var execution by remember { mutableStateOf<RemoteRepairExecution?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }

    fun resetPrepared() {
        validation = null
        execution = null
        infoMessage = null
    }

    fun scanXbox() {
        val root = scanRoot.trim()
        if (root.isBlank()) return
        prefs.edit().putString("xbox_scan_root", root).apply()
        scanning = true
        plan = null
        errorMessage = null
        resetPrepared()

        scope.launch {
            runCatching {
                repository.scanXboxStockDlcInstaller(root, FtpRoute.Auto)
            }.onSuccess { result ->
                plan = result
            }.onFailure { error ->
                errorMessage = error.message ?: "Não foi possível escanear o Xbox."
            }
            scanning = false
        }
    }

    fun validate(currentPlan: RepairPlan) {
        validating = true
        validation = null
        execution = null
        errorMessage = null
        infoMessage = null
        scope.launch {
            runCatching {
                repository.validateRemotePlan(currentPlan, FtpRoute.Auto)
            }.onSuccess { result ->
                validation = result
            }.onFailure { error ->
                errorMessage = error.message ?: "Não foi possível validar origem e destino no Xbox."
            }
            validating = false
        }
    }

    fun executeMove(currentPlan: RepairPlan, currentValidation: RemoteRepairValidation) {
        moving = true
        execution = null
        errorMessage = null
        infoMessage = null
        scope.launch {
            runCatching {
                repository.executeRemoteMoves(currentPlan, currentValidation)
            }.onSuccess { result ->
                execution = result
                if (result.succeeded) {
                    infoMessage = if (result.movedCount > 0) {
                        "${result.movedCount} pacote(s) movido(s) no próprio HDD e verificado(s) por SIZE."
                    } else {
                        "Nada precisou ser movido; os pacotes já estavam corretos."
                    }
                    runCatching {
                        repository.validateRemotePlan(currentPlan, FtpRoute.Auto)
                    }.onSuccess { validation = it }
                } else {
                    errorMessage = "O reparo não terminou completamente. Nenhum conflito foi sobrescrito; veja os resultados abaixo."
                }
            }.onFailure { error ->
                errorMessage = error.message ?: "Falha ao executar o move server-side."
            }
            moving = false
        }
    }

    EchoPanel(
        modifier = modifier.fillMaxWidth(),
        highlighted = plan?.canExecute == true,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EchoEyebrow("GAME REPAIR // XBOX")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "EchoFix no próprio HDD",
                        style = MaterialTheme.typography.titleLarge,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Black,
                    )
                }
                EchoStatusPill(
                    text = when {
                        moving -> "MOVING"
                        validation?.canMove == true -> "READY"
                        plan?.canExecute == true -> "PLAN OK"
                        else -> "PRIMARY"
                    },
                    active = plan?.canExecute == true,
                )
            }

            Text(
                "Escaneia arquivos que já estão no Xbox, lê apenas o header STFS pela rede e tenta corrigir com RNFR/RNTO sem retransmitir o pacote inteiro.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )

            OutlinedTextField(
                value = scanRoot,
                onValueChange = {
                    scanRoot = it
                    plan = null
                    errorMessage = null
                    resetPrepared()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning && !moving && !validating,
                label = { Text("RAIZ PARA ESCANEAR") },
                leadingIcon = {
                    Icon(Icons.Outlined.Storage, contentDescription = null)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EchoColors.NeonGreen,
                    focusedLabelColor = EchoColors.NeonGreen,
                    cursorColor = EchoColors.NeonGreen,
                ),
            )

            Button(
                onClick = ::scanXbox,
                enabled = !scanning && !moving && !validating && scanRoot.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.NeonGreen,
                    contentColor = EchoColors.Void,
                ),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = EchoColors.Void,
                        strokeWidth = 2.dp,
                    )
                    Text("  ESCANEANDO XBOX...")
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Text(" ESCANEAR XBOX")
                }
            }

            Text(
                "Padrão: /Hdd1/Games. Se o download ficou em outra pasta, troque a raiz acima. O scanner não entra em /Hdd1/Content durante essa busca.",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextMuted,
            )

            errorMessage?.let {
                XboxFixMessage(Icons.Outlined.WarningAmber, it, EchoColors.Warning)
            }
            infoMessage?.let {
                XboxFixMessage(Icons.Outlined.CheckCircle, it, EchoColors.SignalGreen)
            }

            plan?.let { current ->
                HorizontalDivider(color = EchoColors.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "${current.actions.size} pacote(s) detectado(s)",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            formatXboxFixBytes(current.totalBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                    EchoStatusPill(
                        text = if (current.canExecute) "PLANO OK" else "REVISAR",
                        active = current.canExecute,
                    )
                }

                current.detectedPayloadPath?.let {
                    XboxFixField("PAYLOAD(S)", it)
                }
                if (current.titleIds.isNotEmpty()) {
                    XboxFixField("TITLE ID", current.titleIds.joinToString())
                }

                current.actions.take(MAX_XBOX_FIX_ACTIONS).forEachIndexed { index, action ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${index + 1}. ${action.source.fileName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "STFS ${action.metadata.magic.trim()} • ${action.metadata.contentTypeLabel} • ${action.metadata.titleId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextMuted,
                        )
                        Text(
                            "DE: ${action.source.remotePath ?: "--"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "PARA: ${action.destinationPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.NeonGreen,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (current.actions.size > MAX_XBOX_FIX_ACTIONS) {
                    Text(
                        "+ ${current.actions.size - MAX_XBOX_FIX_ACTIONS} pacote(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }

                current.issues.forEach { issue ->
                    Text(
                        "${issue.severity.name.uppercase()}: ${issue.message}",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (issue.severity) {
                            RepairSeverity.Info -> EchoColors.TextSecondary
                            RepairSeverity.Warning, RepairSeverity.Error -> EchoColors.Warning
                        },
                    )
                }

                if (current.canExecute && validation == null) {
                    OutlinedButton(
                        onClick = { validate(current) },
                        enabled = !validating && !moving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (validating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = EchoColors.NeonGreen,
                                strokeWidth = 2.dp,
                            )
                            Text("  VALIDANDO...")
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Text(" VALIDAR ORIGEM E DESTINO")
                        }
                    }
                }
            }

            validation?.let { currentValidation ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("XBOX // MOVE PLAN")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    XboxFixMetric("OK", currentValidation.alreadyCorrectCount.toString(), Modifier.weight(1f))
                    XboxFixMetric("MOVER", currentValidation.missingCount.toString(), Modifier.weight(1f))
                    XboxFixMetric("CONFLITO", currentValidation.conflictCount.toString(), Modifier.weight(1f))
                }

                if (currentValidation.sourceMissingCount > 0) {
                    XboxFixMessage(
                        Icons.Outlined.WarningAmber,
                        "${currentValidation.sourceMissingCount} origem(ns) não existem mais. Escaneie novamente antes de corrigir.",
                        EchoColors.Warning,
                    )
                }

                currentValidation.checks.take(MAX_XBOX_FIX_ACTIONS).forEach { check ->
                    val stateText = when (check.state) {
                        RemoteRepairState.AlreadyCorrect -> "OK"
                        RemoteRepairState.Missing -> "PRONTO PARA MOVE"
                        RemoteRepairState.Conflict -> "CONFLITO"
                        RemoteRepairState.SourceMissing -> "ORIGEM AUSENTE"
                    }
                    Text(
                        "${check.action.source.fileName} • $stateText",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (check.state) {
                            RemoteRepairState.AlreadyCorrect -> EchoColors.SignalGreen
                            RemoteRepairState.Missing -> EchoColors.NeonGreen
                            RemoteRepairState.Conflict, RemoteRepairState.SourceMissing -> EchoColors.Warning
                        },
                    )
                }

                when {
                    !currentValidation.canMove -> {
                        XboxFixMessage(
                            Icons.Outlined.WarningAmber,
                            "Move automático bloqueado. O EchoFix nunca sobrescreve um destino diferente e nunca move uma origem que mudou desde o scan.",
                            EchoColors.Warning,
                        )
                    }

                    currentValidation.missingCount == 0 -> {
                        XboxFixMessage(
                            Icons.Outlined.CheckCircle,
                            "Nada para corrigir: todos os destinos já têm o tamanho esperado.",
                            EchoColors.SignalGreen,
                        )
                    }

                    else -> {
                        Button(
                            onClick = { confirmMove = true },
                            enabled = !moving && !validating,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.NeonGreen,
                                contentColor = EchoColors.Void,
                            ),
                        ) {
                            Icon(Icons.Outlined.DriveFileMove, contentDescription = null)
                            Text(" MOVER ${currentValidation.missingCount} NO PRÓPRIO HDD")
                        }
                    }
                }
            }

            execution?.let { result ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("RESULTADO // SERVER-SIDE MOVE")
                result.results.take(MAX_XBOX_FIX_ACTIONS).forEach { item ->
                    val success = item.status == RemoteMoveStatus.Moved ||
                        item.status == RemoteMoveStatus.AlreadyCorrect
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            if (success) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (success) EchoColors.SignalGreen else EchoColors.Warning,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${item.action.source.fileName} • ${item.status.name.uppercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = EchoColors.Text,
                            )
                            Text(
                                item.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = EchoColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    val currentPlan = plan
    val currentValidation = validation
    if (confirmMove && currentPlan != null && currentValidation != null) {
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            containerColor = EchoColors.SurfaceHigh,
            title = { Text("Mover no próprio HDD?") },
            text = {
                Text(
                    "O EchoFix vai usar RNFR/RNTO para ${currentValidation.missingCount} pacote(s). " +
                        "Nenhum destino existente será sobrescrito. Depois do move, origem e destino são conferidos por SIZE; se a verificação ficar inconsistente, o app tenta rollback para o caminho original.",
                    color = EchoColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmMove = false
                        executeMove(currentPlan, currentValidation)
                    },
                ) {
                    Text("MOVER E VERIFICAR", color = EchoColors.NeonGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMove = false }) {
                    Text("CANCELAR")
                }
            },
        )
    }
}

@Composable
private fun XboxFixMetric(label: String, value: String, modifier: Modifier = Modifier) {
    EchoPanel(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextMuted,
            )
        }
    }
}

@Composable
private fun XboxFixField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Bold,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun XboxFixMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatXboxFixBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_XBOX_FIX_ACTIONS = 6
