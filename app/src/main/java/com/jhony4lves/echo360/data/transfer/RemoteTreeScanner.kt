package com.jhony4lves.echo360.data.transfer

import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.RemoteTransferFile
import com.jhony4lves.echo360.domain.transfer.normalizeRelativePath
import com.jhony4lves.echo360.domain.transfer.relativeKey
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.ftp.FtpProtocolException
import com.jhony4lves.echo360.network.ftp.XboxFtpSession

class RemoteTreeScanner {
    suspend fun scanForLocalTree(
        session: XboxFtpSession,
        canonicalRemoteRoot: String,
        localTree: LocalTransferTree,
    ): List<RemoteTransferFile> {
        val root = XboxPath.canonical(canonicalRemoteRoot)
        val localDirectoryKeys = localTree.directories.mapTo(hashSetOf(), ::relativeKey)
        val remoteFiles = mutableListOf<RemoteTransferFile>()

        suspend fun walk(canonicalDirectory: String, relativeDirectory: String) {
            val entries = try {
                session.list(canonicalDirectory)
            } catch (error: FtpProtocolException) {
                if (error.ftpCode == 550) {
                    // A missing destination directory is a valid synchronization state:
                    // every matching local file below it is simply absent remotely.
                    return
                }
                throw error
            }

            for (entry in entries) {
                val relative = normalizeRelativePath(
                    listOf(relativeDirectory, entry.name)
                        .filter(String::isNotBlank)
                        .joinToString("/"),
                )

                if (entry.isDirectory) {
                    // Comparing a local folder does not require crawling unrelated Xbox trees.
                    if (relativeKey(relative) in localDirectoryKeys) {
                        walk(entry.canonicalPath, relative)
                    }
                } else {
                    remoteFiles += RemoteTransferFile(
                        relativePath = relative,
                        size = entry.size,
                        canonicalPath = entry.canonicalPath,
                    )
                }
            }
        }

        walk(root, "")
        return remoteFiles
    }
}
