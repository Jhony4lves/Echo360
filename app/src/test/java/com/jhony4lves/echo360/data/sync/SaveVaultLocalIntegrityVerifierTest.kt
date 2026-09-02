package com.jhony4lves.echo360.data.sync

import com.jhony4lves.echo360.domain.sync.SaveVaultIntegrityCode
import com.jhony4lves.echo360.domain.sync.SaveVaultManifest
import com.jhony4lves.echo360.domain.sync.SaveVaultManifestFile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SaveVaultLocalIntegrityVerifierTest {
    @Test
    fun `real payload hash matches manifest and extra file is only a warning`() = withSnapshot { snapshot ->
        val payload = snapshot.directory.resolve("payload")
        payload.mkdirs()
        payload.resolve("save.bin").writeBytes("abc".toByteArray())
        payload.resolve("extra.txt").writeText("extra")

        val report = runBlocking { SaveVaultLocalIntegrityVerifier().verify(snapshot) }

        assertTrue(report.valid)
        assertTrue(report.complete)
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.ExtraFile })
    }

    @Test
    fun `tampered payload is detected by sha256`() = withSnapshot { snapshot ->
        val payload = snapshot.directory.resolve("payload")
        payload.mkdirs()
        payload.resolve("save.bin").writeBytes("abd".toByteArray())

        val report = runBlocking { SaveVaultLocalIntegrityVerifier().verify(snapshot) }

        assertFalse(report.valid)
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.HashMismatch })
    }

    @Test
    fun `directory where manifest expects a file is wrong object type`() = withSnapshot { snapshot ->
        snapshot.directory.resolve("payload/save.bin").mkdirs()

        val report = runBlocking { SaveVaultLocalIntegrityVerifier().verify(snapshot) }

        assertFalse(report.valid)
        assertFalse(report.complete)
        assertTrue(report.findings.any { it.code == SaveVaultIntegrityCode.WrongObjectType })
    }

    @Test
    fun `symbolic link is rejected before target bytes can be trusted`() = withSnapshot { snapshot ->
        val payload = snapshot.directory.resolve("payload")
        payload.mkdirs()
        val outsideTarget = snapshot.directory.resolve("outside-target.bin")
        outsideTarget.writeBytes("abc".toByteArray())
        Files.createSymbolicLink(payload.resolve("save.bin").toPath(), outsideTarget.toPath())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { SaveVaultLocalIntegrityVerifier().verify(snapshot) }
        }
    }

    private fun withSnapshot(block: (StoredSaveVaultSnapshot) -> Unit) {
        val directory = Files.createTempDirectory("echo-vault-integrity-").toFile()
        try {
            val manifest = SaveVaultManifest(
                id = directory.name,
                createdAtEpochMs = 1L,
                sourceRoot = "/Hdd1/Content/Profile",
                route = FtpRoute.Fast,
                files = listOf(
                    SaveVaultManifestFile(
                        relativePath = "save.bin",
                        size = 3L,
                        sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    ),
                ),
            )
            block(StoredSaveVaultSnapshot(manifest, directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
