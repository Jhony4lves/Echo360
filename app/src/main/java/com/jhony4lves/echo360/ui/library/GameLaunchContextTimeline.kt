package com.jhony4lves.echo360.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.LaunchAttemptStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LaunchAttempt
import com.jhony4lves.echo360.domain.library.LaunchAttemptStatus
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun GameLaunchContextTimeline(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val attempts by produceState(
        initialValue = emptyList<LaunchAttempt>(),
        game.titleId,
    ) {
        val store = LaunchAttemptStore(context)
        while (isActive) {
            value = withContext(Dispatchers.IO) { store.recentFor(game, limit = 5) }
            delay(LOCAL_REFRESH_MS)
        }
    }

    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EchoEyebrow("LAUNCH CONTEXT // LOCAL EVIDENCE")
                    Text(
                        "Últimas tentativas",
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Bold,
                    )
                }
                EchoStatusPill(
                    text = attempts.firstOrNull()?.let(::statusLabel) ?: "EMPTY",
                    active = attempts.firstOrNull()?.status == LaunchAttemptStatus.Confirmed,
                )
            }

            Text(
                "O Echo registra pedido, aceite explícito da NOVA e confirmação posterior pelo Title ID observado. Uma tentativa não confirmada não é classificada como crash.",
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )

            if (attempts.isEmpty()) {
                Text(
                    "Nenhuma tentativa iniciada pelo Echo foi registrada para este jogo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextMuted,
                )
            } else {
                attempts.forEach { attempt -> LaunchAttemptRow(attempt) }
            }
        }
    }
}

@Composable
private fun LaunchAttemptRow(attempt: LaunchAttempt) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                statusLabel(attempt),
                style = MaterialTheme.typography.labelLarge,
                color = if (attempt.status == LaunchAttemptStatus.Confirmed) EchoColors.NeonGreen else EchoColors.Text,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatTime(attempt.requestedAtEpochMs),
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
        Text(
            statusDetail(attempt),
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextSecondary,
        )
        attempt.rejectionReason?.takeIf { attempt.status == LaunchAttemptStatus.Rejected }?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

private fun statusLabel(attempt: LaunchAttempt): String = when (attempt.status) {
    LaunchAttemptStatus.Requested -> "INCONCLUSIVO"
    LaunchAttemptStatus.Accepted -> "ACEITO"
    LaunchAttemptStatus.Confirmed -> "CONFIRMADO"
    LaunchAttemptStatus.Rejected -> "REJEITADO"
}

private fun statusDetail(attempt: LaunchAttempt): String = when (attempt.status) {
    LaunchAttemptStatus.Requested ->
        "O pedido foi iniciado, mas não há evidência suficiente de aceite ou recusa."

    LaunchAttemptStatus.Accepted ->
        "A NOVA aceitou o launch; o runtime ainda não confirmou este Title ID dentro da janela observada."

    LaunchAttemptStatus.Confirmed ->
        "Launch aceito e Title ID ${attempt.titleIdHex} observado posteriormente pelo Echo."

    LaunchAttemptStatus.Rejected ->
        "A fonte retornou uma recusa explícita ao pedido de launch."
}

private fun formatTime(epochMs: Long): String = DateFormat
    .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(epochMs))

private const val LOCAL_REFRESH_MS = 15_000L
