package com.jhony4lves.echo360.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.GameStatus
import com.jhony4lves.echo360.domain.library.PlayerGameState
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.library.CachedGameArt
import com.jhony4lves.echo360.ui.theme.EchoColors

@Composable
internal fun HomeCollectionRail(
    eyebrow: String,
    games: List<GameEntry>,
    states: Map<String, PlayerGameState>,
    artworkRevision: Int,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (games.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EchoEyebrow(eyebrow)
            Text(
                "${games.size} JOGOS",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            items(games, key = { it.stableKey }) { game ->
                val state = states[game.stableKey] ?: PlayerGameState()
                HomeRailCard(
                    game = game,
                    state = state,
                    artworkRevision = artworkRevision,
                    onClick = onOpenLibrary,
                )
            }
        }
    }
}

@Composable
private fun HomeRailCard(
    game: GameEntry,
    state: PlayerGameState,
    artworkRevision: Int,
    onClick: () -> Unit,
) {
    EchoPanel(modifier = Modifier.width(132.dp)) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(10.dp),
        ) {
            CachedGameArt(game = game, size = 112, revision = artworkRevision)
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    game.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = EchoColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Favorite,
                        contentDescription = "Favorito",
                        tint = EchoColors.NeonGreen,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.status != GameStatus.None -> state.status.label.uppercase()
                    state.launchCount > 0 -> "JOGADO ${state.launchCount}x"
                    else -> game.titleIdHex
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.status != GameStatus.None) EchoColors.NeonGreen else EchoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
