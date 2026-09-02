package com.jhony4lves.echo360.ui.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HealthAndSafety
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
import com.jhony4lves.echo360.data.doctor.DashLaunchDoctorRepository
import com.jhony4lves.echo360.domain.doctor.DashLaunchDoctorReport
import com.jhony4lves.echo360.domain.doctor.DashLaunchPlugin
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun EchoDoctorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { DashLaunchDoctorRepository(context) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<DashLaunchDoctorReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun inspectDashLaunch() {
        loading = true
        message = null
        scope.launch {
            try {
                report = repository.inspect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "Não foi possível ler o DashLaunch pela NOVA."
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EchoEyebrow("ECHO OS // DOCTOR")
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "EchoDoctor",
                            style = MaterialTheme.typography.headlineLarge,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    EchoStatusPill(
                        text = when {
                            report == null -> "READ ONLY"
                            report?.healthy == true -> "CLEAR"
                            else -> "CHECK"
                        },
                        active = report?.healthy == true,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Diagnóstico com evidência primeiro. Nada é corrigido, movido ou apagado sem uma etapa explícita de remediação.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.TextSecondary,
                )
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = report?.healthy == true) {
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
                            EchoEyebrow("DASHLAUNCH // NOVA")
                            Text(
                                "Configuração em memória",
                                style = MaterialTheme.typography.titleMedium,
                                color = EchoColors.Text,
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.HealthAndSafety,
                            contentDescription = null,
                            tint = EchoColors.NeonGreen,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Text(
                        "A leitura usa o endpoint autenticado /dashlaunch da NOVA. O Echo não precisa localizar nem editar launch.ini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )

                    Button(
                        onClick = ::inspectDashLaunch,
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
                        Text(if (loading) "LENDO DASHLAUNCH" else "LER DASHLAUNCH")
                    }
                }
            }
        }

        report?.let { current ->
            item { DashLaunchSummary(current) }

            val configured = current.snapshot.plugins.filter(DashLaunchPlugin::configured)
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        EchoEyebrow("PLUGIN INVENTORY // ${configured.size}")
                        if (configured.isEmpty()) {
                            Text(
                                "Nenhum slot de plugin configurado na resposta atual.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.TextSecondary,
                            )
                        } else {
                            configured.forEach { plugin ->
                                PluginRow(plugin)
                            }
                        }
                    }
                }
            }

            if (current.findings.isEmpty()) {
                item {
                    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                        Text(
                            "Nenhuma anomalia objetiva encontrada nas regras DashLaunch atuais.",
                            modifier = Modifier.padding(15.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                        )
                    }
                }
            } else {
                item { EchoEyebrow("FINDINGS // ${current.findings.size}") }
                current.findings.forEach { finding ->
                    item(key = "${finding.code}:${finding.evidence}") {
                        DoctorFindingCard(finding)
                    }
                }
            }
        }

        message?.let { text ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        item {
            Text(
                "Phase atual: diagnóstico read-only. Alterações de plugin/configuração só serão adicionadas com backup e rollback explícitos.",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

@Composable
private fun DashLaunchSummary(report: DashLaunchDoctorReport) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = report.healthy) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    EchoEyebrow("DASHLAUNCH STATUS")
                    Text(
                        "v${report.snapshot.version.display}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Black,
                    )
                }
                EchoStatusPill(
                    text = when {
                        report.errors > 0 -> "ERRO ${report.errors}"
                        report.warnings > 0 -> "AVISO ${report.warnings}"
                        else -> "OK"
                    },
                    active = report.healthy,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DoctorMetric("KERNEL", report.snapshot.version.kernel.toString(), Modifier.weight(1f))
                DoctorMetric("ERROS", report.errors.toString(), Modifier.weight(1f))
                DoctorMetric("AVISOS", report.warnings.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DoctorMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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
private fun PluginRow(plugin: DashLaunchPlugin) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.62f), RoundedCornerShape(9.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "P${plugin.slot}",
            style = MaterialTheme.typography.labelLarge,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
        Text(
            plugin.path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DoctorFindingCard(finding: IntegrityFinding) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = finding.severity == IntegritySeverity.Error) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
                EchoStatusPill(severityLabel(finding.severity), finding.severity == IntegritySeverity.Error)
            }
            Text(
                finding.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )
            Text(
                "AÇÃO // ${finding.suggestedAction}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

private fun severityLabel(severity: IntegritySeverity): String = when (severity) {
    IntegritySeverity.Error -> "ERRO"
    IntegritySeverity.Warning -> "AVISO"
    IntegritySeverity.Info -> "INFO"
}
