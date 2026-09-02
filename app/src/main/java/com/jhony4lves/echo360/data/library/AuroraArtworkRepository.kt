package com.jhony4lves.echo360.data.library

import android.content.Context
import android.graphics.Bitmap
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class AuroraArtworkRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val root = File(appContext.filesDir, "echo-library-artwork-v1")
    private val rawDir = File(root, "raw").apply { mkdirs() }
    private val coverDir = File(root, "covers").apply { mkdirs() }

    fun cachedCover(game: GameEntry): File? =
        coverFile(game).takeIf { it.isFile && it.length() > 0L }

    fun clear() {
        root.deleteRecursively()
        rawDir.mkdirs()
        coverDir.mkdirs()
    }

    suspend fun syncCovers(
        snapshot: LibrarySnapshot,
        onProgress: (ArtworkSyncProgress) -> Unit = {},
    ): ArtworkSyncResult = withContext(Dispatchers.IO) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        try {
            syncCoversWithSession(
                session = routed.session,
                snapshot = snapshot,
                route = routed.route,
                onProgress = onProgress,
            )
        } finally {
            runCatching { routed.session.close() }
        }
    }

    internal suspend fun syncCoversWithSession(
        session: XboxFtpSession,
        snapshot: LibrarySnapshot,
        route: FtpRoute,
        onProgress: (ArtworkSyncProgress) -> Unit = {},
    ): ArtworkSyncResult {
        val games = snapshot.games
            .distinctBy { AuroraArtworkPaths.cacheStem(it) }
            .sortedBy { it.title.lowercase() }

        var downloaded = 0
        var cached = 0
        var unavailable = 0
        var failed = 0

        games.forEachIndexed { index, game ->
            coroutineContext.ensureActive()
            onProgress(
                ArtworkSyncProgress(
                    processed = index,
                    total = games.size,
                    currentTitle = game.title,
                    route = route,
                    downloaded = downloaded,
                    cached = cached,
                    unavailable = unavailable,
                    failed = failed,
                    message = "Verificando capa de ${game.title}.",
                ),
            )

            val remotePath = AuroraArtworkPaths.coverAsset(snapshot.auroraRoot, game)
            val remoteBytes = runCatching { session.size(remotePath) }.getOrNull()
            if (remoteBytes == null || remoteBytes <= 0L) {
                unavailable += 1
                return@forEachIndexed
            }

            val rawFile = rawFile(game)
            val pngFile = coverFile(game)
            if (rawFile.isFile && rawFile.length() == remoteBytes && pngFile.isFile && pngFile.length() > 0L) {
                cached += 1
                return@forEachIndexed
            }

            val outcome = runCatching {
                val temp = File(rawDir, "${AuroraArtworkPaths.cacheStem(game)}.tmp")
                if (temp.exists()) temp.delete()
                FileOutputStream(temp).use { output ->
                    session.download(remotePath, output)
                }
                require(temp.length() == remoteBytes) {
                    "Capa incompleta: ${temp.length()} de $remoteBytes bytes."
                }

                val decoded = RxeaDecoder.decodeBoxArt(temp.readBytes())
                    ?: error("GC asset não contém slot BOXART.")
                persistPng(decoded, pngFile)

                if (rawFile.exists()) rawFile.delete()
                require(temp.renameTo(rawFile)) { "Não foi possível promover o cache RXEA." }
            }

            if (outcome.isSuccess) downloaded += 1 else failed += 1
        }

        val result = ArtworkSyncResult(
            total = games.size,
            downloaded = downloaded,
            cached = cached,
            unavailable = unavailable,
            failed = failed,
            route = route,
        )
        onProgress(
            ArtworkSyncProgress(
                processed = games.size,
                total = games.size,
                route = route,
                downloaded = downloaded,
                cached = cached,
                unavailable = unavailable,
                failed = failed,
                message = "Capas: $downloaded novas, $cached em cache, $unavailable ausentes, $failed falharam.",
            ),
        )
        return result
    }

    private fun persistPng(decoded: DecodedRxeaImage, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        if (temp.exists()) temp.delete()

        val bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(decoded.argb, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            FileOutputStream(temp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Android não conseguiu codificar a capa em PNG."
                }
            }
        } finally {
            bitmap.recycle()
        }

        if (destination.exists()) destination.delete()
        require(temp.renameTo(destination)) { "Não foi possível promover a capa PNG para o cache." }
    }

    private fun rawFile(game: GameEntry): File =
        File(rawDir, "${AuroraArtworkPaths.cacheStem(game)}.asset")

    private fun coverFile(game: GameEntry): File =
        File(coverDir, "${AuroraArtworkPaths.cacheStem(game)}.png")
}

data class ArtworkSyncProgress(
    val processed: Int,
    val total: Int,
    val currentTitle: String? = null,
    val route: FtpRoute? = null,
    val downloaded: Int = 0,
    val cached: Int = 0,
    val unavailable: Int = 0,
    val failed: Int = 0,
    val message: String,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

data class ArtworkSyncResult(
    val total: Int,
    val downloaded: Int,
    val cached: Int,
    val unavailable: Int,
    val failed: Int,
    val route: FtpRoute,
)
