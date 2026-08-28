package com.jhony4lves.echo360.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.components.EchoBackdrop
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoNavGlyph
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.system.XboxSystemScreen
import com.jhony4lves.echo360.ui.theme.EchoColors
import com.jhony4lves.echo360.ui.theme.EchoTheme

private enum class EchoDestination(
    val shortLabel: String,
    val code: String,
) {
    Home("Home", "HM"),
    Library("Jogos", "LB"),
    Transfer("Transfer", "TX"),
    System("Xbox", "XB"),
}

@Composable
fun Echo360App() {
    EchoTheme {
        var destination by remember { mutableStateOf(EchoDestination.Home) }

        EchoBackdrop {
            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        containerColor = EchoColors.VoidRaised.copy(alpha = 0.98f),
                        tonalElevation = 0.dp,
                    ) {
                        EchoDestination.entries.forEach { item ->
                            val selected = destination == item
                            NavigationBarItem(
                                selected = selected,
                                onClick = { destination = item },
                                icon = {
                                    EchoNavGlyph(
                                        label = item.code,
                                        selected = selected,
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.shortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = EchoColors.NeonGreen,
                                    selectedTextColor = EchoColors.NeonGreen,
                                    indicatorColor = EchoColors.NeonGreen.copy(alpha = 0.08f),
                                    unselectedIconColor = EchoColors.TextMuted,
                                    unselectedTextColor = EchoColors.TextMuted,
                                ),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                when (destination) {
                    EchoDestination.Home -> EchoHome(
                        modifier = Modifier.padding(innerPadding),
                        onOpenLibrary = { destination = EchoDestination.Library },
                        onOpenTransfer = { destination = EchoDestination.Transfer },
                        onOpenSystem = { destination = EchoDestination.System },
                    )

                    EchoDestination.Library -> FuturisticPlaceholderScreen(
                        eyebrow = "ECHO LIBRARY",
                        title = "Sua biblioteca vira o launcher.",
                        description = "Capas, sessões, favoritos, backlog, Title ID, Media ID e TU vão morar aqui com foco primeiro no jogador.",
                        modifier = Modifier.padding(innerPadding),
                    )

                    EchoDestination.Transfer -> FuturisticPlaceholderScreen(
                        eyebrow = "ECHO TRANSFER",
                        title = "Transferência sem Termux.",
                        description = "Fast, Background e Auto estão migrando para a camada Android nativa com comparação e verificação pós-upload.",
                        modifier = Modifier.padding(innerPadding),
                    )

                    EchoDestination.System -> XboxSystemScreen(
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun EchoHome(
    modifier: Modifier = Modifier,
    onOpenLibrary: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeroHeader() }
        item { ConsoleCommandCard(onOpenSystem = onOpenSystem) }
        item { ContinuePlayingCard(onOpenLibrary = onOpenLibrary) }
        item { QuickActions(onOpenLibrary, onOpenTransfer) }
        item { BuildStatusCard() }
    }
}

@Composable
private fun HeroHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                EchoEyebrow("ECHO OS // MOBILE LINK")
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "ECHO//360",
                    style = MaterialTheme.typography.displaySmall,
                    color = EchoColors.Text,
                )
            }
            EchoStatusPill(text = "ALPHA", active = true)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Seu Xbox 360, reconstruído para hoje.",
            style = MaterialTheme.typography.bodyLarge,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun ConsoleCommandCard(onOpenSystem: () -> Unit) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            EchoColors.XboxGreen.copy(alpha = 0.13f),
                            EchoColors.SurfaceHigh,
                        ),
                    ),
                )
                .padding(18.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EchoEyebrow("CONSOLE LINK")
                    EchoStatusPill(text = "CONFIGURAR", active = false)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Xbox pronto para entrar na rede Echo.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = EchoColors.Text,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "NOVA, Aurora FTP e FTPdll passam pela mesma camada nativa e segura do aplicativo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                EchoPrimaryButton(
                    text = "CONFIGURAR XBOX",
                    onClick = onOpenSystem,
                )
            }
        }
    }
}

@Composable
private fun ContinuePlayingCard(onOpenLibrary: () -> Unit) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoEyebrow("CONTINUAR JOGANDO")
                Text(
                    text = "SESSION // --",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            brush = Brush.radialGradient(
                                listOf(
                                    EchoColors.NeonGreen.copy(alpha = 0.28f),
                                    EchoColors.SurfaceBright,
                                ),
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "X",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = EchoColors.NeonGreen,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Aguardando primeira sessão",
                        style = MaterialTheme.typography.titleLarge,
                        color = EchoColors.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "O EchoLibrary vai preencher este painel com seu último jogo, tempo e progresso.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            EchoSecondaryButton(
                text = "ABRIR BIBLIOTECA",
                onClick = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun QuickActions(
    onOpenLibrary: () -> Unit,
    onOpenTransfer: () -> Unit,
) {
    Column {
        EchoEyebrow("QUICK ACCESS")
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionTile(
                code = "LB",
                title = "Biblioteca",
                subtitle = "Jogos e sessões",
                modifier = Modifier.weight(1f),
                onClick = onOpenLibrary,
            )
            QuickActionTile(
                code = "TX",
                title = "Transfer",
                subtitle = "Fast / Background",
                modifier = Modifier.weight(1f),
                onClick = onOpenTransfer,
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    code: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    EchoPanel(modifier = modifier) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = code,
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.NeonGreen,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = EchoColors.Text,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.SurfaceBright,
                    contentColor = EchoColors.NeonGreen,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("ABRIR", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BuildStatusCard() {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            EchoEyebrow("BUILD CHANNEL")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Native Transfer Stack",
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.Text,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Conectividade → comparação → upload → Library",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PhaseChip("NET", true)
                PhaseChip("FTP", true)
                PhaseChip("CMP", true)
                PhaseChip("UP", false)
                PhaseChip("LIB", false)
            }
        }
    }
}

@Composable
private fun PhaseChip(text: String, active: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (active) {
                    EchoColors.NeonGreen.copy(alpha = 0.10f)
                } else {
                    EchoColors.SurfaceBright
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) EchoColors.NeonGreen else EchoColors.TextMuted,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EchoPrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = EchoColors.NeonGreen,
            contentColor = EchoColors.Void,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun EchoSecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = EchoColors.SurfaceBright,
            contentColor = EchoColors.Text,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FuturisticPlaceholderScreen(
    eyebrow: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EchoPanel(
            modifier = Modifier.fillMaxWidth(),
            highlighted = true,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                EchoEyebrow(eyebrow)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = EchoColors.Text,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.TextSecondary,
                )
            }
        }
    }
}