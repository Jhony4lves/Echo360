package com.jhony4lves.echo360.domain.fix

import com.jhony4lves.echo360.network.ftp.FtpRoute

data class StfsMetadata(
    val magic: String,
    val contentType: Long,
    val mediaId: String,
    val titleId: String,
    val discNumber: Int,
    val discInSet: Int,
) {
    val contentTypeHex: String
        get() = contentType.toString(16).uppercase().padStart(8, '0')

    val contentTypeLabel: String
        get() = when (contentType) {
            0x00000001L -> "Saved Game"
            0x00000002L -> "Marketplace Content / DLC"
            0x00001000L -> "Xbox 360 Title"
            0x00004000L -> "Installed Game"
            0x00007000L -> "Game on Demand"
            0x000B0000L -> "Installer"
            0x000D0000L -> "Arcade Title"
            else -> "0x$contentTypeHex"
        }
}

enum class RepairSourceKind {
    Android,
    Xbox,
}

data class RepairSource(
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val contentUri: String? = null,
    val remotePath: String? = null,
    val kind: RepairSourceKind = RepairSourceKind.Android,
)

enum class RepairSeverity {
    Info,
    Warning,
    Error,
}

data class RepairIssue(
    val severity: RepairSeverity,
    val message: String,
    val sourcePath: String? = null,
)

data class RepairAction(
    val ruleId: String,
    val ruleName: String,
    val source: RepairSource,
    val metadata: StfsMetadata,
    val destinationRoot: String,
    val destinationPath: String,
)

data class RepairPlan(
    val selectedRootName: String,
    val selectedRootUri: String,
    val detectedPayloadPath: String? = null,
    val actions: List<RepairAction> = emptyList(),
    val issues: List<RepairIssue> = emptyList(),
    val sourceKind: RepairSourceKind = RepairSourceKind.Android,
    val selectedRootPath: String? = null,
) {
    val canExecute: Boolean
        get() = actions.isNotEmpty() && issues.none { it.severity == RepairSeverity.Error }

    val titleIds: Set<String>
        get() = actions.mapTo(linkedSetOf()) { it.metadata.titleId }

    val totalBytes: Long
        get() = actions.sumOf { it.source.size }
}

enum class RemoteRepairState {
    AlreadyCorrect,
    Missing,
    Conflict,
    SourceMissing,
}

data class RemoteRepairCheck(
    val action: RepairAction,
    val state: RemoteRepairState,
    val sourceSize: Long?,
    val destinationSize: Long?,
)

data class RemoteRepairValidation(
    val requestedRoute: FtpRoute,
    val usedRoute: FtpRoute,
    val checks: List<RemoteRepairCheck>,
    val fallbackReason: String? = null,
) {
    val alreadyCorrectCount: Int
        get() = checks.count { it.state == RemoteRepairState.AlreadyCorrect }

    val missingCount: Int
        get() = checks.count { it.state == RemoteRepairState.Missing }

    val conflictCount: Int
        get() = checks.count { it.state == RemoteRepairState.Conflict }

    val sourceMissingCount: Int
        get() = checks.count { it.state == RemoteRepairState.SourceMissing }

    val canMove: Boolean
        get() = checks.isNotEmpty() &&
            conflictCount == 0 &&
            sourceMissingCount == 0
}

enum class RemoteMoveStatus {
    Moved,
    AlreadyCorrect,
    Failed,
    RolledBack,
}

data class RemoteMoveResult(
    val action: RepairAction,
    val status: RemoteMoveStatus,
    val route: FtpRoute? = null,
    val message: String,
)

data class RemoteRepairExecution(
    val results: List<RemoteMoveResult>,
) {
    val movedCount: Int
        get() = results.count { it.status == RemoteMoveStatus.Moved }

    val alreadyCorrectCount: Int
        get() = results.count { it.status == RemoteMoveStatus.AlreadyCorrect }

    val failedCount: Int
        get() = results.count {
            it.status == RemoteMoveStatus.Failed || it.status == RemoteMoveStatus.RolledBack
        }

    val succeeded: Boolean
        get() = results.isNotEmpty() && failedCount == 0
}
