package com.jhony4lves.echo360.data.convert

import android.content.Context
import android.os.StatFs
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class EchoConvertRepository(
    context: Context,
    private val sessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)

    suspend fun repairDarkSouls2ScholarDisc2(
        requestedRoute: FtpRoute = FtpRoute.Background,
        onProgress: (EchoConvertProgress) -> Unit = {},
    ): EchoConvertResult = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure a conexão com o Xbox na aba Xbox antes de usar o EchoConvert.")

        val workDir = File(appContext.cacheDir, "echo_convert/ds2_sotfs").apply {
            deleteRecursively()
            mkdirs()
        }

        var routeUsed = requestedRoute
        var installerHeader: InstallerCandidate? = null
        var packageFile: File? = null

        try {
            onProgress(
                EchoConvertProgress(
                    stage = EchoConvertStage.Detecting,
                    message = "Procurando o DVD2 / Expansion Installer no Xbox…",
                    route = requestedRoute,
                ),
            )

            val downloadSession = sessionFactory.connect(profile, requestedRoute)
            routeUsed = downloadSession.route
            installerHeader = useSession(downloadSession.session) { session ->
                val candidate = locateDarkSouls2Installer(session, workDir)
                val header = candidate.header
                val partSizes = (0 until header.partCount).map { index ->
                    val path = "${candidate.remoteHeaderPath}.data/Data%04d".format(index)
                    session.size(path) ?: error("Não consegui obter o tamanho de $path.")
                }
                val remoteHeaderSize = session.size(candidate.remoteHeaderPath)
                    ?: candidate.localHeader.length()
                val totalDownload = remoteHeaderSize + partSizes.sum()
                ensureSpace(workDir, totalDownload + MIN_EXTRACTION_HEADROOM)

                var completed = candidate.localHeader.length()
                onProgress(
                    EchoConvertProgress(
                        stage = EchoConvertStage.Downloading,
                        message = "DVD2 detectado. Copiando GoD do Xbox para o celular…",
                        currentBytes = completed,
                        totalBytes = totalDownload,
                        route = routeUsed,
                    ),
                )

                val dataDir = File(candidate.localHeader.absolutePath + ".data").apply { mkdirs() }
                for (index in 0 until header.partCount) {
                    val remotePath = "${candidate.remoteHeaderPath}.data/Data%04d".format(index)
                    val localPart = File(dataDir, "Data%04d".format(index))
                    val before = completed
                    FileOutputStream(localPart).use { output ->
                        session.download(remotePath, output) { fileBytes ->
                            onProgress(
                                EchoConvertProgress(
                                    stage = EchoConvertStage.Downloading,
                                    message = "Baixando Data%04d (%d/%d)…".format(
                                        index,
                                        index + 1,
                                        header.partCount,
                                    ),
                                    currentBytes = before + fileBytes,
                                    totalBytes = totalDownload,
                                    route = routeUsed,
                                ),
                            )
                        }
                    }
                    require(localPart.length() == partSizes[index]) {
                        "Data%04d chegou incompleto: esperado=%d recebido=%d".format(
                            index,
                            partSizes[index],
                            localPart.length(),
                        )
                    }
                    completed += localPart.length()
                }
                candidate
            }

            val candidate = checkNotNull(installerHeader)
            onProgress(
                EchoConvertProgress(
                    stage = EchoConvertStage.Verifying,
                    message = "Validando hash tree do GoD e abrindo XDVDFS…",
                    route = routeUsed,
                ),
            )

            packageFile = File(workDir, DS2_PACKAGE_NAME)
            GodVirtualStream(candidate.localHeader).use { god ->
                require(god.header.mediaId == DS2_DISC2_MEDIA_ID) {
                    "O GoD encontrado não é o DVD2 esperado. Media ID=${god.header.mediaId.hex8()}"
                }
                val xdvdfs = XdvdfsReader(god)
                val content = xdvdfs.find(DS2_DISC_PACKAGE_PATH)
                require(!content.isDirectory) { "O pacote do Dark Souls II foi encontrado como pasta." }
                ensureSpace(workDir, content.size + MIN_UPLOAD_HEADROOM)

                onProgress(
                    EchoConvertProgress(
                        stage = EchoConvertStage.Extracting,
                        message = "Extraindo somente o Compatibility Pack 4 — sem criar ISO…",
                        currentBytes = 0L,
                        totalBytes = content.size,
                        route = routeUsed,
                    ),
                )

                xdvdfs.extractFile(DS2_DISC_PACKAGE_PATH, checkNotNull(packageFile)) { current, total ->
                    onProgress(
                        EchoConvertProgress(
                            stage = EchoConvertStage.Extracting,
                            message = "Extraindo ${DS2_PACKAGE_NAME.take(12)}…",
                            currentBytes = current,
                            totalBytes = total,
                            route = routeUsed,
                        ),
                    )
                }
            }

            // The source remains untouched on the Xbox. Once the DLC package is safely
            // extracted locally, free the temporary GoD copy before uploading it back.
            candidate.localHeader.delete()
            File(candidate.localHeader.absolutePath + ".data").deleteRecursively()

            val localPackage = checkNotNull(packageFile)
            require(localPackage.isFile && localPackage.length() > 0L) {
                "A extração terminou sem produzir o Compatibility Pack."
            }

            val uploadSession = sessionFactory.connect(profile, requestedRoute)
            routeUsed = uploadSession.route
            useSession(uploadSession.session) { session ->
                val existing = session.size(DS2_DESTINATION_PATH)
                if (existing == localPackage.length()) {
                    onProgress(
                        EchoConvertProgress(
                            stage = EchoConvertStage.Completed,
                            message = "Compatibility Pack já estava instalado com o tamanho correto.",
                            currentBytes = existing,
                            totalBytes = existing,
                            route = routeUsed,
                        ),
                    )
                    return@useSession
                }

                onProgress(
                    EchoConvertProgress(
                        stage = EchoConvertStage.Uploading,
                        message = "Instalando Compatibility Pack 4 no caminho correto…",
                        currentBytes = 0L,
                        totalBytes = localPackage.length(),
                        route = routeUsed,
                    ),
                )

                FileInputStream(localPackage).use { input ->
                    session.upload(DS2_DESTINATION_PATH, input) { uploaded ->
                        onProgress(
                            EchoConvertProgress(
                                stage = EchoConvertStage.Uploading,
                                message = "Enviando DLC para 465307E4/00000002…",
                                currentBytes = uploaded,
                                totalBytes = localPackage.length(),
                                route = routeUsed,
                            ),
                        )
                    }
                }

                onProgress(
                    EchoConvertProgress(
                        stage = EchoConvertStage.Verifying,
                        message = "Conferindo o arquivo instalado no Xbox…",
                        route = routeUsed,
                    ),
                )
                val remoteSize = session.size(DS2_DESTINATION_PATH)
                require(remoteSize == localPackage.length()) {
                    "Falha na verificação final: local=${localPackage.length()} remoto=$remoteSize."
                }
            }

            val installedBytes = localPackage.length()
            onProgress(
                EchoConvertProgress(
                    stage = EchoConvertStage.Completed,
                    message = "Pronto. Compatibility Pack 4 instalado e verificado.",
                    currentBytes = installedBytes,
                    totalBytes = installedBytes,
                    route = routeUsed,
                ),
            )

            EchoConvertResult(
                success = true,
                route = routeUsed,
                installedPath = DS2_DESTINATION_PATH,
                installedBytes = installedBytes,
                message = "Dark Souls II: Scholar of the First Sin está com o DVD2 instalado no caminho correto.",
            )
        } catch (error: Throwable) {
            onProgress(
                EchoConvertProgress(
                    stage = EchoConvertStage.Failed,
                    message = error.message ?: "Falha desconhecida no EchoConvert.",
                    route = routeUsed,
                ),
            )
            EchoConvertResult(
                success = false,
                route = routeUsed,
                installedPath = DS2_DESTINATION_PATH,
                installedBytes = packageFile?.takeIf(File::isFile)?.length() ?: 0L,
                message = error.message ?: "Falha desconhecida no EchoConvert.",
            )
        } finally {
            // On success there is no reason to keep a >1 GB cache. On failure keep
            // partial downloads only for this invocation; a retry starts clean.
            workDir.deleteRecursively()
        }
    }

    private suspend fun locateDarkSouls2Installer(
        session: XboxFtpSession,
        workDir: File,
    ): InstallerCandidate {
        val entries = session.list(INSTALLER_GOD_DIRECTORY)
        val directories = entries.filter(RemoteEntry::isDirectory).associateBy { it.name.lowercase(Locale.ROOT) }
        val possibleHeaders = entries
            .filterNot(RemoteEntry::isDirectory)
            .filter { it.size in 0xB000L..128L * 1024L }
            .filter { directories.containsKey((it.name + ".data").lowercase(Locale.ROOT)) }

        require(possibleHeaders.isNotEmpty()) {
            "Nenhum Expansion Installer GoD foi encontrado em $INSTALLER_GOD_DIRECTORY."
        }

        var fallback: InstallerCandidate? = null
        for (remote in possibleHeaders) {
            val local = File(workDir, remote.name)
            FileOutputStream(local).use { output -> session.download(remote.canonicalPath, output) }
            val header = runCatching { GodHeaderParser.parse(local) }.getOrNull()
            if (header == null) {
                local.delete()
                continue
            }

            val candidate = InstallerCandidate(remote.canonicalPath, local, header)
            if (remote.name.equals(KNOWN_GODSTIX_DS2_HEADER, ignoreCase = true)) fallback = candidate
            if (header.mediaId == DS2_DISC2_MEDIA_ID) return candidate

            if (fallback?.localHeader != local) local.delete()
        }

        return fallback ?: error(
            "Encontrei Expansion Installers, mas nenhum tem o Media ID do DVD2 do Dark Souls II (${DS2_DISC2_MEDIA_ID.hex8()}).",
        )
    }

    private fun ensureSpace(directory: File, requiredBytes: Long) {
        val available = StatFs(directory.absolutePath).availableBytes
        require(available >= requiredBytes) {
            "Espaço insuficiente no celular. Necessário ${requiredBytes.asGiB()}, disponível ${available.asGiB()}."
        }
    }

    private suspend fun <T> useSession(
        session: XboxFtpSession,
        block: suspend (XboxFtpSession) -> T,
    ): T = try {
        block(session)
    } finally {
        runCatching { session.close() }
    }

    private data class InstallerCandidate(
        val remoteHeaderPath: String,
        val localHeader: File,
        val header: GodHeader,
    )

    companion object {
        private const val INSTALLER_GOD_DIRECTORY =
            "/Hdd1/Content/0000000000000000/FFED2000/00007000"
        private const val KNOWN_GODSTIX_DS2_HEADER = "C0B2D76914692611985C"
        private const val DS2_DISC2_MEDIA_ID = 0x0C94D453L
        private const val DS2_PACKAGE_NAME = "D4B91B6B4DA1509C280F56F77B09203DE7D39AE646"
        private const val DS2_DISC_PACKAGE_PATH =
            "content/0000000000000000/FFED2000/FFFFFFFF/$DS2_PACKAGE_NAME"
        private const val DS2_DESTINATION_PATH =
            "/Hdd1/Content/0000000000000000/465307E4/00000002/$DS2_PACKAGE_NAME"
        private const val MIN_EXTRACTION_HEADROOM = 1536L * 1024L * 1024L
        private const val MIN_UPLOAD_HEADROOM = 256L * 1024L * 1024L
    }
}

enum class EchoConvertStage {
    Idle,
    Detecting,
    Downloading,
    Verifying,
    Extracting,
    Uploading,
    Completed,
    Failed,
}

data class EchoConvertProgress(
    val stage: EchoConvertStage = EchoConvertStage.Idle,
    val message: String = "Pronto para analisar.",
    val currentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val route: FtpRoute = FtpRoute.Background,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) {
            (currentBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            null
        }
}

data class EchoConvertResult(
    val success: Boolean,
    val route: FtpRoute,
    val installedPath: String,
    val installedBytes: Long,
    val message: String,
)

private fun Long.hex8(): String = String.format(Locale.ROOT, "%08X", this)

private fun Long.asGiB(): String = String.format(Locale.ROOT, "%.2f GiB", this / 1024.0 / 1024.0 / 1024.0)
