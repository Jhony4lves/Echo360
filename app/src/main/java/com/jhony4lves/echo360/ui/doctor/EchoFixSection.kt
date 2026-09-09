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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.jhony4lves.echo360.domain.fix.RepairPlan
import com.jhony4lves.echo360.domain.fix.RepairSeverity
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
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("echo_fix", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var selectedUri by remember {
        mutableStateOf(prefs.getString("installer_tree", null)?.let(Uri::parse))
    }
    var plan by remember { mutableStateOf<RepairPlan?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

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
        }
    }

    fun scan() {
        val uri = selectedUri ?: return
        scanning = true
        plan = null
        errorMessage = null
        scope.launch {
            runCatching { repository.scanStockDlcInstaller(uri) }
                .onSuccess { plan = it }
                .onFailure { errorMessage = it.message ?: "Falha ao analisar a pasta." }
            scanning = false
        }
    }

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
                EchoStatusPill(text = "DRY RUN", active = true)
            }

            Text(
                "Detecta o padrão FFED2000/FFFFFFFF, lê o header STFS e calcula o destino correto sem mover ou apagar nada.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )

            OutlinedButton(
                onClick = { picker.launch(null) },
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
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 9.dp),
                            color = EchoColors.Void,
                            strokeWidth = 2.dp,
                        )
                        Text("ANALISANDO...")
                    } else {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                        Text(" ANALISAR E MONTAR PLANO")
                    }
                }
            }

            errorMessage?.let { message ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = EchoColors.Warning,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Warning,
                    )
                }
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

                if (current.canExecute) {
                    Text(
                        "Plano pronto. Esta fase ainda é somente leitura; a execução será entregue ao EchoTransfer na próxima etapa.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.NeonGreen,
                    )
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

private fun formatFixBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_VISIBLE_ACTIONS = 6
