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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.PlaySessionStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.library.PlaytimeSummary
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun GamePlaytimeTimeline(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { PlaySessionStore(context) }
    val summary by produceState<PlaytimeSummary?>(
        initialValue = null,
        key1 = game.titleIdHex,
    ) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                store.summaryFor(game, recentLimit = 5)
            }
            delay(LOCAL_REFRESH_MS)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                EchoEyebrow("ECHO PLAYTIME")
                Text(
                    "TEMPO OBSERVADO PELO ECHO",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }
            if (summary == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = EchoColors.NeonGreen,
                )
            }
        }

        val current = summary
        if (current != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PlaytimeMetric(
                    label = "TOTAL",
                    value = formatObservedDuration(current.totalObservedMs),
                    modifier = Modifier.weight(1f),
                )
                PlaytimeMetric(
                    label = "SESSÕES",
                    value = current.sessionCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (current.recentSessions.isEmpty()) {
                Text(
                    "Ainda não há uma sessão observada deste jogo. Com o Echo aberto, a NOVA passa a alimentar esta linha do tempo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            } else {
                Spacer(Modifier.height(1.dp))
                current.recentSessions.forEachIndexed { index, session ->
                    PlaySessionRow(session)
                    if (index != current.recentSessions.lastIndex) {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }

            Text(
                "O Echo conta apenas intervalos entre observações confirmadas. Tempo com o app fora do primeiro plano não entra silenciosamente no total.",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

@Composable
private fun PlaytimeMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.TextMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun PlaySessionRow(session: PlaySession) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (session.active) "EM OBSERVAÇÃO" else formatObservedTimestamp(session.startedAtEpochMs),
                style = MaterialTheme.typography.labelLarge,
                color = if (session.active) EchoColors.NeonGreen else EchoColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${session.observationCount} amostra${if (session.observationCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
        Text(
            formatObservedDuration(session.durationMs),
            style = MaterialTheme.typography.titleMedium,
            color = EchoColors.TextSecondary,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatObservedTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(epochMs.coerceAtLeast(0L)))

private const val LOCAL_REFRESH_MS = 30_000L
