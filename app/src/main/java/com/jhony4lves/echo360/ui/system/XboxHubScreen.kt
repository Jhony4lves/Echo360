package com.jhony4lves.echo360.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.remote.EchoRemoteScreen
import com.jhony4lves.echo360.ui.theme.EchoColors

private enum class XboxHubMode(val label: String) {
    Connection("CONEXÃO"),
    Remote("REMOTE"),
}

@Composable
fun XboxHubScreen(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(XboxHubMode.Connection) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            XboxHubMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EchoColors.NeonGreen.copy(alpha = 0.14f),
                        selectedLabelColor = EchoColors.NeonGreen,
                        labelColor = EchoColors.TextSecondary,
                    ),
                )
            }
        }

        when (mode) {
            XboxHubMode.Connection -> XboxSystemScreen(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            XboxHubMode.Remote -> EchoRemoteScreen(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}
