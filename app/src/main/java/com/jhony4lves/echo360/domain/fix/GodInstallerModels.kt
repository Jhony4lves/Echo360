package com.jhony4lves.echo360.domain.fix

import com.jhony4lves.echo360.network.ftp.FtpRoute

data class GodDataPart(
    val name: String,
    val canonicalPath: String,
    val rawSize: Long,
    val payloadSize: Long,
)

data class GodPackageCandidate(
    val titleIdDirectory: String,
    val packageName: String,
    val headerPath: String,
    val dataDirectoryPath: String,
    val headerSize: Long,
    val metadata: StfsMetadata,
    val dataParts: List<GodDataPart>,
    val estimatedIsoBytes: Long,
) {
    val rawDataBytes: Long
        get() = dataParts.sumOf(GodDataPart::rawSize)

    val label: String
        get() = buildString {
            append(metadata.titleId)
            if (metadata.discNumber > 0) {
                append(" • Disc ")
                append(metadata.discNumber)
                if (metadata.discInSet > 0) {
                    append('/')
                    append(metadata.discInSet)
                }
            }
        }
}

data class GodInstallerScanResult(
    val rootPath: String,
    val candidates: List<GodPackageCandidate>,
    val issues: List<RepairIssue> = emptyList(),
)

data class GodEmbeddedPayload(
    val internalPath: String,
    val isoOffset: Long,
    val action: RepairAction,
)

data class GodInstallerPlan(
    val candidate: GodPackageCandidate,
    val tempIsoPath: String,
    val tempIsoBytes: Long,
    val hasXsfHeader: Boolean,
    val sectorCorrection: Int,
    val detectedPayloadPath: String?,
    val payloads: List<GodEmbeddedPayload>,
    val issues: List<RepairIssue> = emptyList(),
) {
    val canExecute: Boolean
        get() = payloads.isNotEmpty() && issues.none { it.severity == RepairSeverity.Error }

    val totalPayloadBytes: Long
        get() = payloads.sumOf { it.action.source.size }

    val titleIds: Set<String>
        get() = payloads.mapTo(linkedSetOf()) { it.action.metadata.titleId }
}

enum class GodPayloadState {
    AlreadyCorrect,
    Missing,
    Conflict,
    TempSourceMissing,
}

data class GodPayloadCheck(
    val payload: GodEmbeddedPayload,
    val state: GodPayloadState,
    val destinationSize: Long?,
)

data class GodInstallerValidation(
    val requestedRoute: FtpRoute,
    val usedRoute: FtpRoute,
    val checks: List<GodPayloadCheck>,
    val fallbackReason: String? = null,
) {
    val alreadyCorrectCount: Int
        get() = checks.count { it.state == GodPayloadState.AlreadyCorrect }

    val missingCount: Int
        get() = checks.count { it.state == GodPayloadState.Missing }

    val conflictCount: Int
        get() = checks.count { it.state == GodPayloadState.Conflict }

    val tempMissingCount: Int
        get() = checks.count { it.state == GodPayloadState.TempSourceMissing }

    val canInstall: Boolean
        get() = checks.isNotEmpty() && conflictCount == 0 && tempMissingCount == 0
}

enum class GodInstallStatus {
    Installed,
    AlreadyCorrect,
    Failed,
}

data class GodInstallResult(
    val payload: GodEmbeddedPayload,
    val status: GodInstallStatus,
    val route: FtpRoute? = null,
    val message: String,
)

data class GodInstallerExecution(
    val results: List<GodInstallResult>,
    val tempIsoDeleted: Boolean,
) {
    val installedCount: Int
        get() = results.count { it.status == GodInstallStatus.Installed }

    val alreadyCorrectCount: Int
        get() = results.count { it.status == GodInstallStatus.AlreadyCorrect }

    val failedCount: Int
        get() = results.count { it.status == GodInstallStatus.Failed }

    val succeeded: Boolean
        get() = results.isNotEmpty() && failedCount == 0
}

enum class GodRepairStage {
    ReadingContainer,
    ReconstructingIso,
    InspectingXdvdfs,
    ValidatingDestinations,
    InstallingPayloads,
    Verifying,
    CleaningTemp,
}

data class GodRepairProgress(
    val stage: GodRepairStage,
    val message: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (completedBytes.toDouble() / totalBytes.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
}
