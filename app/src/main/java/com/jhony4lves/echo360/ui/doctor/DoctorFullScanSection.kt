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
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.doctor.DoctorFullScanRepository
import com.jhony4lves.echo360.domain.doctor.DoctorFullScanReport
import com.jhony4lves.echo360.domain.doctor.DoctorScanAvailability
import com.jhony4lves.echo360.domain.doctor.DoctorScanComponent
import com.jhony4lves.echo360.domain.doctor.DoctorScanComponentSummary
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DoctorFullScanSection() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { DoctorFullScanRepository(context) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<DoctorFullScanReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun scan() {
        loading = true
        message = null
        scope.launch {
            try {
                report = repository.inspect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "O scan read-only não pôde ser concluído."
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EchoPanel(
            modifier = Modifier.fillMaxWidth(),
            highlighted = report?.let { it.healthy && it.complete } == true,
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.HealthAndSafety,
                            contentDescription = null,
                            tint = EchoColors.NeonGreen,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            EchoEyebrow("FULL SCAN // READ ONLY")
                            Text(
                                "Diagnóstico geral",
                                style = MaterialTheme.typography.titleMedium,
                                color = EchoColors.Text,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    EchoStatusPill(
                        text = fullScanStatus(report, loading),
                        active = report?.let { it.healthy && it.complete } == true,
                    )
                }

                Text(
                    "Executa DashLaunch, telemetria e armazenamento como leituras independentes. Uma fonte indisponível não vira falha de saúde e não cancela as demais.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )

                Button(
                    onClick = ::scan,
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
                    Text(if (loading) "ESCANEANDO" else "ESCANEAR TUDO")
                }
            }
        }

        report?.let { current ->
            EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = current.healthy && current.complete) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EchoEyebrow("SCAN SUMMARY // ${current.durationMs} ms")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScanMetric("ERROS", current.errors.toString(), Modifier.weight(1f))
                        ScanMetric("AVISOS", current.warnings.toString(), Modifier.weight(1f))
                        ScanMetric("FONTES OFF", current.unavailableCount.toString(), Modifier.weight(1f))
                    }
                    current.components.forEach { component ->
                        ComponentSummaryRow(component)
                    }
                    if (!current.complete && current.healthy) {
                        Text(
                            "Sem problema de saúde confirmado, mas o scan não teve evidência completa de todas as fontes.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
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
private fun ComponentSummaryRow(summary: DoctorScanComponentSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.58f), RoundedCornerShape(9.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                componentLabel(summary.component),
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.Text,
                fontWeight = FontWeight.Bold,
            )
            Text(
                summary.detail,
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )
            if (summary.errors > 0 || summary.warnings > 0 || summary.info > 0) {
                Text(
                    "${summary.errors} erro(s) • ${summary.warnings} aviso(s) • ${summary.info} info",
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        EchoStatusPill(
            text = componentStatus(summary),
            active = summary.availability == DoctorScanAvailability.Available && !summary.hasHealthIssue,
        )
    }
}

@Composable
private fun ScanMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.58f), RoundedCornerShape(9.dp))
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun fullScanStatus(report: DoctorFullScanReport?, loading: Boolean): String = when {
    loading -> "SCANNING"
    report == null -> "READY"
    report.errors > 0 || report.warnings > 0 -> "CHECK"
    !report.complete -> "PARTIAL"
    else -> "CLEAR"
}

private fun componentStatus(summary: DoctorScanComponentSummary): String = when {
    summary.availability == DoctorScanAvailability.Unavailable -> "OFFLINE"
    summary.availability == DoctorScanAvailability.Partial -> "PARTIAL"
    summary.errors > 0 || summary.warnings > 0 -> "CHECK"
    else -> "OK"
}

private fun componentLabel(component: DoctorScanComponent): String = when (component) {
    DoctorScanComponent.DashLaunch -> "DASHLAUNCH"
    DoctorScanComponent.Telemetry -> "TELEMETRY"
    DoctorScanComponent.Storage -> "STORAGE"
}
