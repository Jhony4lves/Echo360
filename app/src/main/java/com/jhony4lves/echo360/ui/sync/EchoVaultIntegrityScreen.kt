package com.jhony4lves.echo360.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.sync.SaveVaultIntegrityCancelledException
import com.jhony4lves.echo360.data.sync.SaveVaultIntegrityProgress
import com.jhony4lves.echo360.data.sync.SaveVaultLocalIntegrityVerifier
import com.jhony4lves.echo360.data.sync.SaveVaultStore
import com.jhony4lves.echo360.data.sync.StoredSaveVaultSnapshot
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityCode
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityReport
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegritySeverity
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EchoVaultIntegrityScreen(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) { SaveVaultStore(appContext) }
    val verifier = remember { SaveVaultLocalIntegrityVerifier() }
    var snapshots by remember { mutableStateOf(store.snapshots()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EchoEyebrow("ECHO INTEGRITY // LOCAL VAULT")
            Text(
                "Verificar snapshots",
                style = MaterialTheme.typography.headlineMedium,
                color = EchoColors.Text,
            )
            Text(
                "Recalcula SHA-256 somente dos arquivos declarados no manifesto e procura arquivos extras sem hashá-los.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EchoEyebrow("SEM ALTERAÇÃO")
                    Text(
                        "Esta verificação lê apenas o armazenamento privado do Echo360 no Android. Não conecta ao Xbox e não modifica o snapshot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                    OutlinedButton(onClick = { snapshots = store.snapshots() }) {
                        Text("ATUALIZAR LISTA")
                    }
                }
            }
        }

        if (snapshots.isEmpty()) {
            item {
                Text(
                    "Nenhum Save Vault concluído para verificar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextMuted,
                )
            }
        } else {
            items(snapshots, key = { it.manifest.id }) { snapshot ->
                VaultIntegritySnapshotCard(snapshot = snapshot, verifier = verifier)
            }
        }
    }
}

@Composable
private fun VaultIntegritySnapshotCard(
    snapshot: StoredSaveVaultSnapshot,
    verifier: SaveVaultLocalIntegrityVerifier,
) {
    val scope = rememberCoroutineScope()
    var checking by remember(snapshot.manifest.id) { mutableStateOf(false) }
    var progress by remember(snapshot.manifest.id) { mutableStateOf<SaveVaultIntegrityProgress?>(null) }
    var report by remember(snapshot.manifest.id) { mutableStateOf<SaveVaultIntegrityReport?>(null) }
    var error by remember(snapshot.manifest.id) { mutableStateOf<String?>(null) }
    var token by remember(snapshot.manifest.id) { mutableStateOf<TransferCancellationToken?>(null) }

    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        integrityDate(snapshot.manifest.createdAtEpochMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                    Text(
                        snapshot.manifest.sourceRoot,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                report?.let { current ->
                    EchoStatusPill(
                        text = when {
                            !current.valid -> "ALTERADO"
                            current.extraFiles > 0 -> "ÍNTEGRO + EXTRAS"
                            current.complete -> "ÍNTEGRO"
                            else -> "INCOMPLETO"
                        },
                        active = current.valid && current.complete,
                    )
                } ?: EchoStatusPill(if (checking) "VERIFICANDO" else "NÃO VERIFICADO", active = false)
            }

            Text(
                "${snapshot.manifest.fileCount} arquivo(s) • ${integrityBytes(snapshot.manifest.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextSecondary,
            )

            progress?.let { current ->
                val fraction = if (current.fileCount <= 0) 0f
                else (current.fileIndex.toFloat() / current.fileCount.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${current.fileIndex}/${current.fileCount} • ${current.currentFile}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            report?.let { current ->
                Text(
                    "${current.checkedFiles}/${current.expectedFiles} hash(es) recalculado(s) • ${current.extraFiles} extra(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
                if (current.findings.isEmpty()) {
                    Text(
                        "Manifesto, tamanhos e SHA-256 conferem.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.NeonGreen,
                    )
                } else {
                    current.findings.take(6).forEach { finding ->
                        Text(
                            "${integrityFindingLabel(finding.code)} • ${finding.relativePath}: ${finding.evidence}",
                            style = MaterialTheme.typography.labelMedium,
                            color = when (finding.severity) {
                                SaveVaultIntegritySeverity.Error -> EchoColors.Error
                                SaveVaultIntegritySeverity.Warning -> EchoColors.Warning
                                SaveVaultIntegritySeverity.Info -> EchoColors.Info
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current.findings.size > 6) {
                        Text(
                            "+ ${current.findings.size - 6} finding(s) não exibido(s) neste resumo.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = EchoColors.Warning)
            }

            if (checking) {
                OutlinedButton(onClick = { token?.cancel() }) {
                    Text("CANCELAR VERIFICAÇÃO")
                }
            } else {
                Button(
                    onClick = {
                        checking = true
                        progress = null
                        report = null
                        error = null
                        val cancellation = TransferCancellationToken()
                        token = cancellation
                        scope.launch {
                            try {
                                report = verifier.verify(
                                    snapshot = snapshot,
                                    cancellationToken = cancellation,
                                    onProgress = { progress = it },
                                )
                            } catch (_: SaveVaultIntegrityCancelledException) {
                                error = "Verificação cancelada; nenhum arquivo foi alterado."
                            } catch (failure: Throwable) {
                                error = failure.message ?: "Não foi possível verificar este snapshot."
                            } finally {
                                token = null
                                checking = false
                                progress = null
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    Text("VERIFICAR HASHES", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private fun integrityFindingLabel(code: SaveVaultIntegrityCode): String = when (code) {
    SaveVaultIntegrityCode.MissingFile -> "AUSENTE"
    SaveVaultIntegrityCode.WrongObjectType -> "TIPO INCORRETO"
    SaveVaultIntegrityCode.SizeMismatch -> "TAMANHO"
    SaveVaultIntegrityCode.HashMismatch -> "SHA-256"
    SaveVaultIntegrityCode.ExtraFile -> "EXTRA"
}

private fun integrityDate(epochMs: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(epochMs))

private fun integrityBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f GiB", value / 1024.0 / 1024.0 / 1024.0)
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MiB", value / 1024.0 / 1024.0)
        value >= 1024.0 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}
