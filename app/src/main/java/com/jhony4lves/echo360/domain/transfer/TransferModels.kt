package com.jhony4lves.echo360.domain.transfer

import com.jhony4lves.echo360.network.ftp.FtpRoute

data class LocalTransferFile(
    val relativePath: String,
    val size: Long,
    val contentUri: String,
)

data class LocalTransferTree(
    val rootUri: String,
    val rootName: String,
    val files: List<LocalTransferFile>,
    /** Relative directory paths, root represented by an empty string. */
    val directories: Set<String>,
)

data class RemoteTransferFile(
    val relativePath: String,
    val size: Long,
    val canonicalPath: String,
)

enum class TransferDiffKind {
    Same,
    Missing,
    Different,
}

data class TransferDiffItem(
    val relativePath: String,
    val kind: TransferDiffKind,
    val local: LocalTransferFile,
    val remote: RemoteTransferFile? = null,
) {
    val bytesToUpload: Long
        get() = if (kind == TransferDiffKind.Same) 0L else local.size
}

data class TransferAnalysis(
    val localRootName: String,
    val remoteRoot: String,
    val requestedRoute: FtpRoute,
    val usedRoute: FtpRoute,
    val fallbackReason: String? = null,
    val localFileCount: Int,
    val remoteFileCount: Int,
    val sameCount: Int,
    val missingCount: Int,
    val differentCount: Int,
    val uploadBytes: Long,
    val items: List<TransferDiffItem>,
) {
    val uploadCount: Int get() = missingCount + differentCount
}

internal fun normalizeRelativePath(path: String): String = path
    .replace('\\', '/')
    .trim('/')
    .replace(Regex("/+"), "/")

internal fun relativeKey(path: String): String = normalizeRelativePath(path).lowercase()
