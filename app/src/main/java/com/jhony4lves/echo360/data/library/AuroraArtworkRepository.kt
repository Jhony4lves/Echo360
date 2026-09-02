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
    private val backgroundRawDir = File(root, "background-raw").apply { mkdirs() }
    private val backgroundDir = File(root, "backgrounds").apply { mkdirs() }

    fun cachedCover(game: GameEntry): File? =
        coverFile(game).takeIf { it.isFile && it.length() > 0L }

    fun cachedBackground(game: GameEntry): File? =
        backgroundFile(game).takeIf { it.isFile && it.length() > 0L }

    fun clear() {
        root.deleteRecursively()
        rawDir.mkdirs()
        coverDir.mkdirs()
        backgroundRawDir.mkdirs()
        backgroundDir.mkdirs()
    }

    suspend fun syncCovers(
        snapshot: LibrarySnapshot,
        onProgress: (ArtworkSyncProgress) -> Unit = {},
    ): ArtworkSyncResult = withContext(Dispatchers.IO) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        val session = routed.session
        try {
            syncCoversWithSession(
                session = session,
                snapshot = snapshot,
                route = routed.route,
                onProgress = onProgress,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Fetches the selected game's Aurora background only when the detail view asks
     * for it. This intentionally avoids bulk-downloading BK assets for a large
     * collection. Existing valid cache is reused when the remote SIZE is unchanged.
     */
    suspend fun fetchBackground(
        snapshot: LibrarySnapshot,
        game: GameEntry,
    ): ArtworkFetchResult = withContext(Dispatchers.IO) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        val session = routed.session
        try {
            fetchBackgroundWithSession(
                session = session,
                snapshot = snapshot,
                game = game,
                route = routed.route,
            )
        } finally {
            runCatching { session.close() }
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

    internal suspend fun fetchBackgroundWithSession(
        session: XboxFtpSession,
        snapshot: LibrarySnapshot,
        game: GameEntry,
        route: FtpRoute,
    ): ArtworkFetchResult {
        coroutineContext.ensureActive()
        val remotePath = AuroraArtworkPaths.backgroundAsset(snapshot.auroraRoot, game)
        val remoteBytes = runCatching { session.size(remotePath) }.getOrNull()
            ?: return ArtworkFetchResult(
                status = ArtworkFetchStatus.Unavailable,
                file = null,
                remoteBytes = null,
                route = route,
                message = "Aurora não informou tamanho para o background de ${game.title}.",
            )

        if (remoteBytes <= 0L) {
            return ArtworkFetchResult(
                status = ArtworkFetchStatus.Unavailable,
                file = null,
                remoteBytes = remoteBytes,
                route = route,
                message = "Background não disponível no Aurora para ${game.title}.",
            )
        }

        if (remoteBytes > MAX_SINGLE_BACKGROUND_BYTES) {
            return ArtworkFetchResult(
                status = ArtworkFetchStatus.Failed,
                file = null,
                remoteBytes = remoteBytes,
                route = route,
                message = "Background recusado por segurança: $remoteBytes bytes excedem o limite local.",
            )
        }

        val rawFile = backgroundRawFile(game)
        val pngFile = backgroundFile(game)
        if (rawFile.isFile && rawFile.length() == remoteBytes && pngFile.isFile && pngFile.length() > 0L) {
            return ArtworkFetchResult(
                status = ArtworkFetchStatus.Cached,
                file = pngFile,
                remoteBytes = remoteBytes,
                route = route,
                message = "Background carregado do cache local.",
            )
        }

        val outcome = runCatching {
            val temp = File(backgroundRawDir, "${AuroraArtworkPaths.cacheStem(game)}.tmp")
            if (temp.exists()) temp.delete()
            FileOutputStream(temp).use { output ->
                session.download(remotePath, output)
            }
            require(temp.length() == remoteBytes) {
                "Background incompleto: ${temp.length()} de $remoteBytes bytes."
            }

            val decoded = RxeaDecoder.decodeBackground(temp.readBytes())
                ?: error("BK asset não contém slot BACKGROUND.")
            persistPng(decoded, pngFile)

            if (rawFile.exists()) rawFile.delete()
            require(temp.renameTo(rawFile)) { "Não foi possível promover o cache RXEA do background." }
            pngFile
        }

        return outcome.fold(
            onSuccess = { file ->
                ArtworkFetchResult(
                    status = ArtworkFetchStatus.Downloaded,
                    file = file,
                    remoteBytes = remoteBytes,
                    route = route,
                    message = "Background de ${game.title} salvo no cache local.",
                )
            },
            onFailure = { error ->
                ArtworkFetchResult(
                    status = ArtworkFetchStatus.Failed,
                    file = null,
                    remoteBytes = remoteBytes,
                    route = route,
                    message = error.message ?: "Falha ao decodificar o background do Aurora.",
                )
            },
        )
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
                    "Android não conseguiu codificar o artwork em PNG."
                }
            }
        } finally {
            bitmap.recycle()
        }

        if (destination.exists()) destination.delete()
        require(temp.renameTo(destination)) { "Não foi possível promover o artwork PNG para o cache." }
    }

    private fun rawFile(game: GameEntry): File =
        File(rawDir, "${AuroraArtworkPaths.cacheStem(game)}.asset")

    private fun coverFile(game: GameEntry): File =
        File(coverDir, "${AuroraArtworkPaths.cacheStem(game)}.png")

    private fun backgroundRawFile(game: GameEntry): File =
        File(backgroundRawDir, "${AuroraArtworkPaths.cacheStem(game)}.asset")

    private fun backgroundFile(game: GameEntry): File =
        File(backgroundDir, "${AuroraArtworkPaths.cacheStem(game)}.png")

    companion object {
        internal const val MAX_SINGLE_BACKGROUND_BYTES = 32L * 1024L * 1024L
    }
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

enum class ArtworkFetchStatus {
    Cached,
    Downloaded,
    Unavailable,
    Failed,
}

data class ArtworkFetchResult(
    val status: ArtworkFetchStatus,
    val file: File?,
    val remoteBytes: Long?,
    val route: FtpRoute,
    val message: String,
) {
    val available: Boolean
        get() = (status == ArtworkFetchStatus.Cached || status == ArtworkFetchStatus.Downloaded) &&
            file?.isFile == true && file.length() > 0L
}
