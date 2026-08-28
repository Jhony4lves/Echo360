package com.jhony4lves.echo360.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.theme.EchoColors

@Composable
fun EchoBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        EchoColors.VoidRaised,
                        EchoColors.Void,
                        Color(0xFF010302),
                    ),
                ),
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        EchoColors.XboxGreen.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    radius = 1050f,
                ),
            ),
    ) {
        content()
    }
}

@Composable
fun EchoPanel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val border = if (highlighted) {
        EchoColors.NeonGreen.copy(alpha = 0.42f)
    } else {
        EchoColors.Border.copy(alpha = 0.95f)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, border),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                EchoColors.SurfaceHigh.copy(alpha = 0.97f)
            } else {
                EchoColors.Surface.copy(alpha = 0.97f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

@Composable
fun EchoEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = EchoColors.NeonGreen,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun EchoStatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val foreground = if (active) EchoColors.NeonGreen else EchoColors.TextSecondary
    val background = if (active) {
        EchoColors.NeonGreen.copy(alpha = 0.10f)
    } else {
        EchoColors.SurfaceBright.copy(alpha = 0.65f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, foreground.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(foreground)
                .padding(3.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

@Composable
fun EchoNavGlyph(
    label: String,
    selected: Boolean,
) {
    val foreground = if (selected) EchoColors.NeonGreen else EchoColors.TextMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) EchoColors.NeonGreen.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) foreground.copy(alpha = 0.32f) else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(2).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
fun EchoButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier, content = content)
}