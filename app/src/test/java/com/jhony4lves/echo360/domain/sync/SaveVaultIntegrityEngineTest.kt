package com.jhony4lves.echo360.domain.sync

import com.jhony4lves.echo360.network.ftp.FtpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveVaultIntegrityEngineTest {
    private val manifest = SaveVaultManifest(
        id = "vault-test",
        createdAtEpochMs = 1L,
        sourceRoot = "/Hdd1/Content/Profile",
        route = FtpRoute.Fast,
        files = listOf(
            SaveVaultManifestFile("a.bin", 3L, "a".repeat(64)),
            SaveVaultManifestFile("sub/b.bin", 4L, "b".repeat(64)),
        ),
    )

    @Test
    fun `matching payload is valid and complete`() {
        val report = SaveVaultIntegrityEngine.verify(
            manifest,
            listOf(
                evidence("a.bin", 3L, "a"),
                evidence("sub/b.bin", 4L, "b"),
            ),
        )

        assertTrue(report.valid)
        assertTrue(report.complete)
        assertEquals(2, report.checkedFiles)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `missing size and hash mismatches are explicit errors`() {
        val report = SaveVaultIntegrityEngine.verify(
            manifest,
            listOf(evidence("a.bin", 2L, "c")),
        )

        assertFalse(report.valid)
        assertFalse(report.complete)
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.MissingFile && it.relativePath == "sub/b.bin" })
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.SizeMismatch && it.relativePath == "a.bin" })
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.HashMismatch && it.relativePath == "a.bin" })
    }

    @Test
    fun `extra local payload is warning and does not need a hash`() {
        val report = SaveVaultIntegrityEngine.verify(
            manifest = manifest,
            localFiles = listOf(
                evidence("a.bin", 3L, "a"),
                evidence("sub/b.bin", 4L, "b"),
            ),
            extraRelativePaths = listOf("extra.bin"),
        )

        assertTrue(report.valid)
        assertTrue(report.complete)
        assertEquals(1, report.extraFiles)
        assertEquals(SaveVaultIntegritySeverity.Warning, report.findings.single().severity)
    }

    @Test
    fun `path matching is case insensitive like manifest duplicate policy`() {
        val report = SaveVaultIntegrityEngine.verify(
            manifest,
            listOf(
                evidence("A.BIN", 3L, "a"),
                evidence("SUB/B.BIN", 4L, "b"),
            ),
        )

        assertTrue(report.valid)
        assertTrue(report.complete)
    }

    @Test
    fun `declared path cannot also be supplied as extra`() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveVaultIntegrityEngine.verify(
                manifest = manifest,
                localFiles = listOf(
                    evidence("a.bin", 3L, "a"),
                    evidence("sub/b.bin", 4L, "b"),
                ),
                extraRelativePaths = listOf("A.BIN"),
            )
        }
    }

    private fun evidence(path: String, size: Long, hex: String) = SaveVaultLocalFileEvidence(
        relativePath = path,
        size = size,
        sha256 = hex.repeat(64),
    )
}
