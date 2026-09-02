package com.jhony4lves.echo360.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.transfer.EchoTransferRepository
import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.domain.transfer.TransferHistoryEntry
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EchoTransferHostScreen(
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) { EchoTransferRepository(appContext) }
    var history by remember { mutableStateOf<List<TransferHistoryEntry>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        EchoTransferScreen(modifier = Modifier.fillMaxSize())

        ExtendedFloatingActionButton(
            onClick = {
                history = repository.transferHistory()
                showHistory = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp),
            containerColor = EchoColors.NeonGreen,
            contentColor = EchoColors.Void,
            shape = RoundedCornerShape(14.dp),
            icon = {
                Icon(Icons.Outlined.History, contentDescription = null)
            },
            text = {
                Text("HISTÓRICO", fontWeight = FontWeight.Black)
            },
        )
    }

    if (showHistory) {
        TransferHistoryDialog(
            entries = history,
            onClear = {
                repository.clearTransferHistory()
                history = emptyList()
            },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun TransferHistoryDialog(
    entries: List<TransferHistoryEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EchoColors.SurfaceHigh,
        titleContentColor = EchoColors.Text,
        textContentColor = EchoColors.TextSecondary,
        title = {
            Column {
                EchoEyebrow("ECHO OS // TRANSFER LOG")
                Spacer(Modifier.height(4.dp))
                Text("Histórico de transferências", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            if (entries.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = EchoColors.TextMuted,
                    )
                    Text(
                        "Nenhuma transferência concluída, cancelada ou com falha foi registrada ainda.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TransferHistoryCard(entry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("FECHAR")
            }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Text(" LIMPAR")
                }
            }
        },
    )
}

@Composable
private fun TransferHistoryCard(entry: TransferHistoryEntry) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        historyTimestamp(entry.finishedAtEpochMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                    Text(
                        displayHistoryPath(entry.remoteRoot),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                EchoStatusPill(
                    text = historyStatus(entry.status),
                    active = entry.status == TransferExecutionStatus.Completed,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HistoryMetric(
                    label = "ROTA",
                    value = entry.usedRoute?.let(::historyRoute) ?: historyRoute(entry.requestedRoute),
                    modifier = Modifier.weight(1f),
                )
                HistoryMetric(
                    label = "DADOS",
                    value = formatHistoryBytes(entry.transferredBytes),
                    modifier = Modifier.weight(1f),
                )
                HistoryMetric(
                    label = "TEMPO",
                    value = formatHistoryDuration(entry.durationMs),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = EchoColors.NeonGreen,
                )
                Text(
                    if (entry.averageBytesPerSecond > 0L) {
                        "${formatHistoryBytes(entry.averageBytesPerSecond)}/s"
                    } else {
                        "velocidade média indisponível"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
                Text(
                    "• ${entry.verifiedFiles}/${entry.fileCount} verificado(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
            }

            if (entry.retryCount > 0) {
                HistoryNote(
                    icon = Icons.Outlined.Sync,
                    text = "${entry.retryCount} retry(s) automático(s)",
                )
            }

            entry.fallbackReason?.let { reason ->
                HistoryNote(
                    icon = Icons.Outlined.Sync,
                    text = reason,
                )
            }

            entry.failedFile?.let { failed ->
                Text(
                    "Falha em: $failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.Warning,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HistoryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HistoryNote(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = EchoColors.Warning)
        Text(
            text = "  $text",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun historyTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

private fun historyStatus(status: TransferExecutionStatus): String = when (status) {
    TransferExecutionStatus.Completed -> "DONE"
    TransferExecutionStatus.Failed -> "FAIL"
    TransferExecutionStatus.Cancelled -> "CANCEL"
    TransferExecutionStatus.Preparing -> "PREP"
    TransferExecutionStatus.Uploading -> "UPLOAD"
    TransferExecutionStatus.Verifying -> "VERIFY"
}

private fun historyRoute(route: FtpRoute): String = when (route) {
    FtpRoute.Auto -> "AUTO"
    FtpRoute.Fast -> "FAST"
    FtpRoute.Background -> "BG"
}

private fun displayHistoryPath(value: String): String {
    val normalized = value.trim().ifBlank { "/" }
    if (!normalized.startsWith('/')) return normalized
    val pieces = normalized.removePrefix("/").split('/').filter(String::isNotBlank)
    if (pieces.isEmpty()) return "/"
    val drive = pieces.first()
    val rest = pieces.drop(1).joinToString("/")
    return if (rest.isBlank()) "$drive:/" else "$drive:/$rest"
}

private fun formatHistoryBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (value >= 100.0) {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

private fun formatHistoryDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}
