package com.jhony4lves.echo360.ui.doctor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.jhony4lves.echo360.data.transfer.EchoTransferRepository
import com.jhony4lves.echo360.domain.fix.RepairPlan
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.domain.transfer.TransferExecutionProgress
import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch

@Composable
fun EchoFixSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(appContext) { EchoFixRepository(appContext) }
    val transferRepository = remember(appContext) { EchoTransferRepository(appContext) }
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("echo_fix", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var selectedUri by remember {
        mutableStateOf(prefs.getString("installer_tree", null)?.let(Uri::parse))
    }
    var plan by remember { mutableStateOf<RepairPlan?>(null) }
    var analyses by remember { mutableStateOf<List<TransferAnalysis>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var executionProgress by remember { mutableStateOf<TransferExecutionProgress?>(null) }
    var cancellationToken by remember { mutableStateOf<TransferCancellationToken?>(null) }

    fun invalidatePreparedState() {
        analyses = null
        executionProgress = null
        resultMessage = null
        cancellationToken = null
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            selectedUri = uri
            prefs.edit().putString("installer_tree", uri.toString()).apply()
            plan = null
            errorMessage = null
            invalidatePreparedState()
        }
    }

    fun scan() {
        val uri = selectedUri ?: return
        scanning = true
        plan = null
        errorMessage = null
        invalidatePreparedState()
        scope.launch {
            runCatching { repository.scanStockDlcInstaller(uri) }
                .onSuccess { plan = it }
                .onFailure { errorMessage = it.message ?: "Falha ao analisar a pasta." }
            scanning = false
        }
    }

    fun validateOnXbox(currentPlan: RepairPlan) {
        validating = true
        errorMessage = null
        resultMessage = null
        analyses = null
        scope.launch {
            runCatching {
                repository.prepareTransferAnalyses(currentPlan, FtpRoute.Auto)
            }.onSuccess { prepared ->
                analyses = prepared
            }.onFailure { error ->
                errorMessage = error.message ?: "Não foi possível validar os destinos no Xbox."
            }
            validating = false
        }
    }

    fun executeRepair(currentPlan: RepairPlan, prepared: List<TransferAnalysis>) {
        if (prepared.any { it.differentCount > 0 }) {
            errorMessage = "Reparo bloqueado: existe arquivo no destino com tamanho diferente. O EchoFix não sobrescreve conflitos automaticamente."
            return
        }

        val token = TransferCancellationToken()
        cancellationToken = token
        repairing = true
        errorMessage = null
        resultMessage = null
        executionProgress = null

        scope.launch {
            var failed = false
            var uploadedFiles = 0

            for (analysis in prepared) {
                if (token.isCancelled()) break
                if (analysis.uploadCount == 0) continue

                val result = transferRepository.execute(
                    analysis = analysis,
                    cancellationToken = token,
                    onProgress = { executionProgress = it },
                )

                uploadedFiles += result.verifiedFiles
                when (result.status) {
                    TransferExecutionStatus.Completed -> Unit
                    TransferExecutionStatus.Cancelled -> {
                        resultMessage = "Reparo cancelado com segurança. Arquivos já verificados foram mantidos."
                        failed = true
                        break
                    }
                    TransferExecutionStatus.Failed -> {
                        errorMessage = result.message ?: "EchoTransfer falhou durante o reparo."
                        failed = true
                        break
                    }
                    else -> Unit
                }
            }

            if (!failed && !token.isCancelled()) {
                resultMessage = "$uploadedFiles pacote(s) enviados e verificados por SIZE."
                runCatching {
                    repository.prepareTransferAnalyses(currentPlan, FtpRoute.Auto)
                }.onSuccess { verified ->
                    analyses = verified
                    if (verified.sumOf { it.sameCount } == currentPlan.actions.size) {
                        resultMessage = "Reparo concluído: ${currentPlan.actions.size} pacote(s) estão no destino correto e foram verificados por SIZE."
                    }
                }
            }

            repairing = false
            cancellationToken = null
        }
    }

    val currentAnalyses = analyses
    val sameCount = currentAnalyses?.sumOf(TransferAnalysis::sameCount) ?: 0
    val missingCount = currentAnalyses?.sumOf(TransferAnalysis::missingCount) ?: 0
    val differentCount = currentAnalyses?.sumOf(TransferAnalysis::differentCount) ?: 0

    EchoPanel(modifier = modifier.fillMaxWidth(), highlighted = plan?.canExecute == true) {
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
                    EchoEyebrow("GAME REPAIR // ECHOFIX")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Instaladores de disco",
                        style = MaterialTheme.typography.titleLarge,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Black,
                    )
                }
                EchoStatusPill(
                    text = when {
                        repairing -> "REPAIRING"
                        currentAnalyses != null && differentCount == 0 -> "READY"
                        plan?.canExecute == true -> "PLAN OK"
                        else -> "DRY RUN"
                    },
                    active = plan?.canExecute == true,
                )
            }

            Text(
                "Detecta FFED2000/FFFFFFFF, lê STFS, calcula o destino e só entrega a escrita ao EchoTransfer depois da validação.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )

            OutlinedButton(
                onClick = { picker.launch(null) },
                enabled = !repairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(if (selectedUri == null) " ESCOLHER PASTA EXTRAÍDA" else " TROCAR PASTA")
            }

            selectedUri?.let { uri ->
                Text(
                    text = uri.lastPathSegment ?: uri.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Button(
                    onClick = ::scan,
                    enabled = !scanning && !repairing && !validating,
                    modifier = Modifier.fillMaxWidth(),
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
                        Text("  ANALISANDO...")
                    } else {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                        Text(" ANALISAR E MONTAR PLANO")
                    }
                }
            }

            errorMessage?.let { message ->
                FixMessage(
                    icon = Icons.Outlined.WarningAmber,
                    message = message,
                    color = EchoColors.Warning,
                )
            }

            resultMessage?.let { message ->
                FixMessage(
                    icon = Icons.Outlined.CheckCircle,
                    message = message,
                    color = EchoColors.SignalGreen,
                )
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
                            "${current.actions.size} pacote(s) roteado(s)",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            formatFixBytes(current.totalBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                    EchoStatusPill(
                        text = if (current.canExecute) "PLANO OK" else "REVISAR",
                        active = current.canExecute,
                    )
                }

                current.detectedPayloadPath?.let { path ->
                    FixField("PAYLOAD DETECTADO", path)
                }

                if (current.titleIds.isNotEmpty()) {
                    FixField("TITLE ID", current.titleIds.joinToString())
                }

                current.actions.take(MAX_VISIBLE_ACTIONS).forEachIndexed { index, action ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.BuildCircle,
                                contentDescription = null,
                                tint = EchoColors.NeonGreen,
                            )
                            Text(
                                "  ${index + 1}. ${action.source.fileName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "STFS ${action.metadata.magic.trim()} • ${action.metadata.contentTypeLabel} • ${action.metadata.titleId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextMuted,
                        )
                        Text(
                            "→ ${action.destinationPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextSecondary,
                        )
                    }
                }

                if (current.actions.size > MAX_VISIBLE_ACTIONS) {
                    Text(
                        "+ ${current.actions.size - MAX_VISIBLE_ACTIONS} ação(ões) no plano",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }

                current.issues.forEach { issue ->
                    val issueColor = when (issue.severity) {
                        RepairSeverity.Info -> EchoColors.TextSecondary
                        RepairSeverity.Warning, RepairSeverity.Error -> EchoColors.Warning
                    }
                    Text(
                        "${issue.severity.name.uppercase()}: ${issue.message}",
                        style = MaterialTheme.typography.labelMedium,
                        color = issueColor,
                    )
                }

                if (current.canExecute && currentAnalyses == null) {
                    OutlinedButton(
                        onClick = { validateOnXbox(current) },
                        enabled = !validating && !repairing,
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
                            Text(" VALIDAR DESTINOS NO XBOX")
                        }
                    }
                    Text(
                        "A validação usa SIZE e o roteador AUTO. Nenhum arquivo de jogo é alterado nessa etapa.",
                        style = MaterialTheme.typography.labelSmall,
                        color = EchoColors.TextMuted,
                    )
                }

                currentAnalyses?.let { prepared ->
                    HorizontalDivider(color = EchoColors.Border)
                    EchoEyebrow("DESTINO // XBOX")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FixMetric("IGUAIS", sameCount.toString(), Modifier.weight(1f))
                        FixMetric("AUSENTES", missingCount.toString(), Modifier.weight(1f))
                        FixMetric("CONFLITOS", differentCount.toString(), Modifier.weight(1f))
                    }

                    when {
                        differentCount > 0 -> {
                            FixMessage(
                                icon = Icons.Outlined.WarningAmber,
                                message = "$differentCount arquivo(s) existem com tamanho diferente. O reparo automático está bloqueado para evitar sobrescrita destrutiva.",
                                color = EchoColors.Warning,
                            )
                        }

                        missingCount == 0 -> {
                            FixMessage(
                                icon = Icons.Outlined.CheckCircle,
                                message = "Nada para corrigir: todos os pacotes já estão no destino e têm o tamanho esperado.",
                                color = EchoColors.SignalGreen,
                            )
                        }

                        else -> {
                            Button(
                                onClick = { executeRepair(current, prepared) },
                                enabled = !repairing && !validating,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EchoColors.NeonGreen,
                                    contentColor = EchoColors.Void,
                                ),
                            ) {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                                Text(" CORRIGIR $missingCount PACOTE(S) VIA AUTO")
                            }
                        }
                    }
                }

                executionProgress?.let { progress ->
                    HorizontalDivider(color = EchoColors.Border)
                    EchoEyebrow("ECHOFIX // TRANSFER")
                    progress.currentFile?.let { file ->
                        Text(
                            file,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress.overallFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = EchoColors.NeonGreen,
                        trackColor = EchoColors.SurfaceBright,
                    )
                    Text(
                        "${(progress.overallFraction * 100).toInt()}% • ${formatFixBytes(progress.logicalBytesTransferred)} / ${formatFixBytes(progress.totalBytes)}" +
                            if (progress.bytesPerSecond > 0L) " • ${formatFixBytes(progress.bytesPerSecond)}/s" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextSecondary,
                    )
                }

                if (repairing) {
                    OutlinedButton(
                        onClick = { cancellationToken?.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoColors.Error),
                    ) {
                        Icon(Icons.Outlined.Cancel, contentDescription = null)
                        Text(" CANCELAR COM SEGURANÇA")
                    }
                }
            }
        }
    }
}

@Composable
private fun FixField(label: String, value: String) {
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
private fun FixMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun FixMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
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

private fun formatFixBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_VISIBLE_ACTIONS = 6
