package com.jhony4lves.echo360.data.mods

import android.content.Context
import com.jhony4lves.echo360.data.sync.SaveVaultLocalIntegrityVerifier
import com.jhony4lves.echo360.data.sync.SaveVaultStore
import com.jhony4lves.echo360.domain.mods.RemoteMutationSafetyCode
import com.jhony4lves.echo360.domain.mods.RemoteMutationSafetyDecision
import com.jhony4lves.echo360.domain.mods.RemoteMutationSafetyPolicy
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.domain.xbox.XboxPath

data class EchoRollbackAssessment(
    val snapshotId: String?,
    val sourceRoot: String?,
    val decision: RemoteMutationSafetyDecision,
)

class EchoMutationRollbackRepository(
    context: Context,
    private val verifier: SaveVaultLocalIntegrityVerifier = SaveVaultLocalIntegrityVerifier(),
) {
    private val store = SaveVaultStore(context.applicationContext)

    suspend fun assess(
        targetCanonicalPath: String,
        cancellationToken: TransferCancellationToken = TransferCancellationToken(),
    ): EchoRollbackAssessment {
        val target = XboxPath.canonical(targetCanonicalPath)
        val candidates = store.snapshots()
            .asSequence()
            .filter { snapshot -> covers(snapshot.manifest.sourceRoot, target) }
            .take(MAX_SNAPSHOTS_TO_VERIFY)
            .toList()

        if (candidates.isEmpty()) {
            return EchoRollbackAssessment(
                snapshotId = null,
                sourceRoot = null,
                decision = RemoteMutationSafetyPolicy.evaluate(target, null, null),
            )
        }

        var firstBlocked: EchoRollbackAssessment? = null
        for (snapshot in candidates) {
            if (cancellationToken.isCancelled()) {
                return EchoRollbackAssessment(
                    snapshotId = snapshot.manifest.id,
                    sourceRoot = snapshot.manifest.sourceRoot,
                    decision = RemoteMutationSafetyDecision(
                        approved = false,
                        code = RemoteMutationSafetyCode.RollbackSnapshotIncomplete,
                        detail = "Checagem de rollback cancelada antes da aprovação.",
                    ),
                )
            }
            val report = runCatching {
                verifier.verify(snapshot, cancellationToken)
            }.getOrElse { failure ->
                val blocked = EchoRollbackAssessment(
                    snapshotId = snapshot.manifest.id,
                    sourceRoot = snapshot.manifest.sourceRoot,
                    decision = RemoteMutationSafetyDecision(
                        approved = false,
                        code = RemoteMutationSafetyCode.RollbackSnapshotInvalid,
                        detail = failure.message ?: "Não foi possível verificar o snapshot de rollback.",
                    ),
                )
                if (firstBlocked == null) firstBlocked = blocked
                continue
            }
            val decision = RemoteMutationSafetyPolicy.evaluate(
                targetCanonicalPath = target,
                rollbackManifest = snapshot.manifest,
                rollbackIntegrity = report,
            )
            val assessment = EchoRollbackAssessment(
                snapshotId = snapshot.manifest.id,
                sourceRoot = snapshot.manifest.sourceRoot,
                decision = decision,
            )
            if (decision.approved) return assessment
            if (firstBlocked == null) firstBlocked = assessment
        }

        return firstBlocked ?: EchoRollbackAssessment(
            snapshotId = null,
            sourceRoot = null,
            decision = RemoteMutationSafetyPolicy.evaluate(target, null, null),
        )
    }

    private fun covers(rootInput: String, target: String): Boolean {
        val root = XboxPath.canonical(rootInput).trimEnd('/')
        return target.equals(root, ignoreCase = true) || target.startsWith("$root/", ignoreCase = true)
    }

    companion object {
        private const val MAX_SNAPSHOTS_TO_VERIFY = 20
    }
}
