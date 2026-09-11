package com.jhony4lves.echo360.domain.fix

import com.jhony4lves.echo360.domain.xbox.XboxFatx
import com.jhony4lves.echo360.domain.xbox.XboxPath

object StockDlcInstallerRule {
    const val ID: String = "stock-dlc-installer-disc"
    const val NAME: String = "Stock DLC Installer Disc"
    const val EXPECTED_CONTENT_TYPE: Long = 0x00000002L

    fun plan(source: RepairSource, metadata: StfsMetadata): RepairAction {
        require(metadata.contentType == EXPECTED_CONTENT_TYPE) {
            "${source.fileName}: Content Type 0x${metadata.contentTypeHex} não é DLC/Marketplace Content."
        }
        require(metadata.titleId != "00000000") {
            "${source.fileName}: Title ID inválido (00000000)."
        }

        val safeName = XboxFatx.safeSegment(source.fileName)
        val destinationRoot = XboxPath.canonical(
            "/Hdd1/Content/0000000000000000/${metadata.titleId}/00000002",
        )
        val destinationPath = XboxFatx.requireCompatiblePath(
            XboxPath.canonical("$destinationRoot/$safeName"),
        )

        return RepairAction(
            ruleId = ID,
            ruleName = NAME,
            source = source,
            metadata = metadata,
            destinationRoot = destinationRoot,
            destinationPath = destinationPath,
        )
    }
}
