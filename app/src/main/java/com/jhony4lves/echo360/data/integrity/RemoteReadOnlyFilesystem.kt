package com.jhony4lves.echo360.data.integrity

import com.jhony4lves.echo360.network.ftp.XboxFtpSession

enum class RemoteObjectType {
    File,
    Directory,
}

data class RemoteObjectStat(
    val canonicalPath: String,
    val objectType: RemoteObjectType,
    val sizeBytes: Long,
)

data class RemoteDirectoryEntry(
    val name: String,
    val canonicalPath: String,
    val objectType: RemoteObjectType,
    val sizeBytes: Long,
)

data class RemoteDirectoryListing(
    val entries: List<RemoteDirectoryEntry>,
    val limitReached: Boolean = false,
)

/**
 * Read-only filesystem contract consumed by EchoIntegrity.
 *
 * FTP is the compatibility adapter today. Future EchoCore FILE_STAT / DIR_LIST
 * can implement the same contract after the Xbox-side draft is promoted.
 */
internal interface RemoteReadOnlyFilesystem {
    suspend fun list(canonicalDirectory: String): RemoteDirectoryListing
    suspend fun stat(canonicalPath: String): RemoteObjectStat?
}

internal class FtpReadOnlyFilesystem(
    private val session: XboxFtpSession,
) : RemoteReadOnlyFilesystem {
    override suspend fun list(canonicalDirectory: String): RemoteDirectoryListing =
        RemoteDirectoryListing(
            entries = session.list(canonicalDirectory).map { entry ->
                RemoteDirectoryEntry(
                    name = entry.name,
                    canonicalPath = entry.canonicalPath,
                    objectType = if (entry.isDirectory) RemoteObjectType.Directory else RemoteObjectType.File,
                    sizeBytes = entry.size,
                )
            },
            limitReached = false,
        )

    override suspend fun stat(canonicalPath: String): RemoteObjectStat? =
        session.size(canonicalPath)?.let { size ->
            RemoteObjectStat(
                canonicalPath = canonicalPath,
                objectType = RemoteObjectType.File,
                sizeBytes = size,
            )
        }
}
