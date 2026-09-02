package com.jhony4lves.echo360.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jhony4lves.echo360.data.library.AuroraArtworkRepository
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.ui.theme.EchoColors

@Composable
internal fun CachedGameArt(
    game: GameEntry,
    size: Int,
    revision: Int,
) {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) { AuroraArtworkRepository(appContext) }
    val cover = repository.cachedCover(game)
    val bitmap = remember(game.stableKey, revision, cover?.absolutePath, cover?.lastModified()) {
        cover?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
    }
    val shape = RoundedCornerShape(16.dp)

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Capa de ${game.title}",
            modifier = Modifier
                .size(size.dp)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
        return
    }

    val initials = game.title
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "X" }

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                Brush.radialGradient(
                    listOf(EchoColors.NeonGreen.copy(alpha = 0.30f), EchoColors.SurfaceHigh, EchoColors.Void),
                ),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.headlineMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Black,
        )
    }
}
