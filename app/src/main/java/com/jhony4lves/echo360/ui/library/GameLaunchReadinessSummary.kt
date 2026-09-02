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
import androidx.compose.material.icons.outlined.HealthAndSafety
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
import com.jhony4lves.echo360.data.integrity.EchoIntegrityRepository
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.domain.doctor.LaunchReadinessAnalyzer
import com.jhony4lves.echo360.domain.doctor.LaunchReadinessReport
import com.jhony4lves.echo360.domain.doctor.LaunchReadinessStatus
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GameLaunchReadinessSummary(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val libraryRepository = remember(context) { AuroraLibraryRepository(context) }
    val integrityRepository = remember(context) { EchoIntegrityRepository(context) }
    val analyzer = remember { LaunchReadinessAnalyzer() }
    val scope = rememberCoroutineScope()

    var report by remember(game.stableKey) { mutableStateOf<LaunchReadinessReport?>(null) }
    var loading by remember(game.stableKey) { mutableStateOf(false) }
    var message by remember(game.stableKey) { mutableStateOf<String?>(null) }

    fun inspect() {
        loading = true
        message = null
        scope.launch {
            try {
                val snapshot = libraryRepository.loadCached()
                    ?: error("Sincronize a biblioteca antes de avaliar o launch.")
                val baseline = integrityRepository.analyze(snapshot, game)
                val verified = integrityRepository.verifyGame(snapshot, game, baseline)
                report = analyzer.analyze(verified)
                message = verified.remoteMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "Não foi possível concluir a avaliação read-only."
            } finally {
                loading = false
            }
        }
    }

    val current = report
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = current?.status == LaunchReadinessStatus.Ready,
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
                    EchoEyebrow("LAUNCH READINESS // READ ONLY")
                    Text(
                        "Recomendação baseada em evidência",
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Bold,
                    )
                }
                EchoStatusPill(
                    text = readinessLabel(current?.status, loading),
                    active = current?.status == LaunchReadinessStatus.Ready,
                )
            }

            Text(
                "READY exige executável remoto verificado e nenhuma anomalia objetiva nas checagens atuais. Isso não promete ausência de crashes futuros ou compatibilidade que o Echo ainda não mediu.",
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )

            current?.let { readiness ->
                Text(
                    readiness.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.Text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LaunchMetric("ERROS", readiness.integrity.errorCount.toString(), Modifier.weight(1f))
                    LaunchMetric("AVISOS", readiness.integrity.warningCount.toString(), Modifier.weight(1f))
                    LaunchMetric(
                        "REMOTE",
                        if (readiness.integrity.remoteVerified) "OK" else "NÃO",
                        Modifier.weight(1f),
                    )
                }
            }

            message?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoColors.TextMuted,
                )
            }

            Button(
                onClick = ::inspect,
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
                    Icon(Icons.Outlined.HealthAndSafety, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "AVALIANDO" else "AVALIAR LAUNCH")
            }
        }
    }
}

@Composable
private fun LaunchMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = EchoColors.Text,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun readinessLabel(status: LaunchReadinessStatus?, loading: Boolean): String = when {
    loading -> "CHECKING"
    status == null -> "NOT CHECKED"
    status == LaunchReadinessStatus.Ready -> "READY"
    status == LaunchReadinessStatus.NeedsVerification -> "VERIFY"
    status == LaunchReadinessStatus.Caution -> "CAUTION"
    else -> "BLOCKED"
}
