package com.jhony4lves.echo360.data.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LibrarySnapshot
import com.jhony4lves.echo360.domain.library.NowPlaying
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AuroraLibraryRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val prefs = appContext.getSharedPreferences("echo_library", Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.cacheDir, "echo-library").apply { mkdirs() }
    private val cachedDatabase = File(cacheDir, "content.db")

    suspend fun nowPlaying(): NowPlaying {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        return novaClient.nowPlaying(profile)
    }

    suspend fun sync(
        onProgress: (LibrarySyncProgress) -> Unit = {},
    ): LibrarySnapshot = withContext(Dispatchers.IO) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        onProgress(LibrarySyncProgress(LibrarySyncStage.Connecting, message = "Conectando ao Xbox."))

        val routed = sessionFactory.connect(profile, FtpRoute.Auto)
        val session = routed.session
        try {
            onProgress(LibrarySyncProgress(LibrarySyncStage.LocatingAurora, message = "Localizando a instalação do Aurora."))
            val auroraRoot = locateAurora(session)
                ?: error("Não encontrei Aurora.xex + Data/Databases/content.db nas unidades montadas.")
            val remoteDatabase = "$auroraRoot/Data/Databases/content.db"
            val expectedBytes = session.size(remoteDatabase)
                ?: error("O content.db do Aurora não respondeu a SIZE.")

            onProgress(
                LibrarySyncProgress(
                    stage = LibrarySyncStage.DownloadingDatabase,
                    totalBytes = expectedBytes,
                    message = "Copiando snapshot somente-leitura do catálogo.",
                ),
            )

            val temp = File(cacheDir, "content.db.tmp")
            if (temp.exists()) temp.delete()
            FileOutputStream(temp).use { output ->
                session.download(remoteDatabase, output) { downloaded ->
                    onProgress(
                        LibrarySyncProgress(
                            stage = LibrarySyncStage.DownloadingDatabase,
                            transferredBytes = downloaded,
                            totalBytes = expectedBytes,
                            message = "Copiando content.db.",
                        ),
                    )
                }
            }

            require(temp.length() == expectedBytes) {
                "Snapshot incompleto: ${temp.length()} de $expectedBytes bytes."
            }
            if (cachedDatabase.exists()) cachedDatabase.delete()
            require(temp.renameTo(cachedDatabase)) { "Não foi possível promover o snapshot do catálogo." }

            onProgress(LibrarySyncProgress(LibrarySyncStage.ReadingCatalog, message = "Lendo ContentItems."))
            val games = readGames(cachedDatabase)
            prefs.edit()
                .putString("aurora_root", auroraRoot)
                .putString("database_path", remoteDatabase)
                .apply()

            onProgress(
                LibrarySyncProgress(
                    stage = LibrarySyncStage.Completed,
                    transferredBytes = expectedBytes,
                    totalBytes = expectedBytes,
                    gameCount = games.size,
                    message = "${games.size} item(ns) carregados do Aurora.",
                ),
            )
            LibrarySnapshot(
                games = games,
                auroraRoot = auroraRoot,
                databaseRemotePath = remoteDatabase,
                databaseBytes = expectedBytes,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    suspend fun loadCached(): LibrarySnapshot? = withContext(Dispatchers.IO) {
        if (!cachedDatabase.exists() || cachedDatabase.length() == 0L) return@withContext null
        val auroraRoot = prefs.getString("aurora_root", null) ?: return@withContext null
        val remote = prefs.getString("database_path", null) ?: "$auroraRoot/Data/Databases/content.db"
        runCatching {
            LibrarySnapshot(
                games = readGames(cachedDatabase),
                auroraRoot = auroraRoot,
                databaseRemotePath = remote,
                databaseBytes = cachedDatabase.length(),
            )
        }.getOrNull()
    }

    private suspend fun locateAurora(session: XboxFtpSession): String? {
        val remembered = prefs.getString("aurora_root", null)?.let(XboxPath::canonical)
        if (remembered != null && isAuroraRoot(session, remembered)) return remembered

        val rootEntries = session.list("/").filter(RemoteEntry::isDirectory)
        val drives = rootEntries
            .map { XboxPath.canonical(it.canonicalPath) }
            .filter { path ->
                val drive = path.removePrefix("/").substringBefore('/')
                drive.equals("Hdd1", true) || drive.startsWith("Usb", true) || drive.equals("OnBoardMU", true)
            }
            .distinct()

        val queue = ArrayDeque<SearchNode>()
        drives.forEach { queue.add(SearchNode(it, 0)) }
        var inspected = 0

        while (queue.isNotEmpty() && inspected < 100) {
            val node = queue.removeFirst()
            inspected += 1
            if (isAuroraRoot(session, node.path)) return node.path
            if (node.depth >= 2) continue

            val children = runCatching { session.list(node.path) }
                .getOrDefault(emptyList())
                .asSequence()
                .filter(RemoteEntry::isDirectory)
                .filterNot { it.name.startsWith('.') }
                .sortedByDescending { child -> auroraNameScore(child.name) }
                .take(40)
                .toList()

            children.forEach { child ->
                queue.add(SearchNode(XboxPath.canonical(child.canonicalPath), node.depth + 1))
            }
        }
        return null
    }

    private suspend fun isAuroraRoot(session: XboxFtpSession, path: String): Boolean {
        val canonical = XboxPath.canonical(path)
        val xex = runCatching { session.size("$canonical/Aurora.xex") }.getOrNull()
        if (xex == null || xex <= 0L) return false
        val database = runCatching { session.size("$canonical/Data/Databases/content.db") }.getOrNull()
        return database != null && database > 0L
    }

    private fun auroraNameScore(name: String): Int = when {
        name.contains("aurora", ignoreCase = true) -> 100
        name.equals("apps", ignoreCase = true) -> 50
        name.equals("app", ignoreCase = true) -> 45
        name.equals("homebrew", ignoreCase = true) -> 40
        else -> 0
    }

    private fun readGames(databaseFile: File): List<GameEntry> {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            val sql = """
                SELECT DISTINCT
                    I.Id,
                    I.TitleId,
                    I.MediaId,
                    I.DiscNum,
                    I.TitleName,
                    I.Directory,
                    I.Executable,
                    I.BaseVersion,
                    M.MountPoint
                FROM ContentItems I
                LEFT JOIN ScanPaths S ON I.ScanPathId = S.Id
                LEFT JOIN MountedDevices M ON S.DeviceId = M.DeviceId
                WHERE I.TitleName IS NOT NULL
                  AND I.Executable IS NOT NULL
                  AND LENGTH(TRIM(I.TitleName)) > 0
                  AND LENGTH(TRIM(I.Executable)) > 0
                ORDER BY I.TitleName COLLATE NOCASE, I.DiscNum
            """.trimIndent()

            database.rawQuery(sql, null).use { cursor ->
                buildList {
                    val idCol = cursor.getColumnIndexOrThrow("Id")
                    val titleIdCol = cursor.getColumnIndexOrThrow("TitleId")
                    val mediaIdCol = cursor.getColumnIndexOrThrow("MediaId")
                    val discCol = cursor.getColumnIndexOrThrow("DiscNum")
                    val nameCol = cursor.getColumnIndexOrThrow("TitleName")
                    val directoryCol = cursor.getColumnIndexOrThrow("Directory")
                    val executableCol = cursor.getColumnIndexOrThrow("Executable")
                    val baseVersionCol = cursor.getColumnIndexOrThrow("BaseVersion")
                    val mountCol = cursor.getColumnIndexOrThrow("MountPoint")

                    while (cursor.moveToNext()) {
                        val rawMount = cursor.getStringOrNull(mountCol)
                        val rawDirectory = cursor.getString(directoryCol).orEmpty()
                        val inferredRoot = canonicalMount(rawMount, rawDirectory)
                        add(
                            GameEntry(
                                databaseId = cursor.getLong(idCol),
                                titleId = unsigned32(cursor.getLong(titleIdCol)),
                                mediaId = unsigned32(cursor.getLong(mediaIdCol)),
                                discNumber = cursor.getInt(discCol).coerceAtLeast(1),
                                title = cursor.getString(nameCol).orEmpty().trim(),
                                directory = stripDrivePrefix(rawDirectory),
                                executable = cursor.getString(executableCol).orEmpty().trim(),
                                baseVersion = cursor.getStringOrNull(baseVersionCol),
                                contentRoot = inferredRoot,
                            ),
                        )
                    }
                }
            }
        } finally {
            database.close()
        }
    }
}

private fun canonicalMount(mountPoint: String?, directory: String): String? {
    if (!mountPoint.isNullOrBlank()) return XboxPath.canonical(mountPoint)
    val normalized = directory.replace('\\', '/')
    val first = normalized.trimStart('/').substringBefore('/')
    return if (first.endsWith(':')) XboxPath.canonical(first) else null
}

private fun stripDrivePrefix(directory: String): String {
    val normalized = directory.replace('\\', '/').trim()
    val clean = normalized.trimStart('/')
    val first = clean.substringBefore('/')
    return if (first.endsWith(':')) clean.substringAfter('/', "") else clean
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun unsigned32(value: Long): Long = value and 0xFFFF_FFFFL

private data class SearchNode(
    val path: String,
    val depth: Int,
)

enum class LibrarySyncStage {
    Connecting,
    LocatingAurora,
    DownloadingDatabase,
    ReadingCatalog,
    Completed,
}

data class LibrarySyncProgress(
    val stage: LibrarySyncStage,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val gameCount: Int = 0,
    val message: String,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (transferredBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}
