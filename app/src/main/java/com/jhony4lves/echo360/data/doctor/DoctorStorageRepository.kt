package com.jhony4lves.echo360.data.doctor

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.doctor.DoctorStorageAnalyzer
import com.jhony4lves.echo360.domain.doctor.DoctorStorageEntry
import com.jhony4lves.echo360.domain.doctor.DoctorStorageMount
import com.jhony4lves.echo360.domain.doctor.DoctorStorageObjectType
import com.jhony4lves.echo360.domain.doctor.DoctorStorageOrigin
import com.jhony4lves.echo360.domain.doctor.DoctorStorageReport
import com.jhony4lves.echo360.domain.doctor.DoctorStorageSnapshot
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.RemoteEntry
import com.jhony4lves.echo360.network.ftp.XboxFtpSession
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Compatibility source for Doctor storage visibility while the EchoCore
 * FILE_STAT / DIR_LIST contract remains hardware-gated.
 *
 * The probe is intentionally bounded and read-only:
 * - root LIST once;
 * - deep inspection only for /Hdd1 in v1, matching the current EchoCore path policy draft;
 * - max 256 entries retained per directory;
 * - no recursion;
 * - no capacity/free-space inference.
 */
class DoctorStorageRepository(
    context: Context,
    private val ftpSessionFactory: XboxFtpSessionFactory = XboxFtpSessionFactory(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)
    private val analyzer = DoctorStorageAnalyzer()

    suspend fun inspect(): DoctorStorageReport = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de ler o armazenamento.")

        val fast = runCatching { inspectRoute(profile, FtpRoute.Fast) }
        val snapshot = fast.getOrElse { fastError ->
            if (fastError is CancellationException) throw fastError
            val background = runCatching { inspectRoute(profile, FtpRoute.Background) }
            background.getOrElse { backgroundError ->
                if (backgroundError is CancellationException) throw backgroundError
                DoctorStorageSnapshot(
                    origin = DoctorStorageOrigin.Unavailable,
                    mounts = emptyList(),
                    rootEntryCount = 0,
                    rootLimitReached = false,
                    unavailableDetail = buildString {
                        append("Aurora FTP: ")
                        append(safeError(fastError))
                        append(" • FTPdll: ")
                        append(safeError(backgroundError))
                    },
                    checkedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }

        DoctorStorageReport(
            snapshot = snapshot,
            findings = analyzer.analyze(snapshot),
        )
    }

    private suspend fun inspectRoute(
        profile: com.jhony4lves.echo360.domain.xbox.XboxProfile,
        route: FtpRoute,
    ): DoctorStorageSnapshot {
        var session: XboxFtpSession? = null
        try {
            val routed = ftpSessionFactory.connect(profile, route)
            session = routed.session

            val rawRoot = routed.session.list("/")
            val rootLimitReached = rawRoot.size > MAX_ENTRIES
            val root = rawRoot.take(MAX_ENTRIES)
            val hdd = root.firstOrNull {
                it.name.equals(HDD_ROOT_NAME, ignoreCase = true) ||
                    it.canonicalPath.equals(HDD_CANONICAL_ROOT, ignoreCase = true)
            }

            val mounts = if (hdd == null) {
                emptyList()
            } else {
                listOf(inspectHddMount(routed.session, hdd))
            }

            return DoctorStorageSnapshot(
                origin = when (routed.route) {
                    FtpRoute.Fast -> DoctorStorageOrigin.AuroraFtpCompatibility
                    FtpRoute.Background -> DoctorStorageOrigin.FtpDllCompatibility
                    FtpRoute.Auto -> error("A rota Auto deve estar resolvida antes do snapshot.")
                },
                mounts = mounts,
                rootEntryCount = rawRoot.size,
                rootLimitReached = rootLimitReached,
                checkedAtEpochMs = System.currentTimeMillis(),
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.close() }
            }
        }
    }

    private suspend fun inspectHddMount(
        session: XboxFtpSession,
        rootEntry: RemoteEntry,
    ): DoctorStorageMount {
        val rootType = rootEntry.toObjectType()
        if (!rootEntry.isDirectory) {
            return DoctorStorageMount(
                canonicalRoot = HDD_CANONICAL_ROOT,
                observedName = rootEntry.name,
                objectType = rootType,
                entries = emptyList(),
                limitReached = false,
                listingUnavailableDetail = "Hdd1 apareceu na raiz, mas não como diretório.",
            )
        }

        return try {
            val rawEntries = session.list(HDD_CANONICAL_ROOT)
            DoctorStorageMount(
                canonicalRoot = HDD_CANONICAL_ROOT,
                observedName = rootEntry.name,
                objectType = rootType,
                entries = rawEntries.take(MAX_ENTRIES).map(::toDoctorEntry),
                limitReached = rawEntries.size > MAX_ENTRIES,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            DoctorStorageMount(
                canonicalRoot = HDD_CANONICAL_ROOT,
                observedName = rootEntry.name,
                objectType = rootType,
                entries = emptyList(),
                limitReached = false,
                listingUnavailableDetail = safeError(error),
            )
        }
    }

    private fun toDoctorEntry(entry: RemoteEntry) = DoctorStorageEntry(
        name = entry.name,
        canonicalPath = entry.canonicalPath,
        objectType = entry.toObjectType(),
        sizeBytes = entry.size,
    )

    private fun RemoteEntry.toObjectType(): DoctorStorageObjectType =
        if (isDirectory) DoctorStorageObjectType.Directory else DoctorStorageObjectType.File

    private fun safeError(error: Throwable): String =
        error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(220)?.ifBlank { null }
            ?: error::class.simpleName.orEmpty().ifBlank { "fonte indisponível" }

    companion object {
        private const val MAX_ENTRIES = 256
        private const val HDD_ROOT_NAME = "Hdd1"
        private const val HDD_CANONICAL_ROOT = "/Hdd1"
    }
}
