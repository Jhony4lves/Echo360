package com.jhony4lves.echo360.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.data.library.PlayerStateStore
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.domain.library.NowPlaying
import com.jhony4lves.echo360.domain.library.PlayerGameState
import com.jhony4lves.echo360.domain.library.buildHomeCollectionModel
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.library.CachedGameArt
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch

@Composable
fun EchoPlayerHomeScreen(
    modifier: Modifier = Modifier,
    onOpenLibrary: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { AuroraLibraryRepository(context) }
    val playerStore = remember(context) { PlayerStateStore(context) }
    val configured = remember(context) { SecureXboxConfigStore(context).load() != null }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var states by remember { mutableStateOf<Map<String, PlayerGameState>>(emptyMap()) }

    fun refreshLive() {
        if (!configured) return
        scope.launch {
            nowPlaying = runCatching { repository.nowPlaying() }.getOrNull()
            val live = nowPlaying
            val game = snapshot?.games?.firstOrNull {
                it.titleId == live?.titleId && (live?.mediaId == 0L || it.mediaId == live?.mediaId)
            } ?: snapshot?.games?.firstOrNull { it.titleId == live?.titleId }
            if (game != null) {
                playerStore.markSeen(game)
                states = playerStore.snapshot(snapshot?.games.orEmpty())
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshot = repository.loadCached()
        states = playerStore.snapshot(snapshot?.games.orEmpty())
        refreshLive()
    }

    val games = snapshot?.games.orEmpty()
    val liveGame = nowPlaying?.let { live ->
        games.firstOrNull { it.titleId == live.titleId && (live.mediaId == 0L || it.mediaId == live.mediaId) }
            ?: games.firstOrNull { it.titleId == live.titleId }
    }
    val collection = buildHomeCollectionModel(
        games = games,
        states = states,
        liveGame = liveGame,
    )
    val continueGame = collection.continueGame

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        EchoEyebrow("ECHO OS // MOBILE LINK")
                        Spacer(Modifier.height(5.dp))
                        Text("ECHO//360", style = MaterialTheme.typography.displaySmall, color = EchoColors.Text)
                    }
                    EchoStatusPill("ALPHA", true)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Seu Xbox 360, reconstruído para hoje.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.TextSecondary,
                )
            }
        }

        item {
            EchoPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                highlighted = configured,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(EchoColors.XboxGreen.copy(alpha = 0.14f), EchoColors.SurfaceHigh),
                            ),
                        )
                        .padding(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EchoEyebrow("CONSOLE LINK")
                            EchoStatusPill(
                                when {
                                    nowPlaying != null -> "LIVE"
                                    configured -> "READY"
                                    else -> "SETUP"
                                },
                                nowPlaying != null,
                            )
                        }
                        Text(
                            when {
                                nowPlaying != null -> "Xbox online na rede Echo."
                                configured -> "Xbox configurado e pronto para conectar."
                                else -> "Configure seu Xbox para liberar a rede Echo."
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = EchoColors.Text,
                        )
                        Text(
                            if (configured) {
                                "NOVA, Aurora FTP e FTPdll disponíveis pela camada nativa."
                            } else {
                                "IP, NOVA e FTP ficam protegidos pelo Android Keystore."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                        )
                        Button(
                            onClick = onOpenSystem,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (configured) EchoColors.SurfaceBright else EchoColors.NeonGreen,
                                contentColor = if (configured) EchoColors.NeonGreen else EchoColors.Void,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Outlined.SettingsEthernet, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (configured) "ABRIR CONSOLE LINK" else "CONFIGURAR XBOX")
                        }
                    }
                }
            }
        }

        item {
            EchoPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                highlighted = continueGame != null,
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EchoEyebrow("CONTINUAR JOGANDO")
                        EchoStatusPill(if (liveGame != null) "RUNNING" else "SESSION", liveGame != null)
                    }

                    if (continueGame == null) {
                        Text(
                            if (snapshot == null) {
                                "Sua biblioteca ainda não foi sincronizada."
                            } else {
                                "Jogue ou marque títulos para começar seu histórico."
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = EchoColors.Text,
                        )
                        Text(
                            "O Echo360 usa a Library e a NOVA para transformar esta área na sua retomada rápida.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CachedGameArt(game = continueGame, size = 72, revision = 0)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    continueGame.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = EchoColors.Text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    if (liveGame != null) {
                                        "TU ${nowPlaying?.titleUpdateVersion ?: 0} // ${nowPlaying?.resolutionWidth ?: 0}×${nowPlaying?.resolutionHeight ?: 0}"
                                    } else {
                                        val state = states[continueGame.stableKey] ?: PlayerGameState()
                                        if (state.launchCount > 0) {
                                            "INICIADO ${state.launchCount}x PELO ECHO"
                                        } else {
                                            "ÚLTIMA ATIVIDADE SALVA"
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EchoColors.NeonGreen,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onOpenLibrary,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EchoColors.NeonGreen,
                            contentColor = EchoColors.Void,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Outlined.SportsEsports, null)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (continueGame == null) "ABRIR BIBLIOTECA" else "VER NA BIBLIOTECA",
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }

        item {
            HomeCollectionRail(
                eyebrow = "RECENTES",
                games = collection.recentGames,
                states = states,
                artworkRevision = 0,
                onOpenLibrary = onOpenLibrary,
            )
        }

        item {
            HomeCollectionRail(
                eyebrow = "FAVORITOS",
                games = collection.favoriteGames,
                states = states,
                artworkRevision = 0,
                onOpenLibrary = onOpenLibrary,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickTile(
                    code = "LB",
                    title = "Biblioteca",
                    subtitle = if (snapshot == null) {
                        "Sincronizar jogos"
                    } else {
                        "${collection.totalGames} jogos • ${collection.favoriteCount} favoritos"
                    },
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLibrary,
                )
                QuickTile(
                    code = "TX",
                    title = "Transfer",
                    subtitle = "Fast / Background / Auto",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTransfer,
                )
            }
        }

        item {
            EchoPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    EchoEyebrow("BUILD CHANNEL")
                    Text("Player Experience", style = MaterialTheme.typography.titleLarge, color = EchoColors.Text)
                    Text(
                        "NET → FTP → COMPARE → UPLOAD → LIBRARY → PLAYER",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("NET", "FTP", "CMP", "UP", "LIB", "PLY").forEach {
                            Box(
                                modifier = Modifier
                                    .background(EchoColors.NeonGreen.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = EchoColors.NeonGreen)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    code: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    EchoPanel(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.NeonGreen,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(17.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = EchoColors.Text)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
                maxLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            Icon(
                if (code == "TX") Icons.Outlined.FolderCopy else Icons.Outlined.SportsEsports,
                null,
                tint = EchoColors.NeonGreen,
            )
        }
    }
}
