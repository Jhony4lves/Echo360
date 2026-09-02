package com.jhony4lves.echo360.domain.mods

import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityCode
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityFinding
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityReport
import com.jhony4lves.echo360.domain.sync.SaveVaultIntegritySeverity
import com.jhony4lves.echo360.domain.sync.SaveVaultManifest
import com.jhony4lves.echo360.network.ftp.FtpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMutationSafetyPolicyTest {
    private val manifest = SaveVaultManifest(
        id = "vault-safe",
        createdAtEpochMs = 1L,
        sourceRoot = "/Hdd1/Content/0000000000000000/465307E4",
        route = FtpRoute.Fast,
        files = emptyList(),
    )

    @Test
    fun `approved only when verified rollback covers target`() {
        val decision = RemoteMutationSafetyPolicy.evaluate(
            targetCanonicalPath = "/Hdd1/Content/0000000000000000/465307E4/000B0000/tu00000008_00000000",
            rollbackManifest = manifest,
            rollbackIntegrity = goodReport(),
        )

        assertTrue(decision.approved)
        assertEquals(RemoteMutationSafetyCode.Approved, decision.code)
    }

    @Test
    fun `missing rollback blocks mutation`() {
        val decision = RemoteMutationSafetyPolicy.evaluate(
            targetCanonicalPath = "/Hdd1/Content/0000000000000000/465307E4/000B0000/file",
            rollbackManifest = null,
            rollbackIntegrity = null,
        )
        assertFalse(decision.approved)
        assertEquals(RemoteMutationSafetyCode.MissingRollbackSnapshot, decision.code)
    }

    @Test
    fun `target outside rollback root is blocked`() {
        val decision = RemoteMutationSafetyPolicy.evaluate(
            targetCanonicalPath = "/Hdd1/Content/0000000000000000/545408A7/000B0000/file",
            rollbackManifest = manifest,
            rollbackIntegrity = goodReport(),
        )
        assertFalse(decision.approved)
        assertEquals(RemoteMutationSafetyCode.TargetOutsideRollbackRoot, decision.code)
    }

    @Test
    fun `traversal cannot masquerade as a covered child target`() {
        val decision = RemoteMutationSafetyPolicy.evaluate(
            targetCanonicalPath = "/Hdd1/Content/0000000000000000/465307E4/../545408A7/file",
            rollbackManifest = manifest,
            rollbackIntegrity = goodReport(),
        )
        assertFalse(decision.approved)
        assertEquals(RemoteMutationSafetyCode.UnsafeTargetPath, decision.code)
    }

    @Test
    fun `tampered incomplete or extra rollback is blocked`() {
        val invalid = goodReport().copy(
            findings = listOf(
                SaveVaultIntegrityFinding(
                    code = SaveVaultIntegrityCode.HashMismatch,
                    severity = SaveVaultIntegritySeverity.Error,
                    relativePath = "save.bin",
                    evidence = "tampered",
                ),
            ),
        )
        assertEquals(
            RemoteMutationSafetyCode.RollbackSnapshotInvalid,
            RemoteMutationSafetyPolicy.evaluate("/Hdd1/Content/0000000000000000/465307E4/file", manifest, invalid).code,
        )

        val incomplete = goodReport().copy(checkedFiles = 0, expectedFiles = 1)
        assertEquals(
            RemoteMutationSafetyCode.RollbackSnapshotIncomplete,
            RemoteMutationSafetyPolicy.evaluate("/Hdd1/Content/0000000000000000/465307E4/file", manifest, incomplete).code,
        )

        val extras = goodReport().copy(extraFiles = 1)
        assertEquals(
            RemoteMutationSafetyCode.RollbackSnapshotHasExtras,
            RemoteMutationSafetyPolicy.evaluate("/Hdd1/Content/0000000000000000/465307E4/file", manifest, extras).code,
        )
    }

    private fun goodReport() = SaveVaultIntegrityReport(
        snapshotId = manifest.id,
        checkedFiles = 0,
        expectedFiles = 0,
        extraFiles = 0,
        findings = emptyList(),
    )
}
