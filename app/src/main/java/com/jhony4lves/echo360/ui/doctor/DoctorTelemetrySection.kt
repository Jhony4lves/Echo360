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
import androidx.compose.material.icons.outlined.Memory
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
import com.jhony4lves.echo360.data.doctor.DoctorTelemetryRepository
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryReport
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
internal fun DoctorTelemetrySection() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { DoctorTelemetryRepository(context) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<DoctorTelemetryReport?>(null) }
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
                message = error.message ?: "Não foi possível ler a telemetria pela NOVA."
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EchoPanel(
            modifier = Modifier.fillMaxWidth(),
            highlighted = report?.let { current ->
                current.healthy && current.snapshot.unavailable.isEmpty() &&
                    (current.snapshot.memory != null || current.snapshot.temperature != null)
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
                        EchoEyebrow("TELEMETRY // READ INFO")
                        Text(
                            "RAM e sensores térmicos",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    EchoStatusPill(
                        text = telemetryStatus(report, loading),
                        active = report?.let { current ->
                            current.healthy && current.snapshot.unavailable.isEmpty() &&
                                (current.snapshot.memory != null || current.snapshot.temperature != null)
                        } == true,
                    )
                }

                Text(
                    "Fonte atual: NOVA compatibility. Os valores são exibidos crus/normalizados; o Echo ainda não classifica uma temperatura normal como segura ou perigosa sem evidência específica do hardware.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
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
                    Text(if (loading) "LENDO SENSORES" else "LER TELEMETRIA")
                }
            }
        }

        report?.let { current ->
            current.snapshot.memory?.let { memory ->
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Memory,
                                contentDescription = null,
                                tint = EchoColors.NeonGreen,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            EchoEyebrow("MEMORY // ${originLabel(current.snapshot.origin)}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TelemetryMetric("USADA", formatBytes(memory.usedBytes), Modifier.weight(1f))
                            TelemetryMetric("LIVRE", formatBytes(memory.freeBytes), Modifier.weight(1f))
                            TelemetryMetric("TOTAL", formatBytes(memory.totalBytes), Modifier.weight(1f))
                        }
                        memory.usedFraction
                            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                            ?.let { fraction ->
                                Text(
                                    "Uso reportado: ${formatPercent(fraction)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EchoColors.TextMuted,
                                )
                            }
                    }
                }
            }

            current.snapshot.temperature?.let { temperature ->
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        EchoEyebrow("THERMALS // NORMALIZADO °C")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TelemetryMetric("CPU", formatCelsius(temperature.cpuCelsius), Modifier.weight(1f))
                            TelemetryMetric("GPU", formatCelsius(temperature.gpuCelsius), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TelemetryMetric("RAM", formatCelsius(temperature.memoryCelsius), Modifier.weight(1f))
                            TelemetryMetric("CASE", formatCelsius(temperature.caseCelsius), Modifier.weight(1f))
                        }
                        Text(
                            "Unidade reportada pela fonte: ${temperature.reportedUnit.name}.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }

            current.snapshot.unavailable.forEach { unavailable ->
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EchoEyebrow("SOURCE UNAVAILABLE // ${componentLabel(unavailable.component)}")
                        Text(
                            unavailable.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoColors.TextSecondary,
                        )
                        Text(
                            "A indisponibilidade da fonte não é classificada como falha de saúde do console.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }

            current.findings.forEach { finding ->
                TelemetryFindingCard(finding)
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
private fun TelemetryMetric(label: String, value: String, modifier: Modifier = Modifier) {
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
private fun TelemetryFindingCard(finding: IntegrityFinding) {
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

private fun telemetryStatus(report: DoctorTelemetryReport?, loading: Boolean): String = when {
    loading -> "READING"
    report == null -> "NOVA COMPAT"
    report.snapshot.memory == null && report.snapshot.temperature == null -> "UNAVAILABLE"
    report.snapshot.unavailable.isNotEmpty() -> "PARTIAL"
    report.errors > 0 || report.warnings > 0 -> "CHECK"
    else -> "RAW"
}

private fun originLabel(origin: DoctorTelemetryOrigin): String = when (origin) {
    DoctorTelemetryOrigin.NovaCompatibility -> "NOVA COMPAT"
    DoctorTelemetryOrigin.EchoCore -> "ECHOCORE"
}

private fun componentLabel(component: DoctorTelemetryComponent): String = when (component) {
    DoctorTelemetryComponent.Memory -> "MEMORY"
    DoctorTelemetryComponent.Temperature -> "TEMPERATURE"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB", mib)
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

private fun formatCelsius(value: Double): String =
    if (value.isFinite()) String.format(Locale.US, "%.1f °C", value) else "—"
