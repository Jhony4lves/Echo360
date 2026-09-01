package com.jhony4lves.echo360.ui

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.components.EchoBackdrop
import com.jhony4lves.echo360.ui.components.EchoNavGlyph
import com.jhony4lves.echo360.ui.convert.EchoConvertScreen
import com.jhony4lves.echo360.ui.home.EchoPlayerHomeScreen
import com.jhony4lves.echo360.ui.library.EchoPlayerLibraryScreen
import com.jhony4lves.echo360.ui.system.XboxSystemScreen
import com.jhony4lves.echo360.ui.theme.EchoColors
import com.jhony4lves.echo360.ui.theme.EchoTheme
import com.jhony4lves.echo360.ui.transfer.EchoTransferScreen

private enum class EchoDestination(
    val shortLabel: String,
    val code: String,
) {
    Home("Home", "HM"),
    Library("Jogos", "LB"),
    Transfer("Transfer", "TX"),
    Convert("Convert", "CV"),
    System("Xbox", "XB"),
}

@Composable
fun Echo360App() {
    EchoTheme {
        var destination by remember { mutableStateOf(EchoDestination.Home) }

        EchoBackdrop {
            Scaffold(
                containerColor = Color.Transparent,
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
                                icon = { EchoNavGlyph(label = item.code, selected = selected) },
                                label = { Text(item.shortLabel, style = MaterialTheme.typography.labelMedium) },
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
                    EchoDestination.Home -> EchoPlayerHomeScreen(
                        modifier = Modifier.padding(innerPadding),
                        onOpenLibrary = { destination = EchoDestination.Library },
                        onOpenTransfer = { destination = EchoDestination.Transfer },
                        onOpenSystem = { destination = EchoDestination.System },
                    )

                    EchoDestination.Library -> EchoPlayerLibraryScreen(
                        modifier = Modifier.padding(innerPadding),
                    )

                    EchoDestination.Transfer -> EchoTransferScreen(
                        modifier = Modifier.padding(innerPadding),
                    )

                    EchoDestination.Convert -> EchoConvertScreen(
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
