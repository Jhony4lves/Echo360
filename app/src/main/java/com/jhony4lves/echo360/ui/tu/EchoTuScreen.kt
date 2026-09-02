package com.jhony4lves.echo360.ui.tu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jhony4lves.echo360.data.mods.EchoMutationRollbackRepository
import com.jhony4lves.echo360.data.mods.EchoRollbackAssessment
import com.jhony4lves.echo360.data.tu.EchoTuRepository
import com.jhony4lves.echo360.data.tu.EchoTuScanner
import com.jhony4lves.echo360.domain.tu.EchoTuTitleId
import com.jhony4lves.echo360.domain.tu.TitleUpdateCandidate
import com.jhony4lves.echo360.domain.tu.TitleUpdateInventory
import com.jhony4lves.echo360.domain.tu.TitleUpdateLocation
import com.jhony4lves.echo360.domain.tu.TitleUpdateSourceResult
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun EchoTuScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { EchoTuRepository(context) }
    val rollbackRepository = remember(context) { EchoMutationRollbackRepository(context) }
    val scope = rememberCoroutineScope()

    var titleId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var runtimeBusy by remember { mutableStateOf(false) }
    var rollbackBusy by remember { mutableStateOf(false) }
    var inventory by remember { mutableStateOf<TitleUpdateInventory?>(null) }
    var rollback by remember { mutableStateOf<EchoRollbackAssessment?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun normalizedTitleIdOrNull(): String? = runCatching { EchoTuTitleId.normalize(titleId) }.getOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EchoEyebrow("ECHO TU // READ-ONLY INVENTORY")
            Text(
                "Title Updates",
                style = MaterialTheme.typography.headlineMedium,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Mostra o que o Xbox realmente expõe nas localizações clássicas de TU. Não chama nenhum pacote de 'mais recente' sem catálogo confiável.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    EchoEyebrow("ALVO")
                    OutlinedTextField(
                        value = titleId,
                        onValueChange = {
                            titleId = it.take(10)
                            inventory = null
                            rollback = null
                            message = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title ID (8 hex)") },
                        placeholder = { Text("465307E4") },
                        singleLine = true,
                        enabled = !busy && !runtimeBusy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                runtimeBusy = true
                                message = null
                                scope.launch {
                                    runCatching { repository.currentRuntime() }
                                        .onSuccess { current ->
                                            if (current == null) {
                                                message = "NOVA não reportou um título atual utilizável."
                                            } else {
                                                titleId = current.titleIdHex
                                                inventory = null
                                                rollback = null
                                                message = "Title ID atual: ${current.titleIdHex} • TU reportada: ${current.reportedTuVersion}."
                                            }
                                        }
                                        .onFailure { message = it.message ?: "Não foi possível ler o título atual." }
                                    runtimeBusy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy && !runtimeBusy,
                        ) {
                            if (runtimeBusy) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            } else {
                                Text("USAR TÍTULO ATUAL")
                            }
                        }
                        Button(
                            onClick = {
                                val normalized = normalizedTitleIdOrNull()
                                if (normalized == null) {
                                    message = "Informe um Title ID hexadecimal com 8 caracteres."
                                } else {
                                    titleId = normalized
                                    busy = true
                                    inventory = null
                                    rollback = null
                                    message = null
                                    scope.launch {
                                        runCatching { repository.inspect(normalized) }
                                            .onSuccess { inventory = it }
                                            .onFailure { message = it.message ?: "Não foi possível inspecionar as TUs." }
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy && !runtimeBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.NeonGreen,
                                contentColor = EchoColors.Void,
                            ),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = EchoColors.Void)
                            } else {
                                Text("INSPECIONAR", fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Text(
                        "v1 lê somente Hdd1: pasta 000B0000 do Title ID + Cache legado. Nenhuma TU é instalada, movida, renomeada ou apagada.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }

        message?.let { text ->
            item {
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

        inventory?.let { result ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EchoEyebrow("OBSERVAÇÃO")
                            EchoStatusPill(
                                text = when (result.actualRoute.name) {
                                    "Fast" -> "AURORA FTP"
                                    "Background" -> "FTPDLL"
                                    else -> result.actualRoute.name.uppercase()
                                },
                                active = true,
                            )
                        }
                        Text(
                            "Title ID ${result.requestedTitleIdHex}",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        val runtime = result.runtime
                        when {
                            runtime == null -> Text(
                                "Runtime NOVA indisponível; isso não prova ausência de TU.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.TextMuted,
                            )
                            result.runtimeMatchesRequestedTitle -> Text(
                                "Jogo em execução: ${runtime.titleIdHex} • Media ID ${runtime.mediaIdHex ?: "desconhecida"} • TU reportada ${runtime.reportedTuVersion}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.NeonGreen,
                            )
                            else -> Text(
                                "O título em execução é ${runtime.titleIdHex}, não o alvo consultado. A TU runtime ${runtime.reportedTuVersion} pertence ao título em execução.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.Warning,
                            )
                        }
                    }
                }
            }

            item { TuSourceHeader("PASTA DO TITLE ID", result.contentFolder) }
            if (result.contentFolder.candidates.isEmpty()) {
                item { TuEmptySource(result.contentFolder) }
            } else {
                items(result.contentFolder.candidates, key = { it.remotePath }) { candidate ->
                    TuCandidateCard(candidate, assigned = true)
                }
            }

            item { TuSourceHeader("CACHE LEGADO", result.legacyCache) }
            if (result.legacyCache.candidates.isEmpty()) {
                item { TuEmptySource(result.legacyCache) }
            } else {
                item {
                    Text(
                        "Arquivos TU_ do Cache são candidatos globais. Sem ler o header STFS, o Echo360 não atribui esses arquivos ao Title ID consultado.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.Warning,
                    )
                }
                items(result.legacyCache.candidates, key = { it.remotePath }) { candidate ->
                    TuCandidateCard(candidate, assigned = false)
                }
            }

            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EchoEyebrow("ROLLBACK GATE // MODS & TU")
                        Text(
                            "Antes de qualquer futura escrita, o Echo360 exige um Save Vault íntegro, completo, sem extras e cobrindo exatamente o alvo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                        )
                        OutlinedButton(
                            onClick = {
                                rollbackBusy = true
                                rollback = null
                                scope.launch {
                                    rollback = rollbackRepository.assess(
                                        EchoTuScanner.contentDirectory(result.requestedTitleIdHex),
                                    )
                                    rollbackBusy = false
                                }
                            },
                            enabled = !rollbackBusy,
                        ) {
                            if (rollbackBusy) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            } else {
                                Text("CHECAR ROLLBACK LOCAL")
                            }
                        }
                        rollback?.let { assessment ->
                            EchoStatusPill(
                                text = if (assessment.decision.approved) "ROLLBACK APROVADO" else "ESCRITA BLOQUEADA",
                                active = assessment.decision.approved,
                            )
                            assessment.snapshotId?.let {
                                Text(
                                    "Vault: $it",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EchoColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                assessment.decision.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (assessment.decision.approved) EchoColors.NeonGreen else EchoColors.Warning,
                            )
                        }
                        Text(
                            "Mesmo com rollback aprovado, o v1 continua read-only: não existe executor de instalação/modificação automática nesta versão.",
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EchoEyebrow("REGRA DE EVIDÊNCIA")
                    Text(
                        "Arquivo encontrado ≠ TU mais recente. TU reportada pelo runtime ≠ catálogo online. O Echo360 só mostra cada evidência pelo que ela realmente prova.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TuSourceHeader(label: String, source: TitleUpdateSourceResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EchoEyebrow(label)
        EchoStatusPill(
            text = when {
                !source.available -> "INDISPONÍVEL"
                source.limitReached -> "LIMITE"
                else -> "${source.candidates.size} CANDIDATO(S)"
            },
            active = source.available && !source.limitReached,
        )
    }
}

@Composable
private fun TuEmptySource(source: TitleUpdateSourceResult) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(source.canonicalDirectory, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
            Text(
                source.detail ?: if (source.available) "Nenhum candidato encontrado." else "Fonte não pôde ser lida.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (source.available) EchoColors.TextSecondary else EchoColors.Warning,
            )
        }
    }
}

@Composable
private fun TuCandidateCard(candidate: TitleUpdateCandidate, assigned: Boolean) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
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
                    candidate.fileName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EchoStatusPill(
                    text = if (assigned) candidate.titleIdHex ?: "TITLE" else "NÃO ATRIBUÍDA",
                    active = assigned,
                )
            }
            Text(
                candidate.remotePath,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${tuLocationLabel(candidate.location)} • ${candidate.sizeBytes?.let(::formatTuBytes) ?: "tamanho não confirmado"}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextSecondary,
            )
        }
    }
}

private fun tuLocationLabel(location: TitleUpdateLocation): String = when (location) {
    TitleUpdateLocation.ContentFolder -> "000B0000"
    TitleUpdateLocation.LegacyCache -> "CACHE"
}

private fun formatTuBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MiB", value / 1024.0 / 1024.0)
        value >= 1024.0 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}
