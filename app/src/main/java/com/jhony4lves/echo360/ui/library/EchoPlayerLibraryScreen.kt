package com.jhony4lves.echo360.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.ArtworkSyncProgress
import com.jhony4lves.echo360.data.library.AuroraArtworkRepository
import com.jhony4lves.echo360.data.library.AuroraGameLauncher
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.data.library.LibrarySyncProgress
import com.jhony4lves.echo360.data.library.PlayerStateStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.GameStatus
import com.jhony4lves.echo360.domain.library.LibraryFilter
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.domain.library.NowPlaying
import com.jhony4lves.echo360.domain.library.PlayerGameState
import com.jhony4lves.echo360.domain.library.filterLibraryGames
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun EchoPlayerLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { AuroraLibraryRepository(context) }
    val artworkRepository = remember(context) { AuroraArtworkRepository(context) }
    val launcher = remember(context) { AuroraGameLauncher(context) }
    val playerStore = remember(context) { PlayerStateStore(context) }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<LibrarySnapshot?>(null) }
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var states by remember { mutableStateOf<Map<String, PlayerGameState>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.All) }
    var selected by remember { mutableStateOf<GameEntry?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<LibrarySyncProgress?>(null) }
    var artworkSyncing by remember { mutableStateOf(false) }
    var artworkProgress by remember { mutableStateOf<ArtworkSyncProgress?>(null) }
    var artworkRevision by remember { mutableStateOf(0) }
    var detailBackgroundFile by remember { mutableStateOf<File?>(null) }
    var detailBackgroundLoading by remember { mutableStateOf(false) }
    var detailBackgroundMessage by remember { mutableStateOf<String?>(null) }
    var detailBackgroundRevision by remember { mutableStateOf(0) }
    var detailBackgroundRequestRevision by remember { mutableStateOf(0) }
    var launchingKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshStates() {
        states = playerStore.snapshot(snapshot?.games.orEmpty())
    }

    fun refreshNowPlaying() {
        scope.launch {
            nowPlaying = runCatching { repository.nowPlaying() }.getOrNull()
            val live = nowPlaying
            val game = snapshot?.games?.firstOrNull {
                it.titleId == live?.titleId && (live?.mediaId == 0L || it.mediaId == live?.mediaId)
            } ?: snapshot?.games?.firstOrNull { it.titleId == live?.titleId }
            if (game != null) {
                playerStore.markSeen(game)
                refreshStates()
            }
        }
    }

    fun syncLibrary() {
        syncing = true
        message = null
        scope.launch {
            runCatching {
                repository.sync { progress -> syncProgress = progress }
            }.onSuccess {
                snapshot = it
                states = playerStore.snapshot(it.games)
                message = "${it.games.size} jogos carregados do Aurora."
                refreshNowPlaying()
            }.onFailure {
                message = it.message ?: "Falha ao sincronizar a biblioteca."
            }
            syncing = false
        }
    }

    fun syncArtwork() {
        val current = snapshot ?: return
        artworkSyncing = true
        artworkProgress = null
        message = null
        scope.launch {
            runCatching {
                artworkRepository.syncCovers(current) { progress -> artworkProgress = progress }
            }.onSuccess { result ->
                artworkRevision += 1
                message = "Capas: ${result.downloaded} novas, ${result.cached} em cache, ${result.unavailable} ausentes, ${result.failed} falharam."
            }.onFailure {
                message = it.message ?: "Falha ao sincronizar as capas do Aurora."
            }
            artworkSyncing = false
        }
    }

    fun launch(game: GameEntry) {
        launchingKey = game.stableKey
        message = null
        scope.launch {
            runCatching { launcher.launch(game) }
                .onSuccess {
                    playerStore.recordLaunch(game)
                    refreshStates()
                    message = "Launch enviado para ${game.title}."
                    refreshNowPlaying()
                }
                .onFailure { message = it.message ?: "NOVA recusou o launch." }
            launchingKey = null
        }
    }

    LaunchedEffect(Unit) {
        snapshot = repository.loadCached()
        states = playerStore.snapshot(snapshot?.games.orEmpty())
        refreshNowPlaying()
    }

    LaunchedEffect(
        selected?.stableKey,
        snapshot?.databaseRemotePath,
        detailBackgroundRequestRevision,
    ) {
        val game = selected
        val current = snapshot
        if (game == null || current == null) {
            detailBackgroundFile = null
            detailBackgroundLoading = false
            detailBackgroundMessage = null
            return@LaunchedEffect
        }

        detailBackgroundFile = artworkRepository.cachedBackground(game)
        detailBackgroundLoading = true
        detailBackgroundMessage = if (detailBackgroundFile != null) {
            "Cache local carregado; verificando o Aurora."
        } else {
            "Buscando background no Aurora."
        }

        try {
            val result = artworkRepository.fetchBackground(current, game)
            result.file?.let { file ->
                detailBackgroundFile = file
                detailBackgroundRevision += 1
            }
            detailBackgroundMessage = result.message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            detailBackgroundMessage = error.message ?: "Não foi possível carregar o background do Aurora."
        } finally {
            detailBackgroundLoading = false
        }
    }

    val games = snapshot?.games.orEmpty()
    val filtered = filterLibraryGames(games, states, filter, query)
    val liveGame = nowPlaying?.let { live ->
        games.firstOrNull { it.titleId == live.titleId && (live.mediaId == 0L || it.mediaId == live.mediaId) }
            ?: games.firstOrNull { it.titleId == live.titleId }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    EchoEyebrow("ECHO OS // LIBRARY")
                    Spacer(Modifier.height(5.dp))
                    Text("Jogos", style = MaterialTheme.typography.headlineLarge, color = EchoColors.Text)
                }
                EchoStatusPill(
                    text = when {
                        nowPlaying != null -> "NOVA LIVE"
                        snapshot != null -> "CACHE"
                        else -> "OFFLINE"
                    },
                    active = nowPlaying != null,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "Sua coleção primeiro. A parte técnica fica disponível quando você quiser.",
                style = MaterialTheme.typography.bodyLarge,
                color = EchoColors.TextSecondary,
            )
        }

        if (liveGame != null && nowPlaying != null) {
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                EchoEyebrow("NOW PLAYING")
                                EchoStatusPill("LIVE", true)
                            }
                            Text(liveGame.title, style = MaterialTheme.typography.headlineSmall, color = EchoColors.Text)
                            Text(
                                "TU ${nowPlaying?.titleUpdateVersion ?: 0} // ${nowPlaying?.resolutionWidth ?: 0}×${nowPlaying?.resolutionHeight ?: 0}",
                                style = MaterialTheme.typography.labelMedium,
                                color = EchoColors.TextSecondary,
                            )
                            Button(
                                onClick = { selected = liveGame },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EchoColors.SurfaceBright,
                                    contentColor = EchoColors.NeonGreen,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Outlined.SportsEsports, null)
                                Spacer(Modifier.width(7.dp))
                                Text("VER JOGO")
                            }
                        }
                    }
                }
            }
        }

        selected?.let { game ->
            item {
                GameDetailPanel(
                    game = game,
                    state = states[game.stableKey] ?: PlayerGameState(),
                    running = liveGame?.stableKey == game.stableKey,
                    launching = launchingKey == game.stableKey,
                    artworkRevision = artworkRevision,
                    backgroundFile = detailBackgroundFile,
                    backgroundLoading = detailBackgroundLoading,
                    backgroundMessage = detailBackgroundMessage,
                    backgroundRevision = detailBackgroundRevision,
                    onRetryBackground = { detailBackgroundRequestRevision += 1 },
                    onClose = { selected = null },
                    onFavorite = {
                        playerStore.toggleFavorite(game)
                        refreshStates()
                    },
                    onStatus = {
                        playerStore.setStatus(game, it)
                        refreshStates()
                    },
                    onLaunch = { launch(game) },
                )
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            EchoEyebrow("COLLECTION SYNC")
                            Text(
                                if (snapshot == null) "Conecte sua biblioteca" else "${games.size} itens no catálogo",
                                style = MaterialTheme.typography.titleMedium,
                                color = EchoColors.Text,
                            )
                        }
                        EchoStatusPill(if (snapshot == null) "EMPTY" else "READY", snapshot != null)
                    }
                    Button(
                        onClick = ::syncLibrary,
                        enabled = !syncing && !artworkSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EchoColors.NeonGreen,
                            contentColor = EchoColors.Void,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = EchoColors.Void,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (snapshot == null) "SINCRONIZAR COM AURORA" else "ATUALIZAR CATÁLOGO")
                    }
                    if (snapshot != null) {
                        Button(
                            onClick = ::syncArtwork,
                            enabled = !syncing && !artworkSyncing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.SurfaceBright,
                                contentColor = EchoColors.NeonGreen,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (artworkSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = EchoColors.NeonGreen,
                                )
                            } else {
                                Icon(Icons.Outlined.Refresh, null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (artworkSyncing) "SINCRONIZANDO CAPAS" else "SINCRONIZAR CAPAS DO AURORA")
                        }
                    }
                    syncProgress?.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextSecondary)
                    }
                    artworkProgress?.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextSecondary)
                    }
                }
            }
        }

        message?.let { text ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        if (snapshot != null) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Buscar jogo ou Title ID") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = EchoColors.Text,
                        unfocusedTextColor = EchoColors.Text,
                        focusedBorderColor = EchoColors.NeonGreen,
                        unfocusedBorderColor = EchoColors.BorderStrong,
                        focusedLabelColor = EchoColors.NeonGreen,
                        unfocusedLabelColor = EchoColors.TextMuted,
                        cursorColor = EchoColors.NeonGreen,
                    ),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EchoColors.NeonGreen.copy(alpha = 0.14f),
                                selectedLabelColor = EchoColors.NeonGreen,
                                labelColor = EchoColors.TextSecondary,
                            ),
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EchoEyebrow("COLLECTION // ${filtered.size}")
                    Text(
                        "${states.values.count { it.favorite }} FAVORITOS",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EchoPanel(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Nenhum jogo nesse filtro.",
                            modifier = Modifier.padding(18.dp),
                            color = EchoColors.TextSecondary,
                        )
                    }
                }
            } else {
                items(filtered, key = { it.stableKey }) { game ->
                    PlayerGameCard(
                        game = game,
                        state = states[game.stableKey] ?: PlayerGameState(),
                        running = liveGame?.stableKey == game.stableKey,
                        artworkRevision = artworkRevision,
                        onOpen = { selected = game },
                        onFavorite = {
                            playerStore.toggleFavorite(game)
                            refreshStates()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerGameCard(
    game: GameEntry,
    state: PlayerGameState,
    running: Boolean,
    artworkRevision: Int,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = running) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CachedGameArt(game, 66, artworkRevision)
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        game.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (running) EchoStatusPill("LIVE", true)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        state.status != GameStatus.None -> state.status.label.uppercase()
                        state.launchCount > 0 -> "JOGADO ${state.launchCount}x"
                        else -> game.titleIdHex
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.status != GameStatus.None) EchoColors.NeonGreen else EchoColors.TextMuted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${game.titleIdHex} // MID ${game.mediaIdHex}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextMuted,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (state.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (state.favorite) EchoColors.NeonGreen else EchoColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun GameDetailPanel(
    game: GameEntry,
    state: PlayerGameState,
    running: Boolean,
    launching: Boolean,
    artworkRevision: Int,
    backgroundFile: File?,
    backgroundLoading: Boolean,
    backgroundMessage: String?,
    backgroundRevision: Int,
    onRetryBackground: () -> Unit,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onStatus: (GameStatus) -> Unit,
    onLaunch: () -> Unit,
) {
    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GameDetailBackgroundHero(
                game = game,
                backgroundFile = backgroundFile,
                loading = backgroundLoading,
                message = backgroundMessage,
                revision = backgroundRevision,
                onRetry = onRetryBackground,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                CachedGameArt(game, 84, artworkRevision)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        EchoEyebrow(if (running) "RUNNING NOW" else "GAME PROFILE")
                        IconButton(onClick = onFavorite) {
                            Icon(
                                if (state.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                null,
                                tint = if (state.favorite) EchoColors.NeonGreen else EchoColors.TextMuted,
                            )
                        }
                    }
                    Text(game.title, style = MaterialTheme.typography.headlineSmall, color = EchoColors.Text)
                    Text(
                        "${game.titleIdHex} // MID ${game.mediaIdHex}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(GameStatus.WantToPlay, GameStatus.Playing, GameStatus.Finished).forEach { status ->
                    FilterChip(
                        selected = state.status == status,
                        onClick = { onStatus(if (state.status == status) GameStatus.None else status) },
                        label = { Text(status.label) },
                        leadingIcon = if (state.status == status) {
                            { Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }

            Text(
                game.canonicalExecutablePath ?: game.executable,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            game.baseVersion?.takeIf { it.isNotBlank() }?.let {
                Text("BASE VERSION // $it", style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = onLaunch,
                    enabled = !launching && game.canonicalDirectory != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (launching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = EchoColors.Void,
                        )
                    } else {
                        Icon(Icons.Outlined.PlayArrow, null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (running) "REINICIAR" else "JOGAR", fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(0.65f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.SurfaceBright,
                        contentColor = EchoColors.Text,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("FECHAR")
                }
            }
        }
    }
}
