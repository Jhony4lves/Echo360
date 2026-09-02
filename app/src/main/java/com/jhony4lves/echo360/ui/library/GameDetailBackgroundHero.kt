package com.jhony4lves.echo360.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.theme.EchoColors
import java.io.File

@Composable
internal fun GameDetailBackgroundHero(
    game: GameEntry,
    backgroundFile: File?,
    loading: Boolean,
    message: String?,
    revision: Int,
    onRetry: () -> Unit,
) {
    val bitmap = remember(
        game.stableKey,
        revision,
        backgroundFile?.absolutePath,
        backgroundFile?.lastModified(),
    ) {
        backgroundFile
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
    }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        EchoColors.XboxGreen.copy(alpha = 0.24f),
                        EchoColors.SurfaceHigh,
                        EchoColors.Void,
                    ),
                ),
            ),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Background de ${game.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                EchoColors.Void.copy(alpha = 0.05f),
                                EchoColors.Void.copy(alpha = 0.28f),
                                EchoColors.Void.copy(alpha = 0.88f),
                            ),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        ) {
            EchoEyebrow(if (bitmap != null) "AURORA BACKGROUND" else "GAME ART")
            Spacer(Modifier.height(4.dp))
            Text(
                game.title,
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            message?.takeIf { it.isNotBlank() }?.let { status ->
                Spacer(Modifier.height(3.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .size(22.dp),
                strokeWidth = 2.dp,
                color = EchoColors.NeonGreen,
            )
        } else if (bitmap == null) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.SurfaceBright.copy(alpha = 0.92f),
                    contentColor = EchoColors.NeonGreen,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("BUSCAR FUNDO")
            }
        }
    }
}
