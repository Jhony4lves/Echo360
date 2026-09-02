package com.jhony4lves.echo360.domain.sync

import com.jhony4lves.echo360.network.ftp.FtpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SaveVaultManifestCodecTest {
    private val manifest = SaveVaultManifest(
        id = "vault-123",
        createdAtEpochMs = 123456789L,
        sourceRoot = "/Hdd1/Content/Profile/Title",
        route = FtpRoute.Background,
        fallbackReason = "Aurora indisponível",
        files = listOf(
            SaveVaultManifestFile("slot1/save.bin", 3L, "a".repeat(64)),
            SaveVaultManifestFile("settings.dat", 7L, "b".repeat(64)),
        ),
    )

    @Test
    fun `round trip preserves manifest semantics`() {
        val decoded = SaveVaultManifestCodec.decode(SaveVaultManifestCodec.encode(manifest))
        assertEquals(manifest, decoded)
        assertEquals(10L, decoded.totalBytes)
    }

    @Test
    fun `decoder rejects tampered byte total`() {
        val tampered = SaveVaultManifestCodec.encode(manifest).replace("BYTES\t10", "BYTES\t11")
        assertThrows(IllegalArgumentException::class.java) {
            SaveVaultManifestCodec.decode(tampered)
        }
    }

    @Test
    fun `decoder rejects traversal hidden inside encoded file path`() {
        val encoded = SaveVaultManifestCodec.encode(manifest)
        val pathToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("../evil.bin".toByteArray())
        val tampered = encoded.replace(
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("settings.dat".toByteArray()),
            pathToken,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SaveVaultManifestCodec.decode(tampered)
        }
    }
}
