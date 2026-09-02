package com.jhony4lves.echo360.ui.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.jhony4lves.echo360.data.doctor.DoctorStorageRepository
import com.jhony4lves.echo360.domain.doctor.DoctorStorageEntry
import com.jhony4lves.echo360.domain.doctor.DoctorStorageMount
import com.jhony4lves.echo360.domain.doctor.DoctorStorageOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorStorageReport
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun DoctorStorageSection() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { DoctorStorageRepository(context) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<DoctorStorageReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            try {
                report = repository.inspect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "Não foi possível ler o armazenamento."
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EchoPanel(
            modifier = Modifier.fillMaxWidth(),
            highlighted = report?.let { current ->
                current.healthy && current.snapshot.unavailableDetail == null && current.snapshot.mounts.isNotEmpty()
            } == true,
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
                        EchoEyebrow("STORAGE // READ FILESYSTEM")
                        Text(
                            "Hdd1 e metadata",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    EchoStatusPill(
                        text = storageStatus(report, loading),
                        active = report?.let { current ->
                            current.healthy && current.snapshot.unavailableDetail == null && current.snapshot.mounts.isNotEmpty()
                        } == true,
                    )
                }

                Text(
                    "Compatibilidade atual: Aurora FTP primeiro, FTPdll como fallback. Uma pasta por leitura, sem recursão automática e no máximo 256 entradas retidas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )

                Text(
                    "O Echo não chama soma de arquivos de espaço usado/livre. Capacidade de volume só aparecerá quando uma fonte confiável realmente fornecer esse dado.",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )

                Button(
                    onClick = ::refresh,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = EchoColors.Void,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (loading) "LENDO HDD1" else "LER ARMAZENAMENTO")
                }
            }
        }

        report?.let { current ->
            current.snapshot.unavailableDetail?.let { detail ->
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EchoEyebrow("SOURCE UNAVAILABLE")
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = EchoColors.TextSecondary)
                        Text(
                            "Falha de transporte não é classificada como defeito do HDD.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }

            if (current.snapshot.unavailableDetail == null) {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        EchoEyebrow("ROOT // ${originLabel(current.snapshot.origin)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StorageMetric("ROOT ENTRIES", current.snapshot.rootEntryCount.toString(), Modifier.weight(1f))
                            StorageMetric(
                                "BOUND",
                                if (current.snapshot.rootLimitReached) "256+" else "OK",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            current.snapshot.mounts.forEach { mount ->
                StorageMountCard(mount, current.snapshot.origin)
            }

            current.findings.forEach { finding ->
                StorageFindingCard(finding)
            }
        }

        message?.let { text ->
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text,
                    modifier = Modifier.padding(13.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StorageMountCard(mount: DoctorStorageMount, origin: DoctorStorageOrigin) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = mount.listingUnavailableDetail == null) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = EchoColors.NeonGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        EchoEyebrow("MOUNT // ${originLabel(origin)}")
                        Text(
                            mount.canonicalRoot,
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                EchoStatusPill(
                    text = when {
                        mount.listingUnavailableDetail != null -> "PARTIAL"
                        mount.limitReached -> "BOUNDED"
                        else -> "VISIBLE"
                    },
                    active = mount.listingUnavailableDetail == null,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StorageMetric("TYPE", mount.objectType.name.uppercase(), Modifier.weight(1f))
                StorageMetric("ENTRIES", mount.entries.size.toString(), Modifier.weight(1f))
            }

            mount.listingUnavailableDetail?.let { detail ->
                Text(
                    "Listagem interna indisponível: $detail",
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoColors.TextSecondary,
                )
            }

            if (mount.entries.isNotEmpty()) {
                EchoEyebrow("AMOSTRA // ${minOf(mount.entries.size, SAMPLE_ENTRIES)} DE ${mount.entries.size}")
                mount.entries.take(SAMPLE_ENTRIES).forEach { entry ->
                    StorageEntryRow(entry)
                }
                if (mount.entries.size > SAMPLE_ENTRIES) {
                    Text(
                        "+ ${mount.entries.size - SAMPLE_ENTRIES} entradas retidas nesta leitura.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageEntryRow(entry: DoctorStorageEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.58f), RoundedCornerShape(9.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.objectType.name == "Directory") "DIR" else "FILE",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.objectType.name == "Directory") entry.canonicalPath else "${entry.canonicalPath} • ${formatBytes(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.58f), RoundedCornerShape(9.dp))
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StorageFindingCard(finding: IntegrityFinding) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = finding.severity == IntegritySeverity.Error,
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    finding.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                EchoStatusPill(
                    text = when (finding.severity) {
                        IntegritySeverity.Error -> "ERRO"
                        IntegritySeverity.Warning -> "AVISO"
                        IntegritySeverity.Info -> "INFO"
                    },
                    active = false,
                )
            }
            Text(finding.evidence, style = MaterialTheme.typography.bodySmall, color = EchoColors.TextSecondary)
            Text(
                "AÇÃO // ${finding.suggestedAction}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

private fun storageStatus(report: DoctorStorageReport?, loading: Boolean): String = when {
    loading -> "READING"
    report == null -> "READ ONLY"
    report.snapshot.unavailableDetail != null -> "UNAVAILABLE"
    report.snapshot.mounts.isEmpty() -> "NO HDD1"
    report.warnings > 0 || report.errors > 0 -> "CHECK"
    report.snapshot.mounts.any { it.listingUnavailableDetail != null } -> "PARTIAL"
    else -> "VISIBLE"
}

private fun originLabel(origin: DoctorStorageOrigin): String = when (origin) {
    DoctorStorageOrigin.AuroraFtpCompatibility -> "AURORA FTP"
    DoctorStorageOrigin.FtpDllCompatibility -> "FTPDLL"
    DoctorStorageOrigin.EchoCore -> "ECHOCORE"
    DoctorStorageOrigin.Unavailable -> "UNAVAILABLE"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes.toDouble() / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.1f MiB", mib)
    return String.format(Locale.US, "%.2f GiB", mib / 1024.0)
}

private const val SAMPLE_ENTRIES = 6
