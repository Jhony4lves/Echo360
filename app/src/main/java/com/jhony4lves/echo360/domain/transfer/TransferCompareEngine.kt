package com.jhony4lves.echo360.domain.transfer

import com.jhony4lves.echo360.network.ftp.FtpRoute

object TransferCompareEngine {
    fun compare(
        local: LocalTransferTree,
        remoteRoot: String,
        requestedRoute: FtpRoute,
        usedRoute: FtpRoute,
        fallbackReason: String?,
        remoteFiles: List<RemoteTransferFile>,
    ): TransferAnalysis {
        val remoteByPath = remoteFiles.associateBy { relativeKey(it.relativePath) }

        val items = local.files
            .sortedBy { relativeKey(it.relativePath) }
            .map { localFile ->
                val remoteFile = remoteByPath[relativeKey(localFile.relativePath)]
                val kind = when {
                    remoteFile == null -> TransferDiffKind.Missing
                    remoteFile.size != localFile.size -> TransferDiffKind.Different
                    else -> TransferDiffKind.Same
                }

                TransferDiffItem(
                    relativePath = normalizeRelativePath(localFile.relativePath),
                    kind = kind,
                    local = localFile,
                    remote = remoteFile,
                )
            }

        return TransferAnalysis(
            localRootName = local.rootName,
            remoteRoot = remoteRoot,
            requestedRoute = requestedRoute,
            usedRoute = usedRoute,
            fallbackReason = fallbackReason,
            localFileCount = local.files.size,
            remoteFileCount = remoteFiles.size,
            sameCount = items.count { it.kind == TransferDiffKind.Same },
            missingCount = items.count { it.kind == TransferDiffKind.Missing },
            differentCount = items.count { it.kind == TransferDiffKind.Different },
            uploadBytes = items.sumOf(TransferDiffItem::bytesToUpload),
            items = items,
        )
    }
}
