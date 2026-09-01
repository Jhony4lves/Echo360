package com.jhony4lves.echo360.ui.convert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.jhony4lves.echo360.data.convert.EchoConvertProgress
import com.jhony4lves.echo360.data.convert.EchoConvertRepository
import com.jhony4lves.echo360.data.convert.EchoConvertResult
import com.jhony4lves.echo360.data.convert.EchoConvertStage
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun EchoConvertScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        EchoConvertRepository(context.applicationContext)
    }
    val scope = rememberCoroutineScope()

    var route by remember { mutableStateOf(FtpRoute.Background) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(EchoConvertProgress(route = route)) }
    var result by remember { mutableStateOf<EchoConvertResult?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                EchoEyebrow("ECHOCONVERT // MVP")
                Text(
                    text = "Reparar discos de instalação",
                    style = MaterialTheme.typography.headlineMedium,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "O primeiro fluxo corrige o DVD2 do Dark Souls II sem criar uma ISO gigante no celular.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            }
        }

        item {
            EchoPanel(
                modifier = Modifier.fillMaxWidth(),
                highlighted = true,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            EchoEyebrow("REPAIR PROFILE // DS2-SOTFS")
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Dark Souls II: Scholar of the First Sin",
                                style = MaterialTheme.typography.titleLarge,
                                color = EchoColors.Text,
                                fontWeight = FontWeight.Black,
                            )
                        }
                        EchoStatusPill(text = "DVD2", active = true)
                    }

                    HorizontalDivider(color = EchoColors.Border)

                    PathLine(
                        label = "ORIGEM",
                        value = "FFED2000/00007000 → Expansion Installer",
                    )
                    PathLine(
                        label = "CONTEÚDO",
                        value = "Compatibility Pack 4 • D4B91B6…",
                    )
                    PathLine(
                        label = "DESTINO",
                        value = "465307E4/00000002/D4B91B6…",
                    )

                    Text(
                        text = "O GoD original no Xbox não será apagado. O EchoConvert baixa uma cópia temporária, verifica os hashes, lê o XDVDFS diretamente e extrai só o DLC necessário.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EchoEyebrow("ROTA FTP")
                    Text(
                        text = "FTPdll é o padrão para este reparo. Você pode trocar para Aurora se quiser testar a rota rápida.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RouteButton(
                            label = "FTPDLL",
                            selected = route == FtpRoute.Background,
                            enabled = !running,
                            onClick = {
                                route = FtpRoute.Background
                                progress = EchoConvertProgress(route = route)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        RouteButton(
                            label = "AUTO",
                            selected = route == FtpRoute.Auto,
                            enabled = !running,
                            onClick = {
                                route = FtpRoute.Auto
                                progress = EchoConvertProgress(route = route)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        RouteButton(
                            label = "AURORA",
                            selected = route == FtpRoute.Fast,
                            enabled = !running,
                            onClick = {
                                route = FtpRoute.Fast
                                progress = EchoConvertProgress(route = route)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (running || progress.stage != EchoConvertStage.Idle) {
            item { ConvertProgressPanel(progress) }
        }

        result?.let { finished ->
            item { ConvertResultPanel(finished) }
        }

        item {
            Button(
                onClick = {
                    running = true
                    result = null
                    progress = EchoConvertProgress(
                        stage = EchoConvertStage.Detecting,
                        message = "Iniciando EchoConvert…",
                        route = route,
                    )
                    scope.launch {
                        val finished = repository.repairDarkSouls2ScholarDisc2(
                            requestedRoute = route,
                            onProgress = { update ->
                                scope.launch { progress = update }
                            },
                        )
                        result = finished
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.NeonGreen,
                    contentColor = EchoColors.Void,
                    disabledContainerColor = EchoColors.SurfaceBright,
                    disabledContentColor = EchoColors.TextMuted,
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                        color = EchoColors.Void,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("REPARANDO…", fontWeight = FontWeight.Black)
                } else {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("CORRIGIR DVD2 AGORA", fontWeight = FontWeight.Black)
                }
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EchoEyebrow("COMO FUNCIONA")
                    StepLine("01", "Detecta o GoD do DVD2 pelo Media ID 0C94D453.")
                    StepLine("02", "Baixa as 8 partes e valida a hash tree do GoD.")
                    StepLine("03", "Lê XDVDFS direto do GoD, sem gerar ISO intermediária.")
                    StepLine("04", "Extrai somente o pacote D4B91B6… do installer.")
                    StepLine("05", "Envia para 465307E4/00000002 e confere o SIZE remoto.")
                }
            }
        }
    }
}

@Composable
private fun RouteButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) EchoColors.NeonGreen else EchoColors.TextSecondary,
        ),
        border = ButtonDefaults.outlinedButtonBorder(enabled).copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (selected) EchoColors.NeonGreen.copy(alpha = 0.55f) else EchoColors.Border,
            ),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ConvertProgressPanel(progress: EchoConvertProgress) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = progress.stage != EchoConvertStage.Failed,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoEyebrow("${stageLabel(progress.stage)} // ${routeLabel(progress.route)}")
                EchoStatusPill(
                    text = when (progress.stage) {
                        EchoConvertStage.Completed -> "PRONTO"
                        EchoConvertStage.Failed -> "ERRO"
                        else -> "ATIVO"
                    },
                    active = progress.stage !in setOf(EchoConvertStage.Completed, EchoConvertStage.Failed),
                )
            }

            Text(
                text = progress.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.stage == EchoConvertStage.Failed) EchoColors.Warning else EchoColors.TextSecondary,
            )

            progress.fraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = EchoColors.NeonGreen,
                    trackColor = EchoColors.SurfaceBright,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatBytes(progress.currentBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}% • ${formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConvertResultPanel(result: EchoConvertResult) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = result.success,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (result.success) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = if (result.success) EchoColors.NeonGreen else EchoColors.Warning,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = if (result.success) "REPARO CONCLUÍDO" else "REPARO NÃO CONCLUÍDO",
                    style = MaterialTheme.typography.titleMedium,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
                if (result.success) {
                    Text(
                        text = result.installedPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoColors.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PathLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .background(EchoColors.VoidRaised, RoundedCornerShape(9.dp))
                .border(1.dp, EchoColors.Border, RoundedCornerShape(9.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.NeonGreen,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun StepLine(index: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = EchoColors.TextSecondary,
        )
    }
}

private fun stageLabel(stage: EchoConvertStage): String = when (stage) {
    EchoConvertStage.Idle -> "IDLE"
    EchoConvertStage.Detecting -> "DETECT"
    EchoConvertStage.Downloading -> "XBOX → PHONE"
    EchoConvertStage.Verifying -> "VERIFY"
    EchoConvertStage.Extracting -> "GOD → CONTENT"
    EchoConvertStage.Uploading -> "PHONE → XBOX"
    EchoConvertStage.Completed -> "DONE"
    EchoConvertStage.Failed -> "FAILED"
}

private fun routeLabel(route: FtpRoute): String = when (route) {
    FtpRoute.Fast -> "AURORA"
    FtpRoute.Background -> "FTPDLL"
    FtpRoute.Auto -> "AUTO"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.ROOT, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}
