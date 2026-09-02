package com.jhony4lves.echo360.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jhony4lves.echo360.data.integrity.EchoIntegrityRepository
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.domain.integrity.EchoIntegrityReport
import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GameIntegritySummary(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val libraryRepository = remember(context) { AuroraLibraryRepository(context) }
    val integrityRepository = remember(context) { EchoIntegrityRepository(context) }
    val scope = rememberCoroutineScope()

    var snapshot by remember(game.stableKey) { mutableStateOf<LibrarySnapshot?>(null) }
    var report by remember(game.stableKey) { mutableStateOf<EchoIntegrityReport?>(null) }
    var loadingLocal by remember(game.stableKey) { mutableStateOf(true) }
    var verifyingRemote by remember(game.stableKey) { mutableStateOf(false) }
    var loadMessage by remember(game.stableKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(game.stableKey) {
        loadingLocal = true
        loadMessage = null
        try {
            val cached = withContext(Dispatchers.IO) { libraryRepository.loadCached() }
            snapshot = cached
            if (cached == null) {
                report = null
                loadMessage = "Sincronize a biblioteca para gerar o diagnóstico local."
            } else {
                report = withContext(Dispatchers.Default) {
                    integrityRepository.analyze(cached, game)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report = null
            loadMessage = error.message ?: "Não foi possível analisar o snapshot local."
        } finally {
            loadingLocal = false
        }
    }

    fun verifyRemote() {
        val currentSnapshot = snapshot ?: return
        val baseline = report ?: integrityRepository.analyze(currentSnapshot, game)
        verifyingRemote = true
        scope.launch {
            try {
                report = integrityRepository.verifyGame(
                    snapshot = currentSnapshot,
                    game = game,
                    baseline = baseline,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadMessage = error.message ?: "Verificação remota interrompida."
            } finally {
                verifyingRemote = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                EchoColors.SurfaceBright.copy(alpha = 0.48f),
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val current = report
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                EchoEyebrow("ECHO INTEGRITY")
                Text(
                    "DIAGNÓSTICO DO JOGO",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }
            if (loadingLocal) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = EchoColors.NeonGreen,
                )
            } else if (current != null) {
                EchoStatusPill(
                    text = integrityStatusLabel(current),
                    active = current.healthy && current.remoteVerified,
                )
            }
        }

        if (current != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntegrityMetric(
                    label = "ERROS",
                    value = current.errorCount.toString(),
                    emphasized = current.errorCount > 0,
                    modifier = Modifier.weight(1f),
                )
                IntegrityMetric(
                    label = "AVISOS",
                    value = current.warningCount.toString(),
                    emphasized = current.warningCount > 0,
                    modifier = Modifier.weight(1f),
                )
                IntegrityMetric(
                    label = "REMOTO",
                    value = if (current.remoteVerified) "OK" else "—",
                    emphasized = false,
                    modifier = Modifier.weight(1f),
                )
            }

            current.findings
                .filter { it.severity != IntegritySeverity.Info }
                .take(MAX_VISIBLE_FINDINGS)
                .forEach { finding -> IntegrityFindingRow(finding) }

            val hiddenCount = current.findings.count { it.severity != IntegritySeverity.Info } - MAX_VISIBLE_FINDINGS
            if (hiddenCount > 0) {
                Text(
                    "+$hiddenCount diagnóstico${if (hiddenCount == 1) "" else "s"} adicional${if (hiddenCount == 1) "" else "is"}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }

            if (current.healthy && current.findings.none { it.severity != IntegritySeverity.Info }) {
                Text(
                    if (current.remoteVerified) {
                        "Nenhuma anomalia objetiva encontrada no snapshot e o executável foi confirmado no Xbox."
                    } else {
                        "Nenhuma anomalia objetiva encontrada no snapshot local. A existência do executável ainda não foi confirmada no Xbox."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            }

            current.remoteMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current.remoteVerified) EchoColors.NeonGreen else EchoColors.TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(
                onClick = ::verifyRemote,
                enabled = !verifyingRemote && snapshot != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.SurfaceHigh,
                    contentColor = EchoColors.NeonGreen,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (verifyingRemote) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = EchoColors.NeonGreen,
                    )
                }
                if (verifyingRemote) Spacer(Modifier.size(8.dp))
                Text(if (verifyingRemote) "VERIFICANDO" else "VERIFICAR NO XBOX")
            }
        }

        loadMessage?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }

        Text(
            "Diagnóstico somente leitura. O Echo não corrige, move ou apaga arquivos nesta etapa.",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.TextMuted,
        )
    }
}

@Composable
private fun IntegrityMetric(
    label: String,
    value: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (emphasized) EchoColors.Text else EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun IntegrityFindingRow(finding: IntegrityFinding) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoColors.SurfaceHigh.copy(alpha = 0.66f), RoundedCornerShape(10.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${severityLabel(finding.severity)} // ${finding.title}",
            style = MaterialTheme.typography.labelLarge,
            color = if (finding.severity == IntegritySeverity.Error) EchoColors.Text else EchoColors.NeonGreen,
            fontWeight = FontWeight.Bold,
        )
        Text(
            finding.evidence,
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "AÇÃO // ${finding.suggestedAction}",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.TextMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun integrityStatusLabel(report: EchoIntegrityReport): String = when {
    report.errorCount > 0 -> "ERRO ${report.errorCount}"
    report.warningCount > 0 -> "AVISO ${report.warningCount}"
    report.remoteVerified -> "VERIFICADO"
    else -> "LOCAL OK"
}

private fun severityLabel(severity: IntegritySeverity): String = when (severity) {
    IntegritySeverity.Error -> "ERRO"
    IntegritySeverity.Warning -> "AVISO"
    IntegritySeverity.Info -> "INFO"
}

private const val MAX_VISIBLE_FINDINGS = 3
