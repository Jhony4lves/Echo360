package com.jhony4lves.echo360.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.library.GameCapabilityMetadataStore
import com.jhony4lves.echo360.domain.library.GameCapabilityMetadata
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.KinectSupport
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.theme.EchoColors

/**
 * Explicit player-confirmed metadata editor.
 *
 * Nothing is inferred from the title name. Unknown remains a real value and
 * therefore cannot accidentally make a game appear in Kinect/local-MP filters.
 */
@Composable
internal fun GameCapabilityMetadataEditor(game: GameEntry) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { GameCapabilityMetadataStore(context) }
    val initial = remember(game.titleId) { store.metadataFor(game) }

    var kinect by remember(game.titleId) { mutableStateOf(initial.kinect) }
    var localPlayersText by remember(game.titleId) {
        mutableStateOf(initial.localPlayers?.toString().orEmpty())
    }
    var genreText by remember(game.titleId) { mutableStateOf(initial.normalizedGenre.orEmpty()) }
    var message by remember(game.titleId) { mutableStateOf<String?>(null) }

    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EchoEyebrow("LIBRARY METADATA // CONFIRMADO POR VOCÊ")
            Text(
                "Esses dados alimentam os filtros sem tentar adivinhar recursos pelo nome do jogo.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )

            Text(
                "Kinect",
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.Text,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                KinectSupport.entries.forEach { option ->
                    FilterChip(
                        selected = kinect == option,
                        onClick = {
                            kinect = option
                            message = null
                        },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EchoColors.NeonGreen.copy(alpha = 0.14f),
                            selectedLabelColor = EchoColors.NeonGreen,
                            labelColor = EchoColors.TextSecondary,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = localPlayersText,
                onValueChange = { value ->
                    localPlayersText = value.filter(Char::isDigit).take(2)
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Jogadores locais (1–16, vazio = desconhecido)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = editorFieldColors(),
            )

            OutlinedTextField(
                value = genreText,
                onValueChange = { value ->
                    genreText = value.take(GameCapabilityMetadataStore.MAX_GENRE_CHARS)
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gênero (vazio = desconhecido)") },
                supportingText = {
                    Text("Ex.: Corrida, RPG, Dança. Busca e filtro usam apenas o que for salvo aqui.")
                },
                colors = editorFieldColors(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val players = localPlayersText.toIntOrNull()
                        if (
                            localPlayersText.isNotBlank() &&
                            (players == null || players !in 1..16)
                        ) {
                            message = "Jogadores locais precisa ficar entre 1 e 16."
                        } else {
                            val saved = store.save(
                                game = game,
                                metadata = GameCapabilityMetadata(
                                    kinect = kinect,
                                    localPlayers = players,
                                    genre = genreText,
                                ),
                            )
                            kinect = saved.kinect
                            localPlayersText = saved.localPlayers?.toString().orEmpty()
                            genreText = saved.normalizedGenre.orEmpty()
                            message = "Metadata salva para o Title ID ${game.titleIdHex}."
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    Text("SALVAR")
                }

                OutlinedButton(
                    onClick = {
                        store.clear(game)
                        kinect = KinectSupport.Unknown
                        localPlayersText = ""
                        genreText = ""
                        message = "Metadata removida; recursos voltaram a Desconhecido."
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("LIMPAR")
                }
            }

            message?.let { text ->
                Spacer(Modifier.height(1.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (text.startsWith("Jogadores")) EchoColors.Warning else EchoColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EchoColors.Text,
    unfocusedTextColor = EchoColors.Text,
    focusedBorderColor = EchoColors.NeonGreen,
    unfocusedBorderColor = EchoColors.BorderStrong,
    focusedLabelColor = EchoColors.NeonGreen,
    unfocusedLabelColor = EchoColors.TextMuted,
    cursorColor = EchoColors.NeonGreen,
)
