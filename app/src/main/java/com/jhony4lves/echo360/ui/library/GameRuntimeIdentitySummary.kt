package com.jhony4lves.echo360.ui.library

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
import com.jhony4lves.echo360.data.library.CurrentTitleRepository
import com.jhony4lves.echo360.domain.doctor.RuntimeIdentityAnalyzer
import com.jhony4lves.echo360.domain.doctor.RuntimeIdentityReport
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.library.CurrentTitleOrigin
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GameRuntimeIdentitySummary(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { CurrentTitleRepository(context) }
    val analyzer = remember { RuntimeIdentityAnalyzer() }
    val scope = rememberCoroutineScope()

    var report by remember(game.stableKey) { mutableStateOf<RuntimeIdentityReport?>(null) }
    var loading by remember(game.stableKey) { mutableStateOf(false) }
    var message by remember(game.stableKey) { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            try {
                val observation = repository.observe()
                report = analyzer.analyze(game, observation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report = analyzer.analyze(game, null)
                message = error.message ?: "Não foi possível ler a identidade runtime do console."
            } finally {
                loading = false
            }
        }
    }

    val current = report
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = current?.let { it.sameTitle && it.mediaMatches != false } == true,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EchoEyebrow("RUNTIME IDENTITY // READ ONLY")
                    Text(
                        "TU e Media ID",
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Bold,
                    )
                }
                EchoStatusPill(
                    text = runtimeStatus(current, loading),
                    active = current?.let { it.sameTitle && it.mediaMatches != false } == true,
                )
            }

            Text(
                "Compara apenas fatos reportados pelo runtime. O número do TU é informativo: o Echo não chama uma versão de atual/desatualizada sem uma fonte confiável de compatibilidade.",
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )

            current?.observation?.let { observation ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeMetric("TITLE", hex(observation.titleId), Modifier.weight(1f))
                    RuntimeMetric(
                        "FONTE",
                        when (observation.origin) {
                            CurrentTitleOrigin.NovaCompatibility -> "NOVA"
                            CurrentTitleOrigin.EchoCore -> "ECHOCORE"
                        },
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeMetric("MID LIB", current.selectedMediaId?.let(::hex) ?: "—", Modifier.weight(1f))
                    RuntimeMetric("MID RUN", current.runtimeMediaId?.let(::hex) ?: "—", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeMetric("TU", current.titleUpdateVersion?.toString() ?: "—", Modifier.weight(1f))
                    RuntimeMetric(
                        "VERSÃO",
                        current.currentVersion ?: current.baseVersion ?: "—",
                        Modifier.weight(1f),
                    )
                }
            }

            current?.findings.orEmpty().forEach { finding ->
                RuntimeFinding(finding)
            }

            message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoColors.TextSecondary,
                )
            }

            Button(
                onClick = ::refresh,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.SurfaceBright,
                    contentColor = EchoColors.NeonGreen,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = EchoColors.NeonGreen,
                    )
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "LENDO RUNTIME" else "LER RUNTIME")
            }
        }
    }
}

@Composable
private fun RuntimeMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoColors.Text,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RuntimeFinding(finding: IntegrityFinding) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            when (finding.severity) {
                IntegritySeverity.Error -> "ERRO // ${finding.title}"
                IntegritySeverity.Warning -> "AVISO // ${finding.title}"
                IntegritySeverity.Info -> "INFO // ${finding.title}"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (finding.severity == IntegritySeverity.Warning) EchoColors.NeonGreen else EchoColors.Text,
            fontWeight = FontWeight.Bold,
        )
        Text(finding.evidence, style = MaterialTheme.typography.bodySmall, color = EchoColors.TextSecondary)
        Text(
            "AÇÃO // ${finding.suggestedAction}",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.TextMuted,
        )
    }
}

private fun runtimeStatus(report: RuntimeIdentityReport?, loading: Boolean): String = when {
    loading -> "READING"
    report == null -> "NOT READ"
    report.observation == null -> "UNAVAILABLE"
    !report.sameTitle -> "OTHER TITLE"
    report.mediaMatches == false -> "MID CHECK"
    report.mediaMatches == true -> "MATCH"
    else -> "TITLE MATCH"
}

private fun hex(value: Long): String = value.toUInt().toString(16).uppercase().padStart(8, '0')
