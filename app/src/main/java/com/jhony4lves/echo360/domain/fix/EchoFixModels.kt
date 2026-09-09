package com.jhony4lves.echo360.domain.fix

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

data class RepairSource(
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val contentUri: String,
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
) {
    val canExecute: Boolean
        get() = actions.isNotEmpty() && issues.none { it.severity == RepairSeverity.Error }

    val titleIds: Set<String>
        get() = actions.mapTo(linkedSetOf()) { it.metadata.titleId }

    val totalBytes: Long
        get() = actions.sumOf { it.source.size }
}
