package com.jhony4lves.echo360.ui.library

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.AuroraGameLauncher
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.data.library.LibrarySyncProgress
import com.jhony4lves.echo360.data.library.LibrarySyncStage
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.domain.library.NowPlaying
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun EchoLibraryScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { AuroraLibraryRepository(context) }
    val launcher = remember(context) { AuroraGameLauncher(context) }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var query by remember { mutableStateOf("") }
    var syncProgress by remember { mutableStateOf<LibrarySyncProgress?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var launchMessage by remember { mutableStateOf<String?>(null) }
    var launchingId by remember { mutableStateOf<Long?>(null) }

    fun refreshNowPlaying() {
        scope.launch {
            nowPlaying = runCatching { repository.nowPlaying() }.getOrNull()
        }
    }

    LaunchedEffect(Unit) {
        snapshot = repository.loadCached()
        nowPlaying = runCatching { repository.nowPlaying() }.getOrNull()
    }

    val filteredGames = snapshot?.games.orEmpty().filter { game ->
        query.isBlank() || game.title.contains(query, ignoreCase = true) ||
            game.titleIdHex.contains(query, ignoreCase = true)
    }
    val runningGame = nowPlaying?.let { running ->
        snapshot?.games?.firstOrNull { it.titleId == running.titleId && (running.mediaId == 0L || it.mediaId == running.mediaId) }
            ?: snapshot?.games?.firstOrNull { it.titleId == running.titleId }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LibraryHeader(
                count = snapshot?.games?.size,
                online = nowPlaying != null,
            )
        }

        if (nowPlaying != null) {
            item {
                NowPlayingPanel(
                    nowPlaying = checkNotNull(nowPlaying),
                    game = runningGame,
                    onRefresh = ::refreshNowPlaying,
                )
            }
        }

        item {
            EchoPanel(
                modifier = Modifier.fillMaxWidth(),
                highlighted = snapshot != null,
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
                        Column {
                            EchoEyebrow("AURORA CATALOG")
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (snapshot == null) "Importar biblioteca" else "Biblioteca sincronizada",
                                style = MaterialTheme.typography.titleLarge,
                                color = EchoColors.Text,
                            )
                        }
                        EchoStatusPill(
                            text = if (snapshot == null) "EMPTY" else "${snapshot?.games?.size ?: 0} ITEMS",
                            active = snapshot != null,
                        )
                    }

                    Text(
                        text = "O Echo360 lê um snapshot local do content.db. O banco no Xbox nunca é alterado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )

                    Button(
                        onClick = {
                            isSyncing = true
                            error = null
                            launchMessage = null
                            scope.launch {
                                runCatching {
                                    repository.sync { progress ->
                                        scope.launch { syncProgress = progress }
                                    }
                                }.onSuccess {
                                    snapshot = it
                                    syncProgress = LibrarySyncProgress(
                                        stage = LibrarySyncStage.Completed,
                                        transferredBytes = it.databaseBytes,
                                        totalBytes = it.databaseBytes,
                                        gameCount = it.games.size,
                                        message = "${it.games.size} item(ns) carregados.",
                                    )
                                    refreshNowPlaying()
                                }.onFailure {
                                    error = it.message ?: "Falha ao sincronizar a biblioteca."
                                }
                                isSyncing = false
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EchoColors.NeonGreen,
                            contentColor = EchoColors.Void,
                        ),
                        contentPadding = PaddingValues(vertical = 13.dp),
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                strokeWidth = 2.dp,
                                color = EchoColors.Void,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (snapshot == null) "SINCRONIZAR COM AURORA" else "ATUALIZAR BIBLIOTECA",
                            fontWeight = FontWeight.Black,
                        )
                    }

                    syncProgress?.let { progress ->
                        SyncProgress(progress)
                    }
                }
            }
        }

        error?.let { message ->
            item { StatusMessage(message, EchoColors.Warning) }
        }
        launchMessage?.let { message ->
            item { StatusMessage(message, EchoColors.Info) }
        }

        if (snapshot != null) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Buscar jogo ou Title ID") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = EchoColors.Text,
                        unfocusedTextColor = EchoColors.Text,
                        focusedBorderColor = EchoColors.NeonGreen,
                        unfocusedBorderColor = EchoColors.BorderStrong,
                        focusedLabelColor = EchoColors.NeonGreen,
                        unfocusedLabelColor = EchoColors.TextMuted,
                        cursorColor = EchoColors.NeonGreen,
                        focusedLeadingIconColor = EchoColors.NeonGreen,
                        unfocusedLeadingIconColor = EchoColors.TextMuted,
                    ),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EchoEyebrow("INSTALLED // ${filteredGames.size}")
                    snapshot?.let {
                        Text(
                            text = it.auroraRoot.replace("/", "\\").removePrefix("\\"),
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
            }

            if (filteredGames.isEmpty()) {
                item {
                    StatusMessage("Nenhum item corresponde à busca.", EchoColors.TextMuted)
                }
            } else {
                items(
                    items = filteredGames,
                    key = { "${it.databaseId}:${it.titleId}:${it.discNumber}" },
                ) { game ->
                    GameCard(
                        game = game,
                        running = nowPlaying?.titleId == game.titleId,
                        launching = launchingId == game.databaseId,
                        onLaunch = {
                            launchingId = game.databaseId
                            launchMessage = null
                            scope.launch {
                                runCatching { launcher.launch(game) }
                                    .onSuccess {
                                        launchMessage = "Launch enviado para ${game.title}."
                                        refreshNowPlaying()
                                    }
                                    .onFailure {
                                        launchMessage = it.message ?: "NOVA recusou o launch."
                                    }
                                launchingId = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(count: Int?, online: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                EchoEyebrow("ECHO OS // LIBRARY")
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "Sua biblioteca",
                    style = MaterialTheme.typography.headlineLarge,
                    color = EchoColors.Text,
                )
            }
            EchoStatusPill(
                text = when {
                    online -> "NOVA LIVE"
                    count != null -> "CACHE"
                    else -> "OFFLINE"
                },
                active = online,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Jogos primeiro. Metadados técnicos quando você quiser.",
            style = MaterialTheme.typography.bodyLarge,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun NowPlayingPanel(
    nowPlaying: NowPlaying,
    game: GameEntry?,
    onRefresh: () -> Unit,
) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(EchoColors.XboxGreen.copy(alpha = 0.18f), EchoColors.SurfaceHigh),
                    ),
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EchoEyebrow("NOW PLAYING")
                    EchoStatusPill("RUNNING", true)
                }
                Text(
                    text = game?.title ?: "Title ${nowPlaying.titleIdHex}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = EchoColors.Text,
                )
                Text(
                    text = buildString {
                        append("TU ${nowPlaying.titleUpdateVersion}")
                        append("  //  ${nowPlaying.resolutionWidth}×${nowPlaying.resolutionHeight}")
                        if (nowPlaying.discCount > 1) append("  //  DISC ${nowPlaying.discCurrent}/${nowPlaying.discCount}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.SurfaceBright,
                        contentColor = EchoColors.NeonGreen,
                    ),
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("ATUALIZAR", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    game: GameEntry,
    running: Boolean,
    launching: Boolean,
    onLaunch: () -> Unit,
) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = running,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(EchoColors.NeonGreen.copy(alpha = 0.30f), EchoColors.VoidRaised),
                        ),
                        RoundedCornerShape(14.dp),
                    )
                    .border(1.dp, EchoColors.BorderStrong, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = game.title.firstOrNull()?.uppercase() ?: "X",
                    style = MaterialTheme.typography.headlineMedium,
                    color = EchoColors.NeonGreen,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = game.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (running) {
                        Spacer(Modifier.width(6.dp))
                        EchoStatusPill("LIVE", true)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "${game.titleIdHex}  •  MID ${game.mediaIdHex}${if (game.discNumber > 1) "  •  DISC ${game.discNumber}" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = game.canonicalExecutablePath ?: game.executable,
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onLaunch,
                enabled = game.canonicalDirectory != null && !launching,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) EchoColors.SurfaceBright else EchoColors.NeonGreen,
                    contentColor = if (running) EchoColors.NeonGreen else EchoColors.Void,
                    disabledContainerColor = EchoColors.SurfaceBright,
                    disabledContentColor = EchoColors.TextMuted,
                ),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
            ) {
                if (launching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Jogar", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncProgress(progress: LibrarySyncProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = progress.stage.name.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.NeonGreen,
            )
            if (progress.totalBytes > 0L) {
                Text(
                    text = "${formatBytes(progress.transferredBytes)} / ${formatBytes(progress.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                )
            }
        }
        if (progress.stage == LibrarySyncStage.DownloadingDatabase) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = EchoColors.NeonGreen,
                trackColor = EchoColors.SurfaceBright,
            )
        }
        Text(
            text = progress.message,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun StatusMessage(text: String, color: Color) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (color == EchoColors.Warning) Icons.Outlined.Storage else Icons.Outlined.Gamepad,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(9.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = EchoColors.TextSecondary)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}
