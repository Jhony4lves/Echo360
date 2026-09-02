package com.jhony4lves.echo360.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.PlaySessionStore
import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.stats.EchoGameStats
import com.jhony4lves.echo360.domain.stats.EchoStatsAnalyzer
import com.jhony4lves.echo360.domain.stats.EchoStatsSnapshot
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.library.formatObservedDuration
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EchoStatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { PlaySessionStore(context) }
    val analyzer = remember { EchoStatsAnalyzer() }
    var stats by remember { mutableStateOf(analyzer.analyze(store.load())) }

    LaunchedEffect(store, analyzer) {
        while (true) {
            stats = analyzer.analyze(store.load())
            delay(REFRESH_MS)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EchoEyebrow("ECHO OS // STATS")
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Seu tempo no Xbox",
                            style = MaterialTheme.typography.headlineLarge,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    EchoStatusPill(
                        text = if (stats.activeSession != null) "OBSERVANDO" else "LOCAL",
                        active = stats.activeSession != null,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Métricas conservadoras das sessões que o Echo360 realmente observou enquanto o app estava ativo.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.TextSecondary,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                StatMetric(
                    modifier = Modifier.weight(1f),
                    eyebrow = "TEMPO OBSERVADO",
                    value = formatObservedDuration(stats.totalObservedMs),
                    supporting = "histórico retido",
                )
                StatMetric(
                    modifier = Modifier.weight(1f),
                    eyebrow = "SESSÕES",
                    value = stats.sessionCount.toString(),
                    supporting = "${stats.distinctGames} jogo(s)",
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                StatMetric(
                    modifier = Modifier.weight(1f),
                    eyebrow = "MÉDIA",
                    value = formatObservedDuration(stats.averageSessionMs),
                    supporting = "por sessão",
                )
                StatMetric(
                    modifier = Modifier.weight(1f),
                    eyebrow = "MAIOR SESSÃO",
                    value = stats.longestSession?.let { formatObservedDuration(it.durationMs) } ?: "—",
                    supporting = stats.longestSession?.title ?: "sem dados",
                )
            }
        }

        stats.mostPlayedGame?.let { top ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        EchoEyebrow("MAIS JOGADO // RETIDO")
                        Text(
                            top.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatObservedDuration(top.totalObservedMs)} // ${top.sessionCount} sessão(ões)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                        )
                    }
                }
            }
        }

        if (stats.games.isNotEmpty()) {
            item { EchoEyebrow("TOP JOGOS") }
            stats.games.take(TOP_GAMES).forEachIndexed { index, game ->
                item(key = "top:${game.titleId}") {
                    GameStatsRow(index + 1, game)
                }
            }
        }

        if (stats.recentSessions.isNotEmpty()) {
            item { EchoEyebrow("SESSÕES RECENTES") }
            stats.recentSessions.forEach { session ->
                item(key = "session:${session.id}") {
                    SessionRow(session)
                }
            }
        }

        if (!stats.hasData) {
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Ainda não há sessões observadas. O monitor só contabiliza intervalos confirmados enquanto o Echo360 está em primeiro plano.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Escopo: o ledger mantém até ${PlaySessionStore.MAX_SESSIONS} sessões concluídas e uma sessão ativa. Estes números são observações conservadoras do Echo360, não estatísticas oficiais do Xbox Live e não incluem períodos que o app não confirmou.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun StatMetric(
    modifier: Modifier,
    eyebrow: String,
    value: String,
    supporting: String,
) {
    EchoPanel(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EchoEyebrow(eyebrow)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
            )
            Text(
                supporting,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GameStatsRow(rank: Int, game: EchoGameStats) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                color = EchoColors.NeonGreen,
                fontWeight = FontWeight.Black,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${formatObservedDuration(game.totalObservedMs)} // ${game.sessionCount} sessão(ões) // média ${formatObservedDuration(game.averageSessionMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: PlaySession) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = session.active) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (session.active) {
                    EchoStatusPill("AGORA", true)
                }
            }
            Text(
                "${formatObservedDuration(session.durationMs)} // ${formatTimestamp(session.startedAtEpochMs)} // ${session.observationCount} amostra(s)",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextSecondary,
            )
        }
    }
}

private fun formatTimestamp(epochMs: Long): String = runCatching {
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private const val REFRESH_MS = 15_000L
private const val TOP_GAMES = 5
