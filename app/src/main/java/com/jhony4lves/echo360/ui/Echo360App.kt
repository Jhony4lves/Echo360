package com.jhony4lves.echo360.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.system.XboxSystemScreen

private enum class EchoDestination(
    val shortLabel: String,
) {
    Home("Home"),
    Library("Jogos"),
    Transfer("Transfer"),
    System("Xbox"),
}

@Composable
fun Echo360App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var destination by remember { mutableStateOf(EchoDestination.Home) }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        EchoDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Text(item.shortLabel.take(1)) },
                                label = { Text(item.shortLabel) },
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

                    EchoDestination.Library -> PlaceholderScreen(
                        title = "EchoLibrary",
                        description = "Biblioteca nativa, capas, Title ID, Media ID, TU, favoritos, backlog e sessões entram depois da paridade do EchoTransfer.",
                        modifier = Modifier.padding(innerPadding),
                    )

                    EchoDestination.Transfer -> PlaceholderScreen(
                        title = "EchoTransfer",
                        description = "A camada de rede nativa está sendo construída agora. Ela será a base do Fast, Background e Auto sem Node/Termux.",
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
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Echo360",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Seu Xbox 360, reconstruído para hoje.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            XboxStatusCard(onOpenSystem = onOpenSystem)
        }

        item {
            ContinuePlayingCard(onOpenLibrary = onOpenLibrary)
        }

        item {
            Text(
                text = "Ações rápidas",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onOpenLibrary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Biblioteca")
                }
                Button(
                    onClick = onOpenTransfer,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Transferir")
                }
            }
        }

        item { FeaturePreviewCard() }
    }
}

@Composable
private fun XboxStatusCard(onOpenSystem: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Xbox",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Conectividade nativa pronta para configurar",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Configure o console uma vez. O Echo360 guarda as credenciais criptografadas e testa NOVA, Aurora FTP e FTPdll diretamente do Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenSystem) {
                Text("Configurar Xbox")
            }
        }
    }
}

@Composable
private fun ContinuePlayingCard(onOpenLibrary: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Continuar jogando",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Sua atividade aparecerá aqui",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "O EchoLibrary vai preencher este espaço com o último jogo, sessão e progresso disponível.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenLibrary) {
                Text("Ver biblioteca")
            }
        }
    }
}

@Composable
private fun FeaturePreviewCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Construindo agora",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Conectividade nativa → EchoTransfer → EchoLibrary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A rede sai do servidor Node e vira infraestrutura Android reutilizável pelo launcher inteiro.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
