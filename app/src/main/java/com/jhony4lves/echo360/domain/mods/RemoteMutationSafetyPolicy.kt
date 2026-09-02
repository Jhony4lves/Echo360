package com.jhony4lves.echo360.domain.mods

import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityReport
import com.jhony4lves.echo360.domain.sync.SaveVaultManifest
import com.jhony4lves.echo360.domain.sync.SaveVaultPathPolicy

enum class RemoteMutationSafetyCode {
    Approved,
    MissingRollbackSnapshot,
    IntegrityReportMismatch,
    RollbackSnapshotInvalid,
    RollbackSnapshotIncomplete,
    RollbackSnapshotHasExtras,
    UnsafeTargetPath,
    TargetOutsideRollbackRoot,
}

data class RemoteMutationSafetyDecision(
    val approved: Boolean,
    val code: RemoteMutationSafetyCode,
    val detail: String,
)

/**
 * Hard gate for every future EchoMods/EchoTU write path.
 *
 * This policy does not perform a mutation. It answers whether a separately
 * reviewed mutating executor would have a trustworthy local rollback anchor.
 */
object RemoteMutationSafetyPolicy {
    fun evaluate(
        targetCanonicalPath: String,
        rollbackManifest: SaveVaultManifest?,
        rollbackIntegrity: SaveVaultIntegrityReport?,
    ): RemoteMutationSafetyDecision {
        val target = runCatching { SaveVaultPathPolicy.canonicalSourceRoot(targetCanonicalPath) }
            .getOrElse {
                return blocked(
                    RemoteMutationSafetyCode.UnsafeTargetPath,
                    "O alvo remoto não passou pela política segura Hdd1/Usb0 do Vault.",
                )
            }

        if (rollbackManifest == null || rollbackIntegrity == null) {
            return blocked(
                RemoteMutationSafetyCode.MissingRollbackSnapshot,
                "Uma mutação remota exige um Save Vault verificado do alvo ou de uma pasta ancestral.",
            )
        }
        if (rollbackIntegrity.snapshotId != rollbackManifest.id) {
            return blocked(
                RemoteMutationSafetyCode.IntegrityReportMismatch,
                "O relatório de integridade não pertence ao snapshot informado.",
            )
        }
        if (!rollbackIntegrity.valid) {
            return blocked(
                RemoteMutationSafetyCode.RollbackSnapshotInvalid,
                "O snapshot de rollback possui erro de tamanho/hash/tipo ou arquivo ausente.",
            )
        }
        if (!rollbackIntegrity.complete) {
            return blocked(
                RemoteMutationSafetyCode.RollbackSnapshotIncomplete,
                "O snapshot de rollback não está completo.",
            )
        }
        if (rollbackIntegrity.extraFiles > 0) {
            return blocked(
                RemoteMutationSafetyCode.RollbackSnapshotHasExtras,
                "O snapshot local contém arquivos extras; normalize o Vault antes de usá-lo como rollback.",
            )
        }

        val root = runCatching { SaveVaultPathPolicy.canonicalSourceRoot(rollbackManifest.sourceRoot) }
            .getOrElse {
                return blocked(
                    RemoteMutationSafetyCode.RollbackSnapshotInvalid,
                    "A raiz do snapshot de rollback não passa pela política segura do Vault.",
                )
            }
        val inside = target.equals(root, ignoreCase = true) ||
            target.startsWith("$root/", ignoreCase = true)
        if (!inside) {
            return blocked(
                RemoteMutationSafetyCode.TargetOutsideRollbackRoot,
                "O alvo $target não está coberto pelo Vault ${rollbackManifest.sourceRoot}.",
            )
        }

        return RemoteMutationSafetyDecision(
            approved = true,
            code = RemoteMutationSafetyCode.Approved,
            detail = "Rollback local íntegro, completo e cobrindo o alvo. A escrita ainda exige um executor explicitamente aprovado.",
        )
    }

    private fun blocked(
        code: RemoteMutationSafetyCode,
        detail: String,
    ) = RemoteMutationSafetyDecision(
        approved = false,
        code = code,
        detail = detail,
    )
}
